defmodule RisiMe.Agent.CalendarTools do
  @moduledoc """
  The Risi Calendar tools of the turn (contract v1.29 §29.7, §29.8), offered only to calendar
  users (`RisiMe.Agent.Calendar.calendar_user?/1`):

  * **`risi_calendar_check`** (server, read, personal): the asker's own events (accepted and
    proposed) in `{from, to}` (≤ 14 days, clamped) as `{"source": "risi_calendar", "read_ok",
    "blocks": [{start, end, all_day, status, ref: "e<n>"}]}`. Titles go to the model **only**
    when the turn can only run on a RisiMe model (`RisiMe.Agent.LLM.own_only?/0`). When the
    asker's phone Calendar skill is on, the phone `calendar_check` runs for the same window in
    the same step, so the answer covers every connected source; `RisiMe.Agent.CalendarHonesty`
    then names what was checked.
  * **`risi_calendar_add`** (server, write, personal): one `confirm` card ([Add] [Edit]
    [Cancel]); **[Add] creates the event at once** (`exec/3`: no client tool, no model call, no
    device permission), posts the `event_card` `mode: "added"` and invites the `with` people.

  The phone's own `calendar_check` is not offered to a calendar user (this tool runs it), and
  `calendar_add` stays for an explicit "add to my phone calendar".
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, CalendarCards, ClientTools, Clock, Writes}
  alias RisiMe.Repo

  @max_range_s 14 * 86_400

  defp obj(props, required),
    do: %{
      "type" => "object",
      "properties" => props,
      "required" => required,
      "additionalProperties" => false
    }

  @str %{"type" => "string", "maxLength" => 100}

  defp calendar_user(ctx),
    do: if(Calendar.calendar_user?(ctx.asker), do: :ok, else: {:error, :denied})

  ## risi_calendar_check

  def risi_calendar_check do
    %{
      name: "risi_calendar_check",
      description:
        "check when the asker is free or busy: their Risi Calendar, and their phone's calendar " <>
          "when it is connected (from, to: an ISO time or a local phrase like \"Monday 00:00\"; " <>
          "at most 14 days); a proposed event is tentative",
      where: :server,
      personal: true,
      write: false,
      finds: true,
      args: obj(%{"from" => @str, "to" => @str}, ["from", "to"]),
      authorize: &calendar_user/1,
      run: &run_check/2
    }
  end

  defp run_check(%{"from" => f, "to" => t}, ctx) do
    with {:ok, from, _} <- ClientTools.time(f, ctx),
         {:ok, to, kind} <- ClientTools.time(t, ctx),
         to = if(kind == :date, do: DateTime.add(to, 86_400 - 1, :second), else: to),
         true <- DateTime.compare(to, from) == :gt || {:error, "failed", "end_before_start"} do
      to = Enum.min([to, DateTime.add(from, @max_range_s, :second)], DateTime)
      window = %{"from" => Clock.ts(from), "to" => Clock.ts(to)}
      {risi_source, risi_blocks} = risi_read(ctx.asker, from, to)
      {phone, google} = reads(window, ctx)

      model =
        Map.merge(window, %{
          "source" => "risi_calendar",
          "read_ok" => risi_source["read_ok"],
          "blocks" => risi_blocks,
          "phone" => phone && phone.model
        })

      # v1.31 §31.4: the model sees Google as {source, read_ok, reason, calendars: <count>,
      # blocks} only: no refs of calendars, no names.
      model = if google, do: Map.put(model, "google", google.model), else: model

      busy = for b <- risi_blocks, do: Map.drop(b, ["title", "ref"]) |> Map.put("busy", true)

      check =
        Map.merge(window, %{
          "blocks" =>
            busy ++ ((phone && phone.blocks) || []) ++ ((google && google.blocks) || []),
          "sources" =>
            [risi_source | (phone && phone.sources) || []] ++ ((google && [google.source]) || []),
          "connected_sources" =>
            Enum.uniq(
              ["risi_calendar" | (phone && phone.connected) || []] ++
                ((google && google.connected) || [])
            )
        })

      refs = Map.merge((phone && phone.refs) || %{}, (google && google.refs) || %{})
      {:ok, model, %{refs: refs, calendar_check: check}}
    end
  end

  defp run_check(_args, _ctx), do: {:error, "failed", "bad_args"}

  # The Risi Calendar read: a source (§29.7) and the model's blocks.
  defp risi_read(user, from, to) do
    case Calendar.own_events(user, from, to) do
      {:ok, evs} ->
        titles? = RisiMe.Agent.LLM.own_only?()

        blocks =
          for {%{event: e, status: status, title: title}, i} <- Enum.with_index(evs, 1) do
            %{
              "start" => Clock.ts(e.start_at),
              "end" => Clock.ts(e.end_at),
              "all_day" => e.all_day,
              "status" => status,
              "ref" => "e#{i}"
            }
            |> then(&if(titles?, do: Map.put(&1, "title", title), else: &1))
          end

        {source(true, nil, length(evs)), blocks}

      :error ->
        {source(false, "unavailable", 0), []}
    end
  end

  defp source(ok?, reason, n),
    do: %{
      "source" => "risi_calendar",
      "read_ok" => ok?,
      "reason" => reason,
      "calendars" =>
        if(ok?,
          do: [%{"name" => "Risi Calendar", "account_type" => "risime", "events" => n}],
          else: []
        )
    }

  ## The Google read (v1.31 §31.4)

  # `{phone, google}`: the phone read (nil: not consulted) and the Google read (nil: the link is
  # not in play: the switch is off, or the user has none).
  defp reads(window, ctx) do
    tool = ClientTools.calendar_check()
    phone? = phone_ok?(tool, ctx)

    case google_plan(ctx.asker) do
      nil ->
        {phone_read(window, ctx), nil}

      {:skip, reason, link} ->
        {phone_read(window, ctx), google_result(link, reason, [], [], %{})}

      {:call, link} when phone? and link.device_id == ctx.device_id ->
        combined_read(window, ctx, link)

      {:call, link} ->
        gtask = Task.async(fn -> google_read(window, ctx, link) end)
        phone = phone_read(window, ctx)
        google = Task.await(gtask, :infinity)
        {phone, renumber(google, phone)}
    end
  end

  # The link is in play only for a `connected` link and a Calendar skill that is not `off`.
  defp google_plan(user) do
    with true <- RisiMe.Agent.GoogleLink.on?(),
         %{} = link <- RisiMe.Agent.GoogleLink.get(user) do
      cond do
        RisiMe.Agent.Skills.state(user, "calendar") == "off" -> {:skip, "paused", link}
        link.state != "connected" -> {:skip, "reauth_needed", link}
        true -> {:call, link}
      end
    else
      _ -> nil
    end
  end

  # The Google device is also the asking device: ONE call for both sources.
  defp combined_read(window, ctx, link) do
    tool = ClientTools.calendar_check()
    gctx = Map.put(ctx, :check_sources, ["phone_provider", "google_api"])

    case tool.run.(window, gctx) do
      {:ok, result, meta} ->
        check = meta[:calendar_check] || %{}
        {gsrc, others} = Enum.split_with(check["sources"] || [], &(&1["source"] == "google_api"))
        gsrc = List.first(gsrc) || %{}

        phone = %{
          model: Map.update(result, "sources", nil, &drop_google/1),
          blocks: check["blocks"] || [],
          sources: others,
          connected: Enum.reject(check["connected_sources"] || [], &(&1 == "google_api")),
          refs: meta[:refs] || %{}
        }

        # The blocks of the two sources arrive merged: they stay in the phone's list.
        google =
          google_result(
            link,
            gsrc["reason"],
            gsrc["calendars"] || [],
            [],
            %{},
            gsrc["read_ok"] == true
          )

        {phone, google}

      other ->
        {phone_failed(other), google_result(link, google_failure(other), [], [], %{})}
    end
  end

  defp drop_google(sources) when is_list(sources),
    do: Enum.reject(sources, &(&1["source"] == "google_api"))

  defp drop_google(other), do: other

  # A separate call to the Google device.
  defp google_read(window, ctx, link) do
    tool = ClientTools.calendar_check()

    gctx =
      ctx |> Map.put(:check_sources, ["google_api"]) |> Map.put(:check_to, link.device_id)

    case tool.run.(window, gctx) do
      {:ok, _result, meta} ->
        check = meta[:calendar_check] || %{}
        gsrc = Enum.find(check["sources"] || [], %{}, &(&1["source"] == "google_api"))
        read? = gsrc["read_ok"] == true

        google_result(
          link,
          gsrc["reason"],
          gsrc["calendars"] || [],
          check["blocks"] || [],
          meta[:refs] || %{},
          read?
        )

      other ->
        google_result(link, google_failure(other), [], [], %{})
    end
  end

  # An unanswered call is `no_answer` ("phone didn't answer"); the turn goes on.
  defp google_failure({:error, "timeout"}), do: "no_answer"
  defp google_failure(_), do: "api_error"

  defp phone_failed({:error, status, reason}), do: failed(status, reason)
  defp phone_failed({:error, status}), do: failed(status, nil)
  defp phone_failed(_), do: failed("failed", nil)

  # The Google blocks take `c<n>` refs after the phone's.
  defp renumber(google, phone) do
    offset = map_size((phone && phone.refs) || %{})

    {blocks, refs} =
      google.blocks
      |> Enum.with_index(offset + 1)
      |> Enum.map(fn {b, i} -> {b, "c#{i}"} end)
      |> Enum.reduce({[], %{}}, fn {b, ref}, {bs, rs} ->
        {[Map.put(b, "ref", ref) | bs], Map.put(rs, ref, Map.put(b, "type", "calendar"))}
      end)

    shown = Enum.reverse(blocks)

    put_in(
      %{google | refs: refs},
      [:model, "blocks"],
      Enum.map(shown, &Map.take(&1, ~w(start end all_day ref)))
    )
  end

  # The Google source as the check keeps it (refs and a count, never names) and as the model sees
  # it (§31.4).
  defp google_result(link, reason, calendars, blocks, refs, read? \\ false) do
    reason = if read?, do: nil, else: reason || "api_error"
    cals = if read?, do: calendars, else: []

    %{
      source: %{
        "source" => "google_api",
        "read_ok" => read?,
        "reason" => reason,
        "calendars" => cals,
        "count" => link.read_calendars
      },
      model: %{
        "source" => "google_api",
        "read_ok" => read?,
        "reason" => reason,
        "calendars" => length(cals),
        "blocks" =>
          for(
            {b, i} <- Enum.with_index(blocks, 1),
            do: Map.take(b, ~w(start end all_day)) |> Map.put("ref", "c#{i}")
          )
      },
      blocks: blocks,
      refs: refs,
      connected: ["google_api"]
    }
  end

  # The phone `calendar_check` for the same window, when the asker's Calendar skill and phone
  # allow it (nil: not consulted).
  defp phone_read(window, ctx) do
    tool = ClientTools.calendar_check()

    if phone_ok?(tool, ctx) do
      case tool.run.(window, ctx) do
        {:ok, result, meta} ->
          check = meta[:calendar_check] || %{}

          %{
            model: result,
            blocks: check["blocks"] || [],
            sources: phone_sources(check["sources"]),
            connected: check["connected_sources"] || [],
            refs: meta[:refs] || %{}
          }

        {:error, status, reason} ->
          failed(status, reason)

        {:error, status} ->
          failed(status, nil)

        _ ->
          failed("failed", nil)
      end
    end
  end

  # An old phone (no `sources`): blocks were read, but not which calendars.
  defp phone_sources(sources) when is_list(sources), do: sources

  defp phone_sources(_),
    do: [
      %{
        "source" => "phone_provider",
        "read_ok" => true,
        "reason" => nil,
        "calendars" => [%{"name" => "your phone's calendar", "account_type" => "", "events" => 0}]
      }
    ]

  defp failed(status, reason) do
    why =
      case {status, reason} do
        {"no_permission", _} -> "no_permission"
        {"timeout", _} -> "timeout"
        {_, r} when is_binary(r) -> r
        {s, _} -> s
      end

    %{
      model: %{"ok" => false, "status" => status},
      blocks: [],
      sources: [
        %{"source" => "phone_provider", "read_ok" => false, "reason" => why, "calendars" => []}
      ],
      connected: [],
      refs: %{}
    }
  end

  defp phone_ok?(tool, ctx) do
    is_binary(ctx[:device_id]) and RisiMe.Devices.risi_tools_device?(ctx.asker, ctx.device_id) and
      RisiMe.Agent.Skills.allows?(tool, ctx) and
      (not Map.get(ctx, :gated?, false) or
         RisiMe.Agent.Skills.state(ctx.asker, "calendar") in ~w(ask allowed))
  end

  ## risi_calendar_add

  def risi_calendar_add do
    %{
      name: "risi_calendar_add",
      description:
        "propose adding an event to the asker's Risi Calendar, the default calendar (the asker " <>
          "taps Add; start/end: ISO or a local phrase like \"Friday 10:00\"; with: names of " <>
          "people to invite)",
      where: :server,
      personal: true,
      write: true,
      finds: false,
      args:
        obj(
          %{
            "title" => %{"type" => "string", "minLength" => 1, "maxLength" => 200},
            "start" => @str,
            "end" => %{"type" => ["string", "null"], "maxLength" => 100},
            "all_day" => %{"type" => ["boolean", "null"]},
            "with" => %{
              "type" => ["array", "null"],
              "maxItems" => 19,
              "items" => %{"type" => "string", "maxLength" => 80}
            },
            "reminder_min" => %{
              "type" => ["integer", "null"],
              "minimum" => 0,
              "maximum" => 10_080
            }
          },
          ["title", "start"]
        ),
      authorize: &calendar_user/1,
      run: &run_add/2,
      exec: &exec/3
    }
  end

  defp run_add(%{"title" => title, "start" => s} = a, ctx) do
    title = String.trim(title)
    settings = Calendar.settings(ctx.asker)

    with true <- (title != "" and String.length(title) <= 200) || {:error, "failed", "bad_title"},
         {:ok, start, kind} <- ClientTools.time(s, ctx),
         all_day = a["all_day"] == true or (kind == :date and a["end"] in [nil, ""]),
         {:ok, stop} <- stop_of(a["end"], start, all_day, settings, ctx),
         true <- DateTime.compare(stop, start) == :gt || {:error, "failed", "end_before_start"},
         :ok <- ClientTools.future(start, ctx) do
      {ok_ids, _} = Calendar.invitable(ctx.asker, with_ids(a, ctx))
      tz = ctx.tz
      source = a["source"] || default_source(ctx)

      reminder =
        if Map.has_key?(a, "reminder_min") and a["reminder_min"] != :default,
          do: a["reminder_min"],
          else: settings["default_reminder_min"]

      wire = %{
        "title" => title,
        "start" => Clock.ts(start),
        "end" => Clock.ts(stop),
        "all_day" => all_day,
        "tz" => tz,
        "with" => ok_ids,
        "reminder_min" => reminder,
        "notes" => nil,
        "source" => source
      }

      {:propose,
       %{
         args: %{"wire" => wire, "title" => title},
         card_args: wire,
         summary: summary(title, start, stop, all_day, tz, ok_ids),
         when: %{"start" => wire["start"], "end" => wire["end"], "all_day" => all_day},
         text: title,
         personal: true,
         skill_id: nil,
         item_id: source && source["item_id"]
       }}
    end
  end

  defp run_add(_args, _ctx), do: {:error, "failed", "bad_args"}

  # A date alone is an all-day event (a day); a start alone lasts the default duration (60).
  defp stop_of(e, start, true, _settings, _ctx) when e in [nil, ""],
    do: {:ok, DateTime.add(start, 86_400, :second)}

  defp stop_of(e, start, false, settings, _ctx) when e in [nil, ""],
    do: {:ok, DateTime.add(start, (settings["default_duration_min"] || 60) * 60, :second)}

  defp stop_of(e, _start, all_day, _settings, ctx) do
    case ClientTools.time(e, ctx) do
      {:ok, t, :date} when all_day -> {:ok, DateTime.add(t, 86_400, :second)}
      {:ok, t, _} -> {:ok, t}
      error -> error
    end
  end

  @doc "\"Add to your Risi Calendar: Interview · Mon 12 Oct, 2–3 PM · with Shenika\"."
  def summary(title, start, stop, all_day, tz, with_ids) do
    names = CalendarCards.names(with_ids)
    people = for id <- with_ids, n = names[id], do: n

    with =
      case people do
        [] -> ""
        [a] -> " · with #{a}"
        list -> " · with " <> Enum.join(Enum.drop(list, -1), ", ") <> " and " <> List.last(list)
      end

    "Add to your Risi Calendar: #{title} · #{ClientTools.span12(start, stop, all_day, tz)}#{with}"
  end

  # The server's draft path passes ids (an item's people); the model passes names.
  defp with_ids(%{"with_ids" => ids}, _ctx) when is_list(ids), do: ids

  defp with_ids(%{"with" => names}, ctx) when is_list(names) and names != [],
    do: resolve_names(ctx.asker, names)

  defp with_ids(_a, _ctx), do: []

  defp default_source(ctx) do
    if is_binary(ctx[:conv]) and RisiMe.Agent.official?(ctx.conv),
      do: %{"conversation_id" => ctx.conv, "message_ids" => [], "item_id" => nil},
      else: nil
  end

  @doc """
  The user ids of the people `names` names among the asker's contacts (friends and members of
  their active groups): a full display name or its first word, case-insensitive; unknown or
  ambiguous names are left out.
  """
  def resolve_names(asker, names) do
    people = contacts(asker)

    names
    |> Enum.filter(&is_binary/1)
    |> Enum.map(&(&1 |> String.trim() |> String.downcase()))
    |> Enum.flat_map(fn n ->
      full = for {id, name} <- people, String.downcase(name) == n, do: id

      first =
        for {id, name} <- people,
            name |> String.split() |> List.first() |> to_string() |> String.downcase() == n,
            do: id

      case {Enum.uniq(full), Enum.uniq(first)} do
        {[id], _} -> [id]
        {[], [id]} -> [id]
        _ -> []
      end
    end)
    |> Enum.uniq()
    |> Kernel.--([asker])
  end

  defp contacts(asker) do
    groups =
      Repo.all(
        from a in RisiMe.Groups.Member,
          join: b in RisiMe.Groups.Member,
          on: b.group_id == a.group_id,
          join: g in RisiMe.Groups.Group,
          on: g.id == a.group_id,
          join: u in RisiMe.Accounts.User,
          on: u.id == b.user_id,
          where:
            a.user_id == ^asker and a.state == "active" and b.state == "active" and
              b.kind == "user" and u.kind == "user" and g.state == "active" and
              b.user_id != ^asker,
          distinct: true,
          select: {u.id, u.display_name}
      )

    friends =
      case RisiMe.Social.friend_ids(asker) do
        [] ->
          []

        ids ->
          Repo.all(
            from u in RisiMe.Accounts.User,
              where: u.id in ^ids and u.kind == "user",
              select: {u.id, u.display_name}
          )
      end

    (groups ++ friends)
    |> Enum.reject(fn {_id, n} -> is_nil(n) end)
    |> Enum.uniq()
  end

  @doc """
  [Add] of a `risi_calendar_add` card (§29.8): creates the event at once (non-invitable people
  are dropped: "Couldn't invite <name>"), posts the `event_card` `mode: "added"` in the Risi
  chat and invites the others. `:ok` or `{:error, status}` (the card may be tapped again).
  """
  def exec(%Writes.Write{} = w, %{"wire" => wire}, _device) do
    with true <- Calendar.calendar_user?(w.user_id) || {:error, :off},
         {:ok, attrs} <- Calendar.parse_new(w.user_id, Map.put(wire, "with", [])),
         {ok_ids, dropped} = Calendar.invitable(w.user_id, wire["with"] || []),
         {:ok, view} <- Calendar.create(w.user_id, %{attrs | with: ok_ids}) do
      {e, parts} = Calendar.load(view["event_id"])
      CalendarCards.added(e, parts, w.card_conversation_id, w.user_id, dropped)
      :ok
    else
      {:error, reason} ->
        Logger.warning("Risi calendar add failed: #{inspect(reason)}")
        add_failed(w, reason)
        {:error, "failed"}
    end
  end

  def exec(_w, _args, _device), do: {:error, "failed"}

  defp add_failed(w, reason) do
    text =
      case reason do
        :off -> "Risi Calendar isn't available right now, so nothing was added."
        :agent_unavailable -> "I couldn't open your Risi Calendar, so nothing was added."
        _ -> "I couldn't add it to your Risi Calendar, so nothing was added."
      end

    Writes.post(w, text, %{
      "kind" => "answer",
      "request_id" => w.request_id,
      "answer" => text,
      "refs" => [],
      "confidence" => 1.0,
      "steps" => [%{"tool" => w.tool, "status" => "failed"}],
      "sources" => [],
      "next_steps" => [],
      "turn_ref" => w.turn_id,
      "notify" => [w.user_id]
    })
  end
end
