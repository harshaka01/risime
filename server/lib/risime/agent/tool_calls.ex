defmodule RisiMe.Agent.ToolCalls do
  @moduledoc """
  Client tools on the asker's phone (contract v1.25 §25.3, v1.26 §26.6; server S13/S16).

  **The call.** `call/2` (or `start/1` for an undo, whose result is handled here) writes:

    * a `risi_tool_calls` row: id, user, device, tool, `write_id`, `undo_entry_id`, `expires_at`
      (the tool's 15-s deadline). **No args.**
    * the per-device event `risi_tool_call` in the asker's partition with `to_devices:
      [device_id]` and a **2-minute TTL** (`RisiMe.Messaging.publish_device/4`): the args live
      only there, and the event is deleted as soon as the result arrives (or the waiter gives
      up). The inbox channel delivers it only to that device's sockets; without a live inbox
      channel that device's token alone gets the content-free wake.

  **The result** (`result/5`, `POST /api/v1/risi/tool_calls/{id}/result`): 404 for anyone but
  that user **and** that device; 409 `tool_call_expired` after the deadline or a second time;
  422 `bad_request` unless `status` is known and `result` is exactly the tool's schema; 409
  `write_not_confirmed` for a write whose `write_id` has no confirmed write; else 204. The
  result goes only to the waiting process (`Phoenix.PubSub`, topic `risi_tool:<id>`) or, for an
  undo, to `RisiMe.Agent.Skills`; it is never stored and never logged.
  """
  use Ecto.Schema

  import Ecto.Query

  require Logger

  alias RisiMe.{Messaging, Repo, TimeUUID}

  @primary_key {:tool_call_id, :binary_id, autogenerate: false}
  schema "risi_tool_calls" do
    field :user_id, :binary_id
    field :device_id, :binary_id
    field :tool, :string
    field :write_id, :binary_id
    field :undo_entry_id, :binary_id
    field :turn_id, :binary_id
    field :event_id, :string
    field :state, :string, default: "waiting"
    field :expires_at, :utc_datetime_usec
    field :inserted_at, :utc_datetime_usec
  end

  @deadline_ms 15_000
  @ttl_s 120
  @max_body 16_384

  @tools ~w(calendar_check calendar_add set_alarm schedule_message cancel_scheduled
            calendar_remove)
  @v126 ~w(set_alarm schedule_message cancel_scheduled calendar_remove)
  @writes ~w(calendar_add set_alarm schedule_message cancel_scheduled)

  @doc "The client tools this server knows (§25.3, §26.6)."
  def tools, do: @tools

  @doc "The v1.26 client tools: sent only to `risi_skills` devices (§26.6)."
  def v126?(tool), do: tool in @v126

  @doc "The client tool's deadline in ms (15 s, §25.3)."
  def deadline_ms,
    do: Application.get_env(:risime, :risi_tool_deadline_ms, @deadline_ms)

  def max_body, do: @max_body

  @doc """
  Sends a tool call and waits for its result (at most `wait_ms`, never past the 15-s deadline).
  `spec`: `user`, `device`, `tool`, `args`, and optionally `turn_id`, `request_id`, `conv`,
  `write_id`. Returns `{:ok, status, result}` or `{:error, :timeout}`.
  """
  def call(spec, wait_ms \\ nil) do
    wait = min(wait_ms || deadline_ms(), deadline_ms())
    id = Ecto.UUID.generate()
    topic = "risi_tool:" <> id
    :ok = Phoenix.PubSub.subscribe(RisiMe.PubSub, topic)

    try do
      {:ok, call} = start(Map.put(spec, :tool_call_id, id))

      receive do
        {:risi_tool_result, ^id, status, result} -> {:ok, status, result}
      after
        max(wait, 0) ->
          give_up(call)
          {:error, :timeout}
      end
    after
      Phoenix.PubSub.unsubscribe(RisiMe.PubSub, topic)
    end
  end

  @doc """
  Writes the row and the per-device event (see the module doc); `{:ok, row}`. An undo call
  (`undo_entry_id`) carries `null` turn, request and conversation.
  """
  def start(spec) do
    id = Map.get(spec, :tool_call_id) || Ecto.UUID.generate()
    now = DateTime.utc_now()
    expires = DateTime.add(now, deadline_ms(), :millisecond)
    event_id = TimeUUID.generate()

    row =
      Repo.insert!(%__MODULE__{
        tool_call_id: id,
        user_id: spec.user,
        device_id: spec.device,
        tool: spec.tool,
        write_id: Map.get(spec, :write_id),
        undo_entry_id: Map.get(spec, :undo_entry_id),
        turn_id: Map.get(spec, :turn_id),
        event_id: event_id,
        state: "waiting",
        expires_at: expires,
        inserted_at: now
      })

    data = %{
      "tool_call_id" => id,
      "turn_id" => Map.get(spec, :turn_id),
      "request_id" => Map.get(spec, :request_id),
      "conversation_id" => Map.get(spec, :conv),
      "device_id" => spec.device,
      "tool" => spec.tool,
      "args" => spec.args,
      "expires_at" => Messaging.iso(expires),
      "to_devices" => [spec.device],
      "server_ts" => Messaging.iso(now)
    }

    # v1.26 §26.6: `undo_entry_id` on the v1.26 tools (and every undo); absent for v1.25 ones.
    data =
      if Map.has_key?(spec, :undo_entry_id) or v126?(spec.tool),
        do: Map.put(data, "undo_entry_id", Map.get(spec, :undo_entry_id)),
        else: data

    event = %{event_id: event_id, kind: "risi_tool_call", data: data}
    :ok = Messaging.publish_device(spec.user, spec.device, event, @ttl_s)
    {:ok, row}
  end

  # The waiter left: the call is over (a late result gets 409) and its args are gone.
  defp give_up(%__MODULE__{} = call) do
    Repo.update_all(
      from(c in __MODULE__, where: c.tool_call_id == ^call.tool_call_id and c.state == "waiting"),
      set: [state: "expired"]
    )

    drop_event(call)
  end

  @doc "Marks a call over and deletes its event (an undo that timed out)."
  def expire(tool_call_id) do
    case Repo.get(__MODULE__, tool_call_id) do
      %__MODULE__{} = c -> give_up(c)
      nil -> :ok
    end
  end

  defp drop_event(call) do
    Messaging.delete_events(call.user_id, [call.event_id])
  rescue
    e -> Logger.warning("risi tool call event not deleted: #{inspect(e.__struct__)}")
  end

  ## The result (§25.3)

  @statuses ~w(ok no_permission declined error)

  @doc """
  `POST /api/v1/risi/tool_calls/{id}/result` from `user` on `device` (`size` = the body's
  bytes): `:ok` or `{:error, :not_found | :tool_call_expired | :too_large | :bad_request |
  :write_not_confirmed}`.
  """
  def result(user, device, id, params, size) do
    with :ok <- if(size > @max_body, do: {:error, :too_large}, else: :ok),
         {:ok, id} <- cast(id),
         %__MODULE__{} = c <- Repo.get(__MODULE__, id) || {:error, :not_found},
         true <- (c.user_id == user and c.device_id == device) || {:error, :not_found},
         :ok <- open?(c),
         {:ok, status, result} <- parse(c.tool, params),
         :ok <- confirmed?(c, status),
         {1, _} <-
           Repo.update_all(
             from(x in __MODULE__, where: x.tool_call_id == ^id and x.state == "waiting"),
             set: [state: "answered"]
           ) do
      drop_event(c)

      # §26.4: an undo's result settles its entry; any other goes to the waiting process.
      if c.undo_entry_id do
        RisiMe.Agent.Skills.undo_result(c, status, result)
      else
        Phoenix.PubSub.broadcast(
          RisiMe.PubSub,
          "risi_tool:" <> id,
          {:risi_tool_result, id, status, result}
        )
      end

      :ok
    else
      {0, _} -> {:error, :tool_call_expired}
      {:error, _} = e -> e
      _ -> {:error, :not_found}
    end
  end

  defp cast(id) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> {:ok, id}
      :error -> {:error, :not_found}
    end
  end

  defp open?(c) do
    if c.state == "waiting" and DateTime.compare(DateTime.utc_now(), c.expires_at) == :lt,
      do: :ok,
      else: {:error, :tool_call_expired}
  end

  # §25.3: a write's result needs a confirmed write (§25.4) — or an allowed one (§26.3).
  defp confirmed?(%{tool: tool, write_id: wid}, "ok") when tool in @writes do
    cond do
      tool == "cancel_scheduled" and wid == nil -> :ok
      wid != nil and RisiMe.Agent.Writes.confirmed?(wid) -> :ok
      true -> {:error, :write_not_confirmed}
    end
  end

  defp confirmed?(_call, _status), do: :ok

  @doc false
  def parse(tool, %{"status" => status} = p) when status in @statuses do
    result = Map.get(p, "result", :missing)

    cond do
      Map.keys(p) -- ["status", "result"] != [] -> {:error, :bad_request}
      status == "ok" and valid_ok?(tool, result) -> {:ok, status, result}
      status == "error" and valid_error?(result) -> {:ok, status, result}
      status in ~w(no_permission declined) and result in [nil, :missing] -> {:ok, status, nil}
      true -> {:error, :bad_request}
    end
  end

  def parse(_tool, _params), do: {:error, :bad_request}

  @error_codes ~w(unknown_tool bad_args calendar_unavailable alarm_unavailable
                  too_many_scheduled not_member)

  defp valid_error?(%{"code" => code} = r) when map_size(r) == 1, do: code in @error_codes
  defp valid_error?(_), do: false

  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{1,6})?(Z|[+-]\d\d:\d\d)$/

  defp ts?(v), do: is_binary(v) and v =~ @ts

  @calendar_sources ~w(phone_provider google_api)
  @source_reasons [nil] ++
                    ~w(not_connected no_permission reauth_needed no_play_services network
                       timeout api_error no_calendars)

  # P0 2026-10-09 (v1.29 §29.2 as aligned with root): what the phone read, per source.
  defp valid_ok?("calendar_check", %{"blocks" => _, "sources" => s, "connected_sources" => c} = r)
       when map_size(r) == 3 do
    valid_ok?("calendar_check", Map.take(r, ["blocks"])) and
      is_list(s) and length(s) <= 4 and Enum.all?(s, &valid_source?/1) and
      is_list(c) and length(c) <= 4 and Enum.all?(c, &(&1 in @calendar_sources))
  end

  defp valid_ok?("calendar_check", %{"blocks" => blocks} = r) when map_size(r) == 1 do
    is_list(blocks) and length(blocks) <= 200 and
      Enum.all?(blocks, fn
        %{"start" => s, "end" => e, "busy" => b, "all_day" => a} = blk when map_size(blk) == 4 ->
          ts?(s) and ts?(e) and is_boolean(b) and is_boolean(a)

        _ ->
          false
      end)
  end

  defp valid_ok?("calendar_add", %{"event_id" => id} = r) when map_size(r) == 1,
    do: is_binary(id) and byte_size(id) in 1..256

  # P0 2026-10-09 (proposal 2026-10-09-risi-action-loop): the calendar it was added to.
  defp valid_ok?("calendar_add", %{"event_id" => id, "calendar" => c} = r)
       when map_size(r) == 2,
       do:
         is_binary(id) and byte_size(id) in 1..256 and
           (c == nil or RisiMe.Agent.CalendarChoice.valid?(c))

  defp valid_ok?("set_alarm", %{"alarm_set" => true} = r) when map_size(r) == 1, do: true

  defp valid_ok?("schedule_message", %{"schedule_id" => id} = r) when map_size(r) == 1,
    do: match?({:ok, _}, Ecto.UUID.cast(id || ""))

  defp valid_ok?("cancel_scheduled", %{"cancelled" => c, "reason" => why} = r)
       when map_size(r) == 2,
       do: is_boolean(c) and why in [nil, "already_sent", "unknown"]

  defp valid_ok?("calendar_remove", %{"removed" => rm, "reason" => why} = r)
       when map_size(r) == 2,
       do: is_boolean(rm) and why in [nil, "not_found"]

  defp valid_ok?(_tool, _result), do: false

  defp valid_source?(
         %{"source" => src, "calendars" => cals, "read_ok" => ok, "reason" => why} = s
       )
       when map_size(s) == 4 do
    src in @calendar_sources and is_boolean(ok) and why in @source_reasons and is_list(cals) and
      length(cals) <= 50 and Enum.all?(cals, &valid_source_calendar?/1)
  end

  defp valid_source?(_), do: false

  # Names and account types only: never a title, attendee or event id.
  defp valid_source_calendar?(%{"name" => n, "account_type" => t, "events" => e} = c)
       when map_size(c) == 3,
       do:
         is_binary(n) and String.length(n) <= 100 and is_binary(t) and String.length(t) <= 100 and
           is_integer(e) and e >= 0

  defp valid_source_calendar?(_), do: false

  @doc "Deletes call rows older than a day (they hold no args; the 409 check needs them a while)."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -86_400, :second)
    Repo.delete_all(from c in __MODULE__, where: c.inserted_at < ^cutoff)
    :ok
  end
end
