defmodule RisiMe.Agent do
  @moduledoc """
  The Risi agent tree: Risi's MLS member client inside the server (contract v1.24 §24.11,
  decisions 065, 066, 067; server S5), and its stage-1 secretary (S6/S7:
  `RisiMe.Agent.Secretary`, `RisiMe.Agent.LLM`, `RisiMe.Agent.LearningLog`).

  ## When it runs
  `RisiMe.Agent.Starter` (after the endpoint, see `children/1`) starts `RisiMe.Agent.Supervisor`
  only when **all** of these hold (`startable?/0`) and the key checks pass
  (`RisiMe.Agent.KeyCheck`); otherwise it is not started, `RisiMe.Risi.available?/0` is false,
  so every chat reports `agent_unavailable` (§24.15), and `/health` says why (`health/0`):

    * `RISI=on`;
    * the MLS NIF is loaded (`RisiMe.Agent.Mls.Nif.loaded?/0`, `scripts/build-mls-nif`);
    * `RISI_MLS_KEK`: base64 of 32 bytes (seals Risi's MLS state in `risi_mls_kv`);
    * `RISI_DATA_KEY`: base64 of 32 bytes (seals the `risi_buffer` bodies).

  It is a `:temporary` child of `RisiMe.Agent.TreeSup`: a tree that fails to start or keeps
  crashing stops (Risi becomes unavailable, one error is logged) and never stops the boot or the
  server (P0 2026-10-08).

  ## The tree (`rest_for_one`)
  `Registry` → `Agent.Mls` (the NIF handle, one serial lane) → `Agent.KeyPackages` →
  `Agent.ConversationSup` (one `Agent.Conversation` per Official group) → `Agent.Inbox` (Risi's
  inbox, drained like a client's).

  ## Private is never touched (§24.0, §24.5)
  Every entry point checks a **server query** on `groups.tab = 'official'` (`official?/1`), and
  the inbox additionally the §24.5 query (`RisiMe.Groups.Tabs.agent_conversation?/2`), before any
  MLS call or any buffer write; the NIF refuses non-Official groups on its own as well. Nothing in
  the tree ever logs plaintext.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.Mls.Nif
  alias RisiMe.{Repo, Risi}

  @doc "True when the tree may start: RISI on, the NIF loaded, both keys present and valid."
  def startable?, do: problems() == []

  @doc """
  Why the tree can't start, as reasons (`:risi_off`, `:nif_not_loaded`, `:missing_mls_kek`,
  `:missing_data_key`); `[]` when it can.
  """
  def problems do
    [
      {Risi.enabled?(), :risi_off},
      {Nif.loaded?(), :nif_not_loaded},
      {match?({:ok, _}, kek()), :missing_mls_kek},
      {match?({:ok, _}, data_key()), :missing_data_key}
    ]
    |> Enum.reject(&elem(&1, 0))
    |> Enum.map(&elem(&1, 1))
  end

  @doc "Why the tree can't start (names only, never values)."
  def missing, do: Enum.map(problems(), &problem_text/1)

  defp problem_text(:risi_off), do: "RISI=on"
  defp problem_text(:nif_not_loaded), do: "the MLS NIF (scripts/build-mls-nif)"
  defp problem_text(:missing_mls_kek), do: "RISI_MLS_KEK (base64, 32 bytes)"
  defp problem_text(:missing_data_key), do: "RISI_DATA_KEY (base64, 32 bytes)"

  @doc false
  def kek, do: key(:risi_mls_kek)

  @doc false
  def data_key, do: key(:risi_data_key)

  defp key(name) do
    with b64 when is_binary(b64) <- Application.get_env(:risime, name),
         {:ok, <<_::binary-size(32)>> = k} <- Base.decode64(String.trim(b64)) do
      {:ok, k}
    else
      _ -> :error
    end
  end

  @doc """
  The application's children for Risi, started **after** the endpoint (P0 2026-10-08): an empty
  `DynamicSupervisor` (`RisiMe.Agent.TreeSup`) and `RisiMe.Agent.Starter`, which checks the
  start conditions and the key checks, then starts `RisiMe.Agent.Supervisor` under it as a
  `:temporary` child. Neither can fail: whatever goes wrong (RISI off, no NIF, missing or wrong
  keys, `:tampered`, a DB error, a crash loop later) only makes Risi unavailable (`health/0`),
  never the boot or the server. Options (tests): `:sup`, `:name`.
  """
  def children(opts \\ []) do
    sup = Keyword.get(opts, :sup, RisiMe.Agent.TreeSup)
    starter = Keyword.get(opts, :name, RisiMe.Agent.Starter)

    [
      Supervisor.child_spec({DynamicSupervisor, name: sup, strategy: :one_for_one}, id: sup),
      Supervisor.child_spec({RisiMe.Agent.Starter, sup: sup, name: starter},
        id: starter,
        restart: :temporary
      )
    ]
  end

  @doc """
  `/health` `checks.risi`: `"off"` (RISI off), `"ok"` (the tree is running) or
  `"unavailable: <reason>"` (names only, never key material).
  """
  def health do
    cond do
      not Risi.enabled?() -> "off"
      running?() -> "ok"
      true -> "unavailable: " <> describe(RisiMe.Agent.Status.get() || :not_started)
    end
  end

  @doc "A reason as one line for logs, `/health` and the preflight (never key material)."
  def describe(:kek_mismatch),
    do: "kek_mismatch (RISI_MLS_KEK does not match the sealed store)"

  def describe({:not_startable, problems}),
    do: Enum.map_join(problems, ", ", &not_startable_text/1)

  def describe({:crashed, why}), do: "crashed after start: " <> describe(why)
  def describe({:reopen_failed, why}), do: "reopen failed: " <> describe(why)
  def describe(:starting), do: "starting"
  def describe(:off), do: "not_started (RISI was off at boot)"
  def describe(:mls_unavailable), do: "mls_unavailable (no attestation key)"
  def describe(:db_error), do: "db_error (Postgres)"
  def describe(:restart_limit), do: "restart_limit (the tree kept crashing)"
  def describe({:child_failed, child}) when is_atom(child), do: "start_failed (#{inspect(child)})"
  def describe({:exception, mod}) when is_atom(mod), do: "exception (#{inspect(mod)})"
  def describe(reason) when is_atom(reason), do: Atom.to_string(reason)
  def describe(_reason), do: "error"

  defp not_startable_text(:risi_off), do: "risi_off"
  defp not_startable_text(:nif_not_loaded), do: "nif_not_loaded (scripts/build-mls-nif)"
  defp not_startable_text(:missing_mls_kek), do: "missing_key RISI_MLS_KEK (base64, 32 bytes)"
  defp not_startable_text(:missing_data_key), do: "missing_key RISI_DATA_KEY (base64, 32 bytes)"

  @doc false
  # An exception as a reason without its message (a message could quote a value).
  def exception_reason(%DBConnection.ConnectionError{}), do: :db_error
  def exception_reason(%Postgrex.Error{}), do: :db_error
  def exception_reason(e), do: {:exception, e.__struct__}

  @doc """
  The read-only Risi preflight (`RisiMe.Release.risi_preflight/1`): env presence and format, the
  NIF, the key checks against the database, and an `open` of Risi's sealed rows in memory
  (nothing is persisted, seeded or registered). `{:ok, notes}` or `{:error, reason}`
  (`describe/1`). Needs the Repo and `RisiMe.MLS.Attestation` running.
  """
  def preflight do
    case problems() -- [:risi_off] do
      [] ->
        {:ok, kek} = kek()
        {:ok, dk} = data_key()

        notes =
          if Risi.enabled?(), do: [], else: ["RISI is off: the tree starts only with RISI=on"]

        notes =
          if RisiMe.Agent.KeyCheck.compare(RisiMe.Agent.KeyCheck.data_name(), dk) == :mismatch,
            do:
              notes ++
                [
                  "RISI_DATA_KEY differs from the key of the risi_buffer rows: they become " <>
                    "unreadable and are dropped (24-h buffer; not fatal)"
                ],
            else: notes

        case RisiMe.Agent.Mls.verify(kek) do
          {:ok, info} -> {:ok, notes ++ [info]}
          {:error, reason} -> {:error, reason}
        end

      problems ->
        {:error, {:not_startable, problems}}
    end
  rescue
    e -> {:error, exception_reason(e)}
  catch
    :exit, _ -> {:error, :exit}
  end

  @doc "True while the whole tree is up (its last child, the inbox, is running)."
  def running?, do: GenServer.whereis(RisiMe.Agent.Inbox) != nil

  @doc """
  The server-side defence (§24.5): true only for a `grp:` conversation whose `groups.tab` is
  `'official'` (a direct query, never a cache or a client claim).
  """
  def official?("grp:" <> _ = conv),
    do:
      Repo.exists?(
        from g in RisiMe.Groups.Group, where: g.id == ^conv and g.tab == "official", select: 1
      )

  def official?(_conv), do: false

  @doc """
  True when Risi may act in `conv` now: Official, the chat on, Risi an active member (§24.5).
  """
  def may_act?(conv),
    do: official?(conv) and RisiMe.Groups.Tabs.agent_conversation?(Risi.user_id(), conv)

  @doc "The MLS group id of a `grp:` conversation at a generation (§12.0)."
  def group_id(conv, generation), do: "#{conv}##{generation}"

  ## Official off (§24.4)

  @doc """
  Official is being turned off by `actor`: while Risi is still an active member and the chat is
  still on, Risi posts its farewell line (an ordinary Risi `text`, §24.4). Called by
  `RisiMe.Chats.toggle/4` **before** the off transaction marks Risi `pending_remove`. Best effort:
  `:ok` whatever happens (the toggle never waits more than `timeout`).
  """
  def official_off(conv, actor, timeout \\ 15_000) do
    if running?() and may_act?(conv) do
      name = actor_name(actor)

      body =
        "Official was turned off by #{name}. I've deleted what I learned in this chat."

      case RisiMe.Agent.Send.text(conv, body, nil, timeout) do
        {:ok, _} -> :ok
        {:error, reason} -> Logger.warning("Risi farewell not sent: #{inspect(reason)}")
      end
    end

    :ok
  catch
    :exit, _ ->
      Logger.warning("Risi farewell not sent: timeout")
      :ok
  end

  @doc """
  After Official was turned off (§24.4): delete what Risi holds for the conversation now: its
  buffer rows, commitments, facts (with their embeddings) and pending jobs
  (`RisiMe.Agent.Secretary.forget/1`), and run it once more 10 minutes later in case a job that
  was already running wrote something (§24.4: within 1 hour). Its MLS state goes when the removal
  commit lands.
  """
  def forget(conv) do
    :ok = RisiMe.Agent.Secretary.forget(conv)

    %{"kind" => "forget", "conv" => conv}
    |> RisiMe.Workers.Risi.new(queue: :risi_timers, schedule_in: 600)
    |> Oban.insert()

    :ok
  rescue
    e -> Logger.warning("Risi forget failed: #{Exception.message(e)}")
  end

  defp actor_name(actor) do
    case Repo.get(RisiMe.Accounts.User, actor) do
      %{display_name: n} when is_binary(n) and n != "" -> n
      _ -> "a member"
    end
  end
end
