defmodule RisiMe.Agent.Mls do
  @moduledoc """
  Owns Risi's MLS handle (`RisiMe.Agent.Mls.Nif`, decision 067): **one serial lane** for every
  MLS operation of Risi's device.

  * **Open:** loads every `risi_mls_kv` row of Risi's device and calls `Nif.open/5` once, with
    the attestation public JWKs as trust anchors and `RISI_MLS_KEK`.
  * **Attestation (§10.0, §24.11):** on first use (or when the device row's key differs) the
    device's signature key is attested by the server's attestation key with `"kind": "agent"`
    (`RisiMe.MLS.Attestation.sign/4`), `set_attestation` stores it in the handle, and the device
    row gets the key, the JWS and the capabilities `["groups", "tabs"]` (§24.7).
  * **Journal:** `call/2` runs a NIF call on the handle and applies the returned journal in
    **one** Postgres transaction **before** the result is returned. If that write fails the
    handle is dropped and reopened from the rows (it was ahead of the database) and the call
    answers `{:error, {:storage, _}}`; a `:poisoned` handle is reopened the same way.

  The KEK is held only in this process's state and is never logged (`format_status/1`).
  """
  use GenServer

  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{KeyCheck, Status}
  alias RisiMe.Agent.Mls.Nif
  alias RisiMe.Devices.Device
  alias RisiMe.MLS.Attestation
  alias RisiMe.{Repo, Risi}

  @chunk 1000

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  @doc """
  Runs `fun.(handle)` (a `Nif` call returning `{:ok, result, journal}` or `{:error, _}`) in the
  serial lane. Returns `{:ok, result}` once the journal is persisted, or `{:error, {kind, msg}}`.
  """
  def call(fun, timeout \\ 60_000) when is_function(fun, 1),
    do: GenServer.call(__MODULE__, {:call, fun}, timeout)

  @doc "The MLS epoch of a group Risi holds: `{:ok, epoch}` or `{:error, {:unknown_group, _}}`."
  def epoch(group_id), do: call(&Nif.epoch(&1, group_id))

  ## Server

  @impl true
  def init(_opts) do
    # Opened in init: the children after this one (rest_for_one) start with a registered,
    # attested device. A failure is recorded (`Agent.Status`) and stops only the tree:
    # `Agent.Starter` logs it once and reports it in /health (P0 2026-10-08).
    result =
      case RisiMe.Agent.kek() do
        {:ok, kek} ->
          open(%{handle: nil, user_id: Risi.user_id(), device_id: Risi.device_id(), kek: kek})

        :error ->
          {:error, :missing_mls_kek}
      end

    case result do
      {:ok, state} ->
        Status.clear_failure()
        {:ok, state}

      {:error, reason} ->
        Status.failure(reason)
        {:stop, {:shutdown, {:open_failed, reason}}}
    end
  end

  @impl true
  def handle_call({:call, _fun}, _from, %{handle: nil} = state),
    do: {:reply, {:error, {:storage, "not open"}}, state}

  def handle_call({:call, fun}, _from, state) do
    result =
      try do
        fun.(state.handle)
      rescue
        e -> {:error, {:other, Exception.message(e)}}
      end

    case result do
      {:ok, value, journal} ->
        case persist(state.device_id, journal) do
          :ok ->
            {:reply, {:ok, value}, state}

          {:error, reason} ->
            Logger.error("Risi MLS journal write failed (#{reason}); reopening")
            {:reply, {:error, {:storage, "journal"}}, reopen(state)}
        end

      {:error, {:poisoned, _}} = e ->
        {:reply, e, reopen(state)}

      {:error, {kind, msg}} = e when is_atom(kind) and is_binary(msg) ->
        {:reply, e, state}

      other ->
        {:reply, {:error, {:other, inspect(other)}}, state}
    end
  end

  @impl true
  def format_status(status) do
    Map.new(status, fn
      {:state, s} when is_map(s) -> {:state, %{s | kek: :redacted, handle: s.handle && :handle}}
      {:message, _} -> {:message, :redacted}
      other -> other
    end)
  end

  defp reopen(state) do
    case open(%{state | handle: nil}) do
      {:ok, state} ->
        state

      {:error, reason} ->
        # Stop: the supervisor restarts the tree after us from the rows.
        Status.failure({:reopen_failed, reason})
        exit({:shutdown, {:reopen_failed, reason}})
    end
  end

  ## Open

  # The key check (`Agent.KeyCheck`) comes first: a KEK that doesn't match the one that sealed
  # the rows is `:kek_mismatch` without an open. With no check yet (rows written before it
  # existed) a `:tampered`/`:bad_kek` open of existing rows is reported as `:kek_mismatch` too
  # (the P0 cause); the check is written after the first successful open. An empty store may
  # take a new KEK (nothing sealed to lose).
  defp open(state) do
    %{user_id: user, device_id: dev, kek: kek} = state
    _ = Risi.seed()
    rows = load_rows(dev)
    check = KeyCheck.compare(KeyCheck.mls_name(dev), kek)

    with :ok <- kek_ok(check, rows),
         {:anchors, [_ | _] = anchors} <- {:anchors, trust_anchors()},
         {:ok, h, j} <- open_rows(user, dev, anchors, kek, rows, check),
         :ok <- persist(dev, j),
         {:ok, pk, j} <- Nif.signature_public_key(h),
         :ok <- persist(dev, j),
         :ok <- register(h, user, dev, pk, rows == []) do
      if check != :ok, do: KeyCheck.record(KeyCheck.mls_name(dev), kek)
      {:ok, %{state | handle: h}}
    else
      {:anchors, []} -> {:error, :mls_unavailable}
      {:error, {kind, _msg}} when is_atom(kind) -> {:error, kind}
      {:error, reason} -> {:error, reason}
    end
  rescue
    e -> {:error, RisiMe.Agent.exception_reason(e)}
  end

  @doc """
  The read-only check behind `RisiMe.Agent.preflight/0`: the key check and an `open` of the
  device's rows **in memory** (no seed, no journal, no registration). `{:ok, info}` (one line,
  no key material) or `{:error, reason}`.
  """
  def verify(kek) do
    {user, dev} = {Risi.user_id(), Risi.device_id()}
    rows = load_rows(dev)
    check = KeyCheck.compare(KeyCheck.mls_name(dev), kek)

    with :ok <- kek_ok(check, rows),
         {:anchors, [_ | _] = anchors} <- {:anchors, trust_anchors()},
         {:ok, _h, _j} <- open_rows(user, dev, anchors, kek, rows, check) do
      {:ok, "risi_mls_kv: #{length(rows)} row(s) open; key check #{check}"}
    else
      {:anchors, []} -> {:error, :mls_unavailable}
      {:error, {kind, _msg}} when is_atom(kind) -> {:error, kind}
      {:error, reason} -> {:error, reason}
    end
  end

  defp kek_ok(:mismatch, [_ | _]), do: {:error, :kek_mismatch}
  defp kek_ok(_check, _rows), do: :ok

  defp open_rows(user, dev, anchors, kek, rows, check) do
    case Nif.open(user, dev, anchors, kek, rows) do
      {:error, {kind, _}} when kind in [:tampered, :bad_kek] and check != :ok and rows != [] ->
        {:error, :kek_mismatch}

      other ->
        other
    end
  end

  defp load_rows(dev) do
    Repo.all(
      from r in "risi_mls_kv",
        where: r.device_id == type(^dev, :binary_id),
        select: {r.key, r.value}
    )
  end

  defp trust_anchors, do: Enum.map(Attestation.public_keys(), &Jason.encode!/1)

  # §24.11: the agent device's JWS carries `"kind": "agent"`, signed by the server's attestation
  # key. Re-attested when the identity is new or the device row holds another key.
  defp register(h, user, dev, pk, fresh?) do
    d = Repo.get_by(Device, user_id: user, device_id: dev)

    if fresh? or d == nil or d.mls_signature_key != pk or d.mls_attestation == nil do
      with {:ok, jws} <- Attestation.sign(user, dev, Base.encode64(pk), kind: "agent"),
           {:ok, :ok, j} <- Nif.set_attestation(h, jws),
           :ok <- persist(dev, j) do
        replaced? = d != nil and d.mls_signature_key != nil and d.mls_signature_key != pk
        store_device(d, user, dev, pk, jws)

        if replaced?,
          do: RisiMe.Groups.device_changed(user, dev, :replaced, only: :official)

        :ok
      end
    else
      :ok
    end
  end

  defp store_device(nil, user, dev, pk, jws) do
    now = DateTime.utc_now()

    Repo.insert!(%Device{
      user_id: user,
      device_id: dev,
      platform: "agent",
      capabilities: Risi.capabilities(),
      mls_signature_key: pk,
      mls_attestation: jws,
      mls_attested_at: now,
      last_seen_at: now
    })
  end

  defp store_device(d, _user, _dev, pk, jws) do
    d
    |> Ecto.Changeset.change(
      mls_signature_key: pk,
      mls_attestation: jws,
      mls_attested_at: DateTime.utc_now(),
      capabilities: Risi.capabilities()
    )
    |> Repo.update!()
  end

  ## Journal

  @doc false
  # The journal in one transaction; the last write of a key wins, deletes included.
  def persist(_dev, []), do: :ok

  def persist(dev, journal) do
    final = Enum.reduce(journal, %{}, fn {k, v}, acc -> Map.put(acc, k, v) end)
    {ups, dels} = Enum.split_with(final, fn {_k, v} -> is_binary(v) end)
    dev_bin = Ecto.UUID.dump!(dev)

    Repo.transaction(fn ->
      ups
      |> Enum.chunk_every(@chunk)
      |> Enum.each(fn chunk ->
        Repo.insert_all(
          "risi_mls_kv",
          for({k, v} <- chunk, do: %{device_id: dev_bin, key: k, value: v}),
          on_conflict: {:replace, [:value]},
          conflict_target: [:device_id, :key]
        )
      end)

      dels
      |> Enum.map(&elem(&1, 0))
      |> Enum.chunk_every(@chunk)
      |> Enum.each(fn keys ->
        Repo.delete_all(
          from r in "risi_mls_kv",
            where:
              r.device_id == type(^dev, :binary_id) and r.key in type(^keys, {:array, :binary})
        )
      end)
    end)
    |> case do
      {:ok, _} -> :ok
      {:error, _} -> {:error, "rollback"}
    end
  rescue
    e -> {:error, e.__struct__ |> inspect()}
  end
end
