defmodule RisiMe.Agent.KeyPackages do
  @moduledoc """
  Keeps Risi's device claimable (§10.1, §24.2): at least #{20} normal key packages uploaded (topped
  up to #{30}) plus a last-resort one, through `RisiMe.MLS.upload_key_packages/3`, the function
  the REST endpoint uses. Checked at start, after every Welcome Risi joins (`refill/0`) and every
  minute (`:risi_kp_interval_ms`). Key packages are generated in `RisiMe.Agent.Mls` (journal
  persisted before upload).
  """
  use GenServer

  import Ecto.Query

  require Logger

  alias RisiMe.Agent.Mls
  alias RisiMe.Agent.Mls.Nif
  alias RisiMe.{MLS, Repo, Risi}

  @min 20
  @target 30

  def start_link(_opts \\ []), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "Asks for a check (asynchronous)."
  def refill, do: GenServer.cast(__MODULE__, :refill)

  @doc "Runs a check now and returns the number of normal key packages stored afterwards."
  def refill_now, do: GenServer.call(__MODULE__, :refill_now, 60_000)

  @impl true
  def init(:ok) do
    send(self(), :tick)
    {:ok, %{}}
  end

  @impl true
  def handle_info(:tick, state) do
    check()
    Process.send_after(self(), :tick, Application.get_env(:risime, :risi_kp_interval_ms, 60_000))
    {:noreply, state}
  end

  def handle_info(_msg, state), do: {:noreply, state}

  @impl true
  def handle_cast(:refill, state) do
    check()
    {:noreply, state}
  end

  @impl true
  def handle_call(:refill_now, _from, state), do: {:reply, check(), state}

  defp check do
    user = Risi.user_id()
    dev = Risi.device_id()

    with {:ok, n} <- MLS.key_package_count(user, dev) do
      kps = if n < @min, do: generate(@target - n), else: []
      last = if last_resort?(user, dev), do: nil, else: last_resort()

      if kps != [] or last != nil do
        params = %{"key_packages" => Enum.map(kps, &Base.encode64/1)}
        params = if last, do: Map.put(params, "last_resort", Base.encode64(last)), else: params

        case MLS.upload_key_packages(user, dev, params) do
          :ok -> :ok
          {:error, reason} -> Logger.warning("Risi key package upload failed: #{reason}")
        end
      end

      n + length(kps)
    else
      {:error, reason} ->
        Logger.warning("Risi key packages unavailable: #{reason}")
        0
    end
  rescue
    e ->
      Logger.warning("Risi key package check failed: #{Exception.message(e)}")
      0
  end

  defp generate(n) do
    case Mls.call(&Nif.generate_key_packages(&1, n)) do
      {:ok, kps} ->
        kps

      {:error, {kind, _}} ->
        Logger.warning("Risi key package generation failed: #{kind}")
        []
    end
  end

  defp last_resort do
    case Mls.call(&Nif.last_resort_key_package/1) do
      {:ok, kp} -> kp
      {:error, _} -> nil
    end
  end

  defp last_resort?(user, dev) do
    Repo.exists?(
      from k in "mls_key_packages",
        join: d in RisiMe.Devices.Device,
        on: k.device_ref == d.id,
        where: d.user_id == ^user and d.device_id == ^dev and k.last_resort
    )
  end
end
