defmodule RisiMe.Agent.RisiItems do
  @moduledoc """
  Risi's items (contract v1.35 §34.4, P0 2026-10-11): a per-user ledger of everything Risi set
  up for the user.

  * **Stored kinds** (`risi_items`, title and calendar sealed with `RISI_DATA_KEY`, AAD
    `risi_items:<id>:title` / `…:calendar`; `ref` holds ids only): `phone_event_added` (a
    verified device `calendar_add`), `risi_calendar_event` (a confirmed `risi_calendar_add`),
    `reminder` (a confirmed `set_reminder`), `scheduled_message` (an `ok` `schedule_message`; the
    text is never stored, `title` null).
  * **Live kinds:** `promise` (the user owns an open ledger item) and `follow_up` (the user is a
    counterpart), from the §28.7 query at read time, `start` = `due`.
  * **Freshness:** a `risi_calendar_event` or `reminder` row takes its state, time and title
    from its source; one whose source is gone, cancelled or fired is left out.
  * **Backfill** (cheap, on read, idempotent): the user's pending reminders, their Risi
    Calendar events created by Risi, and the scheduled messages in their activity log.
  * Writes never fail the caller's path.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, Clock, Reminders, Seal}
  alias RisiMe.Repo

  defmodule Row do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:id, :binary_id, autogenerate: false}
    schema "risi_items" do
      field :user_id, :binary_id
      field :kind, :string
      field :ref, :map
      field :ref_key, :string
      field :title_sealed, :binary
      field :calendar_sealed, :binary
      field :conversation_id, :string
      field :start_at, :utc_datetime_usec
      field :end_at, :utc_datetime_usec
      field :all_day, :boolean, default: false
      field :state, :string, default: "active"
      field :created_at, :utc_datetime_usec
      field :updated_at, :utc_datetime_usec
    end
  end

  @stored ~w(phone_event_added risi_calendar_event reminder scheduled_message)
  @live ~w(promise follow_up)
  @kinds @stored ++ @live
  @max 500
  @keep_s 30 * 86_400

  def kinds, do: @kinds

  ## Writes (from the same success paths as the §26.4 activity entries)

  @doc "A verified phone `calendar_add` (§25.3): `w` the write, `args` its card args."
  def record_phone_event(w, args, device, result) do
    wire = args["wire"] || %{}
    cal = if is_map(result["calendar"]), do: Map.take(result["calendar"], ~w(name account))

    record(w.user_id, "phone_event_added", result["event_id"], %{
      ref: %{
        "event_id" => result["event_id"],
        "write_id" => w.write_id,
        "device_id" => device,
        "calendar" => cal
      },
      title: args["title"] || wire["title"],
      calendar: cal,
      start_at: ts(wire["start"]),
      end_at: ts(wire["end"]),
      all_day: wire["all_day"] == true,
      conversation_id: nil
    })
  end

  @doc "A confirmed `risi_calendar_add` (§29.8)."
  def record_risi_event(user, %{} = e) do
    record(user, "risi_calendar_event", e.event_id, %{
      ref: %{"event_id" => e.event_id},
      title: nil,
      calendar: nil,
      start_at: e.start_at,
      end_at: e.end_at,
      all_day: e.all_day == true,
      conversation_id: nil
    })
  end

  @doc "A confirmed `set_reminder` (§25.4)."
  def record_reminder(user, reminder_id, due, conv) do
    record(user, "reminder", reminder_id, %{
      ref: %{"reminder_id" => reminder_id},
      title: nil,
      calendar: nil,
      start_at: due,
      end_at: nil,
      all_day: false,
      conversation_id: conv
    })
  end

  @doc "An `ok` `schedule_message` (§26.6): ids, the time and the repeat; never the text."
  def record_scheduled(user, schedule_id, device, wire) do
    record(user, "scheduled_message", schedule_id, %{
      ref: %{
        "schedule_id" => schedule_id,
        "device_id" => device,
        "target_conversation_id" => wire["conversation_id"],
        "repeat" => wire["repeat"]
      },
      title: nil,
      calendar: nil,
      start_at: ts(wire["at"]),
      end_at: nil,
      all_day: false,
      conversation_id: wire["conversation_id"]
    })
  end

  defp record(user, kind, key, attrs) when is_binary(key) and key != "" do
    id = Ecto.UUID.generate()
    now = DateTime.utc_now()

    with {:ok, title} <- Seal.seal_text("risi_items", id, "title", attrs.title),
         {:ok, cal} <- seal_cal(id, attrs.calendar) do
      Repo.insert!(
        %Row{
          id: id,
          user_id: user,
          kind: kind,
          ref: attrs.ref,
          ref_key: key,
          title_sealed: title,
          calendar_sealed: cal,
          conversation_id: attrs.conversation_id,
          start_at: attrs.start_at && Clock.usec(attrs.start_at),
          end_at: attrs.end_at && Clock.usec(attrs.end_at),
          all_day: attrs.all_day,
          created_at: now,
          updated_at: now
        },
        on_conflict: :nothing,
        conflict_target: [:user_id, :kind, :ref_key]
      )

      :ok
    end
  rescue
    e ->
      Logger.warning("risi_items write failed: #{inspect(e.__struct__)}")
      :error
  end

  defp record(_user, _kind, _key, _attrs), do: :error

  defp seal_cal(_id, nil), do: {:ok, nil}

  defp seal_cal(id, cal) do
    with {:ok, key} <- Seal.data(),
         do: {:ok, Seal.seal(key, Seal.aad("risi_items", id, "calendar"), cal)}
  end

  defp ts(nil), do: nil

  defp ts(s) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, dt, _} -> dt
      _ -> nil
    end
  end

  defp ts(%DateTime{} = dt), do: dt

  ## Backfill (cheap, idempotent)

  @doc false
  def backfill(user) do
    for r <-
          Repo.all(from r in Reminders, where: r.owner_id == ^user and r.state == "pending"),
        do: record_reminder(user, r.reminder_id, r.due_at, r.conversation_id)

    for e <-
          Repo.all(
            from e in Calendar.Event,
              where: e.owner == ^user and e.created_by == "risi" and e.state == "active"
          ),
        do: record_risi_event(user, e)

    if match?({:ok, _}, Seal.memory()) do
      for s <- RisiMe.Agent.Skills.scheduled(user, nil) do
        record(user, "scheduled_message", s.schedule_id, %{
          ref: %{
            "schedule_id" => s.schedule_id,
            "device_id" => nil,
            "target_conversation_id" => s.target_conversation_id,
            "repeat" => nil
          },
          title: nil,
          calendar: nil,
          start_at: s.at,
          end_at: nil,
          all_day: false,
          conversation_id: s.target_conversation_id
        })
      end
    end

    :ok
  rescue
    e ->
      Logger.warning("risi_items backfill failed: #{inspect(e.__struct__)}")
      :ok
  end

  ## Reads

  @doc """
  The user's items as `RisiItem` maps (§34.4), sorted by `start` (no start last), at most 500.
  `opts`: `from`, `to` (DateTime), `kinds` (list), `device_id` (for `actions`), `undated`
  (include live items without a due, default true). `{:ok, items}` or
  `{:error, :agent_unavailable}`.
  """
  def list(user, opts \\ []) do
    from = Keyword.get(opts, :from, Clock.now())
    to = Keyword.get(opts, :to, DateTime.add(from, 92 * 86_400, :second))
    kinds = Keyword.get(opts, :kinds, @kinds)
    device = Keyword.get(opts, :device_id)
    undated? = Keyword.get(opts, :undated, true)

    with {:ok, _key} <- Seal.data() do
      backfill(user)

      stored =
        Repo.all(
          from r in Row,
            where:
              r.user_id == ^user and r.state == "active" and r.kind in ^kinds and
                ((r.start_at >= ^from and r.start_at < ^to) or
                   (not is_nil(r.end_at) and r.start_at < ^to and r.end_at > ^from) or
                   (r.kind == "scheduled_message" and
                      fragment("?->>'repeat' = 'daily'", r.ref) and r.start_at < ^to)),
            order_by: [asc: r.start_at, asc: r.id]
        )
        |> Enum.flat_map(&fresh(&1, device))

      live = live(user, kinds, from, to, undated?)

      items =
        (stored ++ live)
        |> Enum.sort_by(fn i -> {i["start"] == nil, i["start"] || "", i["id"]} end)
        |> Enum.take(@max)

      {:ok, items}
    else
      _ -> {:error, :agent_unavailable}
    end
  end

  # A stored row as a RisiItem, refreshed from its source ([] when gone).
  defp fresh(%Row{kind: "risi_calendar_event"} = r, device) do
    with {:ok, id} <- Ecto.UUID.cast(r.ref["event_id"]),
         {%Calendar.Event{state: "active"} = e, parts} <- Calendar.load(id),
         true <- Enum.any?(parts, &(&1.user_id == r.user_id and not &1.removed)),
         {:ok, title} <- Calendar.open_title(e) do
      [item(r, title, nil, e.start_at, e.end_at, e.all_day, device)]
    else
      _ -> []
    end
  end

  defp fresh(%Row{kind: "reminder"} = r, device) do
    with %Reminders{state: "pending"} = rem <- Reminders.get(r.ref["reminder_id"]),
         {:ok, text} <- Reminders.open_text(rem) do
      [item(r, text, nil, rem.due_at, nil, false, device)]
    else
      _ -> []
    end
  end

  defp fresh(%Row{} = r, device) do
    with {:ok, title} <- Seal.open_text("risi_items", r.id, "title", r.title_sealed),
         {:ok, cal} <- open_cal(r) do
      [item(r, title, cal, r.start_at, r.end_at, r.all_day, device)]
    else
      _ -> []
    end
  end

  defp open_cal(%Row{calendar_sealed: nil}), do: {:ok, nil}

  defp open_cal(%Row{} = r) do
    with {:ok, key} <- Seal.data(),
         {:ok, cal} <- Seal.open(key, Seal.aad("risi_items", r.id, "calendar"), r.calendar_sealed) do
      {:ok, cal}
    end
  end

  defp item(r, title, cal, start, stop, all_day, device) do
    %{
      "id" => r.id,
      "kind" => r.kind,
      "title" => title,
      "start" => Clock.ts(start),
      "end" => Clock.ts(stop),
      "all_day" => all_day == true,
      "state" => "active",
      "calendar" => cal,
      "conversation_id" => r.conversation_id,
      "ref" => r.ref,
      "created_at" => Clock.ts(r.created_at),
      "actions" => actions(r.kind, r.ref, device)
    }
  end

  defp actions(kind, ref, device) when kind in ~w(phone_event_added scheduled_message) do
    if is_binary(device) and ref["device_id"] == device,
      do: ~w(open edit delete),
      else: []
  end

  defp actions("promise", _ref, _device), do: ~w(open edit)
  defp actions("follow_up", _ref, _device), do: ~w(open)
  defp actions(_kind, _ref, _device), do: ~w(open edit delete)

  defp live(user, kinds, from, to, undated?) do
    if Enum.any?(@live, &(&1 in kinds)) do
      case RisiMe.Agent.Rest.open_commitments(user) do
        {:ok, cs} ->
          for c <- cs,
              kind = if(c.owner_id == user, do: "promise", else: "follow_up"),
              kind in kinds,
              in_range?(c.due, from, to, undated?) do
            %{
              "id" => c.id,
              "kind" => kind,
              "title" => c.text,
              "start" => Clock.ts(c.due),
              "end" => nil,
              "all_day" => c.all_day == true,
              "state" => "active",
              "calendar" => nil,
              "conversation_id" => c.conversation_id,
              "ref" => %{"item_id" => c.id},
              "created_at" => Clock.ts(c.inserted_at),
              "actions" => actions(kind, nil, nil)
            }
          end

        _ ->
          []
      end
    else
      []
    end
  end

  defp in_range?(nil, _from, _to, undated?), do: undated?

  defp in_range?(due, from, to, _undated?),
    do: DateTime.compare(due, from) != :lt and DateTime.compare(due, to) == :lt

  ## REST (§34.4)

  @doc "`GET /risi/items` params → `{:ok, %{\"items\" => …}}` or `{:error, reason}`."
  def rest_list(user, device, params) do
    with {:ok, from} <- param_ts(params["from"], Clock.now()),
         {:ok, to} <- param_ts(params["to"], DateTime.add(from, 92 * 86_400, :second)),
         true <- DateTime.compare(to, from) == :gt || {:error, :bad_request},
         true <- DateTime.diff(to, from) <= 366 * 86_400 || {:error, :bad_request},
         {:ok, kinds} <- param_kinds(params["kinds"]),
         {:ok, items} <- list(user, from: from, to: to, kinds: kinds, device_id: device) do
      {:ok, %{"items" => items}}
    end
  end

  defp param_ts(nil, default), do: {:ok, default}

  defp param_ts(s, _default) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, dt, _} -> {:ok, dt}
      _ -> {:error, :bad_request}
    end
  end

  defp param_ts(_, _), do: {:error, :bad_request}

  defp param_kinds(nil), do: {:ok, @kinds}

  defp param_kinds(s) when is_binary(s) do
    ks = s |> String.split(",", trim: true) |> Enum.map(&String.trim/1)
    if ks != [] and Enum.all?(ks, &(&1 in @kinds)), do: {:ok, ks}, else: {:error, :bad_request}
  end

  defp param_kinds(_), do: {:error, :bad_request}

  defp own_row(user, id) do
    with {:ok, id} <- Ecto.UUID.cast(id),
         %Row{user_id: ^user, state: "active"} = r <- Repo.get(Row, id) do
      {:ok, r}
    else
      _ -> live_item(user, id)
    end
  end

  defp live_item(user, id) do
    case RisiMe.Agent.Rest.open_commitments(user) do
      {:ok, cs} ->
        if Enum.any?(cs, &(&1.id == id)), do: {:live, id}, else: {:error, :not_found}

      _ ->
        {:error, :not_found}
    end
  end

  @doc "`PATCH /risi/items/{id}` (reminders only)."
  def rest_patch(user, device, id, body) do
    case own_row(user, id) do
      {:ok, %Row{kind: "reminder"} = r} ->
        with {:ok, at} <- patch_at(body["at"]),
             {:ok, text} <- patch_text(body["text"]),
             true <- Map.keys(body) -- ~w(at text) == [] || {:error, :bad_request},
             :ok <- Reminders.patch(user, r.ref["reminder_id"], at, text) do
          if at,
            do: r |> Ecto.Changeset.change(start_at: Clock.usec(at)) |> Repo.update!()

          case fresh(Repo.get(Row, r.id), device) do
            [item] -> {:ok, %{"item" => item}}
            [] -> {:error, :not_found}
          end
        end

      {:ok, _} ->
        {:error, :bad_request}

      {:live, _} ->
        {:error, :bad_request}

      error ->
        error
    end
  end

  defp patch_at(nil), do: {:ok, nil}

  defp patch_at(s) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, dt, _} ->
        if DateTime.compare(dt, Clock.now()) == :gt, do: {:ok, dt}, else: {:error, :bad_request}

      _ ->
        {:error, :bad_request}
    end
  end

  defp patch_at(_), do: {:error, :bad_request}

  defp patch_text(nil), do: {:ok, nil}

  defp patch_text(t) when is_binary(t) do
    n = String.length(String.trim(t))
    if n in 1..500, do: {:ok, String.trim(t)}, else: {:error, :bad_request}
  end

  defp patch_text(_), do: {:error, :bad_request}

  @doc "`DELETE /risi/items/{id}`, routed by kind."
  def rest_delete(user, device, id) do
    case own_row(user, id) do
      {:ok, %Row{kind: "risi_calendar_event"} = r} ->
        _ = Calendar.delete(user, r.ref["event_id"])
        Repo.delete(r)
        :ok

      {:ok, %Row{kind: "reminder"} = r} ->
        _ = Reminders.undo(user, %{"reminder_id" => r.ref["reminder_id"]})

        if match?({:ok, _}, Seal.memory()) do
          RisiMe.Agent.Skills.log!(
            user,
            "reminders",
            "reminder_cancelled",
            "Cancelled a reminder",
            via: "settings",
            conversation_id: r.conversation_id
          )
        end

        Repo.delete(r)
        :ok

      {:ok, %Row{} = r} ->
        if is_binary(device) and r.ref["device_id"] == device do
          Repo.delete(r)
          :ok
        else
          {:error, :invalid_device}
        end

      {:live, _} ->
        {:error, :bad_request}

      error ->
        error
    end
  end

  ## Retention, deletion

  @doc "Drops rows past their kind's retention (§34.4)."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@keep_s, :second)

    Repo.delete_all(
      from r in Row,
        where:
          r.kind == "phone_event_added" and
            coalesce(r.end_at, r.start_at) < ^cutoff
    )

    Repo.delete_all(
      from r in Row,
        where:
          r.kind == "scheduled_message" and r.start_at < ^cutoff and
            fragment("coalesce(?->>'repeat', '') <> 'daily'", r.ref)
    )

    # Reminders and Risi Calendar events: their source decides (gone rows are left out on read).
    Repo.delete_all(
      from r in Row,
        where: r.kind in ["reminder", "risi_calendar_event"] and r.start_at < ^cutoff
    )

    :ok
  end

  @doc "Deletes all of a user's rows (account deletion)."
  def delete_user(user) do
    Repo.delete_all(from r in Row, where: r.user_id == ^user)
    :ok
  end

  ## The server tool `risi_items` (§34.4)

  @str %{"type" => "string", "maxLength" => 100}

  def tool do
    %{
      name: "risi_items",
      description:
        "list everything you (Risi) set up for the asker: events added to their phone or Risi " <>
          "Calendar, reminders, scheduled messages, promises and follow-ups (from, to: an ISO " <>
          "time or a local phrase; kinds: optional)",
      where: :server,
      personal: true,
      write: false,
      finds: true,
      args: %{
        "type" => "object",
        "properties" => %{
          "from" => @str,
          "to" => @str,
          "kinds" => %{"type" => "array", "items" => %{"enum" => @kinds}, "maxItems" => 6}
        },
        "required" => [],
        "additionalProperties" => false
      },
      run: &run_tool/2
    }
  end

  defp run_tool(args, ctx) do
    from = tool_time(args["from"], ctx) || ctx.now
    to = tool_time(args["to"], ctx) || DateTime.add(from, 92 * 86_400, :second)
    kinds = Enum.filter(List.wrap(args["kinds"]), &(&1 in @kinds))
    kinds = if kinds == [], do: @kinds, else: kinds

    case list(ctx.asker, from: from, to: to, kinds: kinds, device_id: ctx[:device_id]) do
      {:ok, items} ->
        titles? = RisiMe.Agent.LLM.own_only?()

        model =
          for {i, n} <- Enum.with_index(items, 1) do
            i
            |> Map.take(~w(kind start end all_day))
            |> Map.put("ref", "i#{n}")
            |> then(&if(titles?, do: Map.put(&1, "title", i["title"]), else: &1))
          end

        {:ok, %{"from" => Clock.ts(from), "to" => Clock.ts(to), "items" => model},
         %{risi_items: %{items: items, from: from, to: to}, personal: true}}

      _ ->
        {:error, "failed", "unavailable"}
    end
  end

  defp tool_time(nil, _ctx), do: nil

  defp tool_time(s, ctx) do
    case RisiMe.Agent.TimePhrase.resolve(s, ctx.now, ctx.tz) do
      {:ok, t, _} -> t
      _ -> nil
    end
  end

  @doc "The server-built answer of a `risi_items` step: `{text, sources}` (§34.4)."
  def answer(%{items: items, from: from, to: to}, user, tz) do
    if items == [] do
      {"I haven't set anything up for you from #{day(from, tz)} to #{day(to, tz)}.", []}
    else
      lines = Enum.map_join(items, "\n", &("- " <> line(Map.put(&1, "_user", user), tz)))

      {"Here's what I've set up for you:\n" <> lines,
       for(i <- items, do: %{"type" => "risi_item", "item_id" => i["id"], "kind" => i["kind"]})}
    end
  end

  defp day(dt, tz), do: Elixir.Calendar.strftime(Clock.local(dt, tz), "%a %-d %b")

  ## The schedule answer (§34.1)

  @doc """
  The server-built answer of a schedule question after its forced read: `{text, sources,
  unread?}`. `checks` are the turn's calendar checks; `local?` whether the answer carries
  `local_events` (then phone events are not listed: the phone shows them).
  """
  def agenda(checks, user, tz, local?) do
    last = List.last(checks)
    {honesty, no_read?} = RisiMe.Agent.CalendarHonesty.enforce_made("", checks, tz)

    with false <- no_read?,
         %{result: %{"from" => f, "to" => t} = result} <- last,
         {:ok, from, _} <- DateTime.from_iso8601(f),
         {:ok, to, _} <- DateTime.from_iso8601(t) do
      sources = result["sources"] || []

      risi =
        if Enum.any?(sources, &(&1["source"] == "risi_calendar" and &1["read_ok"] == true)) do
          case Calendar.own_events(user, from, to) do
            {:ok, evs} ->
              for %{event: e, title: title} <- evs,
                  do: %{
                    "kind" => "risi_calendar_event",
                    "title" => title,
                    "start" => Clock.ts(e.start_at),
                    "end" => Clock.ts(e.end_at),
                    "all_day" => e.all_day == true
                  }

            _ ->
              []
          end
        else
          []
        end

      ledger =
        case list(user,
               from: from,
               to: to,
               kinds: ~w(phone_event_added reminder scheduled_message promise follow_up),
               undated: false
             ) do
          {:ok, items} -> Enum.map(items, &Map.put(&1, "_user", user))
          _ -> []
        end

      phone_added = for %{"kind" => "phone_event_added"} = i <- ledger, do: {i["start"], i["end"]}

      busy =
        if local? do
          []
        else
          for b <- result["blocks"] || [],
              b["busy"] != false,
              not Map.has_key?(b, "status"),
              not same_time?(b, phone_added),
              do: %{
                "kind" => "busy",
                "title" => "Busy (phone calendar)",
                "start" => b["start"],
                "end" => b["end"],
                "all_day" => b["all_day"] == true
              }
        end

      items = Enum.sort_by(risi ++ ledger ++ busy, &{sort_key(&1["start"]), &1["title"] || ""})
      one_day? = DateTime.diff(to, from) <= 86_400
      head = range_label(from, to, tz, one_day?)
      unread = Enum.filter(sources, &(&1["read_ok"] != true and &1["reason"] != "not_connected"))

      body =
        cond do
          items != [] ->
            head <> ":\n" <> Enum.map_join(items, "\n", &("- " <> line(&1, tz, not one_day?)))

          local? ->
            head <> ": your phone shows its events below."

          unread != [] ->
            head <> ": nothing in what I could check."

          true ->
            head <> ": nothing on your calendars."
        end

      srcs =
        for i <- ledger, do: %{"type" => "risi_item", "item_id" => i["id"], "kind" => i["kind"]}

      {body <> "\n\n" <> honesty, srcs, unread != []}
    else
      _ -> {honesty, [], true}
    end
  end

  defp sort_key(nil), do: "~"
  defp sort_key(s), do: s

  defp same_time?(b, pairs) do
    Enum.any?(pairs, fn {s, e} -> same_ts?(b["start"], s) and same_ts?(b["end"], e) end)
  end

  defp same_ts?(a, b) when is_binary(a) and is_binary(b) do
    with {:ok, x, _} <- DateTime.from_iso8601(a),
         {:ok, y, _} <- DateTime.from_iso8601(b) do
      DateTime.compare(x, y) == :eq
    else
      _ -> false
    end
  end

  defp same_ts?(_, _), do: false

  defp range_label(from, _to, tz, true), do: day(from, tz)

  defp range_label(from, to, tz, false),
    do: day(from, tz) <> " – " <> day(DateTime.add(to, -1, :second), tz)

  ## The answer lines (§34.1, §34.4)

  @doc """
  One answer line of an item: `"Mon 12 Oct, 10:00–11:00 · Interview with Shenika (Risi
  Calendar)"` (`day?` false drops the day for a one-day list).
  """
  def line(item, tz, day? \\ true) do
    when_text = when_text(item, tz, day?)
    when_text <> " · " <> what(item)
  end

  defp what(%{"kind" => "risi_calendar_event", "title" => t}), do: "#{t} (Risi Calendar)"

  defp what(%{"kind" => "phone_event_added", "title" => t} = i) do
    where =
      case i["calendar"] do
        %{"name" => n} when is_binary(n) and n != "" -> n
        _ -> "your phone calendar"
      end

    "#{t} (added by Risi to #{where})"
  end

  defp what(%{"kind" => "reminder", "title" => t}), do: "Reminder: #{t}"

  defp what(%{"kind" => "scheduled_message"} = i) do
    who = recipient(i["ref"]["target_conversation_id"], i["_user"])
    every = if i["ref"]["repeat"] == "daily", do: "every day, ", else: ""
    "Message to #{who} (#{every}from your phone)"
  end

  defp what(%{"kind" => "promise", "title" => t}), do: "Your promise: #{t}"
  defp what(%{"kind" => "follow_up", "title" => t}), do: "Following up: #{t}"
  defp what(%{"kind" => "busy", "title" => t}), do: t
  defp what(%{"title" => t}), do: to_string(t)

  defp recipient(conv, user) when is_binary(conv) do
    RisiMe.Agent.Secretary.members(conv)
    |> Enum.find_value("a chat", fn m -> if m.user_id != user and m.name, do: m.name end)
  rescue
    _ -> "a chat"
  end

  defp recipient(_conv, _user), do: "a chat"

  defp when_text(%{"start" => nil}, _tz, _day?), do: "No date"

  defp when_text(%{"start" => s} = i, tz, day?) do
    {:ok, a, _} = DateTime.from_iso8601(s)
    la = Clock.local(a, tz)
    day = Elixir.Calendar.strftime(la, "%a %-d %b")

    time =
      cond do
        i["all_day"] ->
          "all day"

        is_binary(i["end"]) ->
          {:ok, b, _} = DateTime.from_iso8601(i["end"])
          lb = Clock.local(b, tz)
          hm(la) <> "–" <> hm(lb)

        true ->
          hm(la)
      end

    cond do
      day? and i["all_day"] -> day
      day? -> day <> ", " <> time
      true -> time
    end
  end

  defp hm(t), do: Elixir.Calendar.strftime(t, "%H:%M")
end
