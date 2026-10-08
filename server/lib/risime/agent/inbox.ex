defmodule RisiMe.Agent.Inbox do
  @moduledoc """
  Risi's inbox, consumed inside the server exactly like a client's (§2): it subscribes to the
  user's live topic (`RisiMe.Messaging.subscribe/1`) and drains the stored events from its
  cursor (`RisiMe.Messaging.fetch_events/3`), oldest first. Live events are only a wake-up; the
  store is the source, so nothing is missed across restarts.

  **The cursor** (`risi_agent_cursor`) advances past an event only after that event was handled,
  i.e. after its MLS journal was persisted by `RisiMe.Agent.Mls`. A crash in between replays the
  event; every handler is idempotent (a processed message no longer decrypts, a Welcome for a
  joined group and an old commit are skipped, buffer writes are keyed by message id).

  **What reaches a conversation (§24.5):** only `mls_welcome`, `mls_commit`, `message` and
  `group_event` events of a `grp:` conversation that the server query says is Official
  (`groups.tab = 'official'`), whose chat is on and where Risi is an active member
  (`RisiMe.Groups.Tabs.agent_conversation?/2`), never a client claim. Anything else is skipped
  without a single MLS call, except:

    * an event of an Official group Risi is no longer a leaf of (the removal commit landed) purges
      that group (`RisiMe.Agent.Conversation.removed/1`);
    * an event that isn't visible yet but is younger than `:risi_hold_ms` (default 10 s) holds
      the drain and is retried: its Postgres transaction (a Welcome's add) may not have committed.
  """
  use GenServer

  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Conversation, ConversationSup}
  alias RisiMe.{Groups, Messaging, Repo, Risi, TimeUUID}

  @page 100
  @kinds ~w(mls_welcome mls_commit message group_event)
  @max_attempts 3

  def start_link(_opts \\ []), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "Drains now and returns the cursor afterwards (tests, diagnostics)."
  def drain_now, do: GenServer.call(__MODULE__, :drain_now, 120_000)

  @doc "The persisted cursor (nil before the first event)."
  def cursor do
    Repo.one(
      from c in "risi_agent_cursor",
        where: c.device_id == type(^Risi.device_id(), :binary_id),
        select: c.cursor
    )
  end

  ## Server

  @impl true
  def init(:ok) do
    :ok = Messaging.subscribe(Risi.user_id())
    send(self(), :drain)
    {:ok, %{cursor: cursor(), scheduled: true, attempts: %{}}}
  end

  @impl true
  def handle_info({:inbox_event, _event}, %{scheduled: true} = state), do: {:noreply, state}

  def handle_info({:inbox_event, _event}, state) do
    send(self(), :drain)
    {:noreply, %{state | scheduled: true}}
  end

  def handle_info(:drain, state), do: {:noreply, drain(%{state | scheduled: false})}

  # Signals (typing, presence) and anything else: never stored, never handled.
  def handle_info(_msg, state), do: {:noreply, state}

  @impl true
  def handle_call(:drain_now, _from, state) do
    state = drain(state)
    {:reply, state.cursor, state}
  end

  @impl true
  def format_status(status) do
    Map.new(status, fn
      {:message, _} -> {:message, :redacted}
      other -> other
    end)
  end

  ## Drain

  defp drain(state) do
    case Messaging.fetch_events(Risi.user_id(), state.cursor, @page) do
      {:ok, events, more?} ->
        case run(events, state) do
          {:done, state} when more? -> drain(state)
          {:done, state} -> state
          {:hold, state} -> retry(state)
        end

      {:error, _} ->
        retry(state)
    end
  rescue
    e ->
      Logger.warning("Risi inbox drain failed: #{Exception.message(e)}")
      retry(state)
  end

  defp retry(%{scheduled: true} = state), do: state

  defp retry(state) do
    Process.send_after(self(), :drain, Application.get_env(:risime, :risi_retry_ms, 1_000))
    %{state | scheduled: true}
  end

  defp run([], state), do: {:done, state}

  defp run([event | rest], state) do
    case handle(event, state) do
      :ok -> run(rest, advance(state, event.event_id))
      :hold -> {:hold, state}
      {:failed, why} -> failed(event, why, rest, state)
    end
  end

  # A handler that crashed is retried a few times, then skipped (logged, never the content).
  defp failed(event, why, rest, state) do
    n = Map.get(state.attempts, event.event_id, 0) + 1

    if n >= @max_attempts do
      Logger.error(
        "Risi skipped inbox event #{event.event_id} (#{event.kind}) after #{n} failures: #{why}"
      )

      run(rest, advance(%{state | attempts: %{}}, event.event_id))
    else
      {:hold, %{state | attempts: Map.put(state.attempts, event.event_id, n)}}
    end
  end

  defp advance(state, event_id) do
    now = DateTime.utc_now()

    Repo.insert_all(
      "risi_agent_cursor",
      [%{device_id: Ecto.UUID.dump!(Risi.device_id()), cursor: event_id, updated_at: now}],
      on_conflict: {:replace, [:cursor, :updated_at]},
      conflict_target: [:device_id]
    )

    %{state | cursor: event_id}
  end

  defp handle(%{kind: kind, data: data} = event, _state) when kind in @kinds and is_map(data) do
    conv = data["conversation_id"] || data["group_id"]

    case decide(conv, event) do
      :handle -> call(conv, &Conversation.handle_event(&1, event))
      :removed -> call(conv, &Conversation.removed/1)
      other -> other
    end
  end

  defp handle(_event, _state), do: :ok

  defp decide("grp:" <> _ = conv, event) do
    risi = Risi.user_id()

    cond do
      not RisiMe.Agent.official?(conv) ->
        # §24.5: never reachable through the server's own filters; refused all the same.
        Logger.warning("Risi inbox refused an event of a non-Official conversation #{conv}")
        :ok

      RisiMe.Groups.Tabs.agent_conversation?(risi, conv) ->
        :handle

      young?(event.event_id) ->
        :hold

      not MapSet.member?(Groups.in_group(conv), {risi, Risi.device_id()}) ->
        :removed

      true ->
        :ok
    end
  end

  defp decide(_conv, _event), do: :ok

  defp young?(event_id) do
    System.system_time(:millisecond) - TimeUUID.unix_ms(event_id) <
      Application.get_env(:risime, :risi_hold_ms, 10_000)
  end

  defp call(conv, fun) do
    with {:ok, pid} <- ConversationSup.ensure(conv) do
      case fun.(pid) do
        {:error, :private_tab} -> :ok
        _ -> :ok
      end
    else
      {:error, :private_tab} -> :ok
      {:error, reason} -> {:failed, inspect(reason)}
    end
  catch
    :exit, {reason, _} -> {:failed, exit_reason(reason)}
  end

  defp exit_reason({e, _stack}) when is_exception(e), do: inspect(e.__struct__)
  defp exit_reason(reason) when is_atom(reason), do: Atom.to_string(reason)
  defp exit_reason(_), do: "exit"
end
