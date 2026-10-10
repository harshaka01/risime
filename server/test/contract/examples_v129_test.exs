defmodule RisiMe.Contract.ExamplesV129Test do
  @moduledoc """
  Contract v1.29 §29 Risi Calendar: every §29 example in `contract/v1/examples/` is the server's
  real output (or the exact body the server accepted), checked **exactly**: same keys, same
  values, except ids (any UUID), cursors and wall-clock timestamps (`created_at`, `updated_at`,
  `responded_at`, `server_ts`, `at`, and `expires_at`, asserted separately where it is not a
  clock), which only have to be of the same kind.

  Root regenerates the files from these flows with
  `RISIME_WRITE_EXAMPLES=1 mix test test/contract/examples_v129_test.exs` (integration only).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.{Calendar, CalendarOffers, Commitment, LedgerReminders}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @write System.get_env("RISIME_WRITE_EXAMPLES") == "1"

  @files ~w(auth_config_v129.json device_put_risi_events.json risi_calendar_events_reply.json
            risi_calendar_changes_reply.json risi_calendar_event_create.json
            risi_calendar_event_create_reply.json risi_calendar_event_patch.json
            risi_calendar_respond_accept.json risi_calendar_respond_suggest.json
            risi_calendar_suggestion_resolve.json risi_calendar_settings.json
            event_risi_calendar_changed.json envelope_risi_confirm_risi_calendar_add.json
            envelope_risi_action_confirm_write_edit_risi_calendar.json
            envelope_risi_event_card_added.json envelope_risi_event_card_official.json
            envelope_risi_calendar_invite.json envelope_risi_event_update.json
            envelope_risi_calendar_suggestion.json envelope_risi_calendar_reminder.json
            envelope_risi_action_event_accept.json envelope_risi_action_event_suggest.json
            envelope_risi_answer_calendar_sources_risi.json
            envelope_risi_digest_personal_v129.json error_version_conflict.json
            error_cursor_expired.json error_not_invitable.json)

  @doc false
  def files, do: @files

  setup do
    calendar_world!()
  end

  ## Exact comparison

  @any_uuid ~r/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/
  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
  @clock ~w(created_at updated_at responded_at server_ts at expires_at)

  defp norm(m, _key) when is_map(m), do: Map.new(m, fn {k, v} -> {k, norm(v, k)} end)
  defp norm(l, key) when is_list(l), do: Enum.map(l, &norm(&1, key))
  defp norm(s, "cursor") when is_binary(s), do: if(s =~ ~r/^rc1\./, do: "<cursor>", else: s)

  defp norm(s, key) when is_binary(s) do
    s = Regex.replace(@any_uuid, s, "<id>")
    if key in @clock and s =~ @ts, do: "<ts>", else: s
  end

  defp norm(v, _), do: v

  defp wire(term), do: term |> Jason.encode!() |> Jason.decode!()

  # The example is exactly the server's real output (ids, cursors and clocks by kind only).
  defp check!(name, real) do
    real = wire(real)
    assert name in @files
    path = Path.join(@dir, name)

    if @write do
      File.write!(path, Jason.encode!(real) <> "\n")
    else
      ex = path |> File.read!() |> Jason.decode!()
      assert norm(ex, nil) == norm(real, nil), "#{name} differs from the server's real output"
    end

    real
  end

  defp envelope({_conv, body, risi}),
    do: %{"v" => 1, "type" => "text", "body" => body, "risi" => risi}

  defp post_in(ps, conv, kind),
    do: Enum.find(ps, fn {c, _, r} -> c == conv and r["kind"] == kind end)

  ## Helpers (as the §29 flow tests)

  defp item!(ctx) do
    {:ok, c} =
      Commitment.seal(%Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: ctx.og,
        chat_id: ctx.og,
        state: "proposed",
        text: "Shenika: interview with Harsha",
        owner_id: ctx.s,
        counterpart_ids: [ctx.h],
        due: ~U[2026-10-12 08:30:00.000000Z],
        due_kind: "datetime",
        all_day: false,
        source_message_ids: [],
        proposed_at: ~U[2026-10-09 05:00:00.000000Z]
      })

    Repo.insert!(c)
  end

  defp act!(ctx, who, conv, env) do
    {user, dev} = if who == :h, do: {ctx.harsha.user, ctx.hd}, else: {ctx.shenika.user, ctx.sd}
    id = envelope!(conv, user, env, dev)
    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    assert :ok = perform_job(Job, job.args)
  end

  defp action(target, action, edit),
    do: %{
      "v" => 1,
      "type" => "risi_action",
      "target" => target,
      "action" => action,
      "edit" => edit
    }

  defp request!(ctx, text) do
    rid = Ecto.UUID.generate()

    envelope!(
      ctx.hrc,
      ctx.harsha.user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    job
  end

  defp final(answer, draft \\ nil) do
    f = %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}
    if draft, do: Map.put(f, "draft", draft), else: f
  end

  defp wait_call(user_id, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      [call | _] ->
        call

      [] when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, tries - 1)

      _ ->
        flunk("no risi_tool_call event")
    end
  end

  ## REST, sync, invites, updates, suggestions, reminders, errors

  test "§29.1, §29.3, §29.6, §29.10: REST bodies and replies, the change feed, the cards", ctx do
    # auth_config: the switch on (the deployment keys as in the v1.27 example).
    {200, cfg} = api(:get, "/api/v1/auth/config", ctx.harsha.token, nil, nil)
    assert cfg["risi_events"] == "on"
    v127 = @dir |> Path.join("auth_config_v127.json") |> File.read!() |> Jason.decode!()

    check!(
      "auth_config_v129.json",
      Map.merge(cfg, Map.take(v127, ~w(modes issuer client_id phone_verification signup)))
    )

    # The device capability.
    put =
      check!("device_put_risi_events.json", %{
        "platform" => "android",
        "push_token" => nil,
        "app_version" => "0.3.0-nightly.47",
        "mls" => %{
          "signature_key" => "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
          "capabilities" =>
            ~w(groups member_devices images deletes calls history_share video group_calls
               call_switch screen_share tabs risi_tools risi_skills risi_ledger risi_events)
        }
      })

    dev = Ecto.UUID.generate()
    {200, _} = api(:put, "/api/v1/me/devices/#{dev}", ctx.harsha.token, put, nil)
    assert RisiMe.Devices.risi_events_device?(ctx.h, dev)

    c0 = Calendar.cursor(ctx.h)

    # Create (Harsha, with Shenika, from the Official chat).
    body =
      check!(
        "risi_calendar_event_create.json",
        interview(%{
          "notes" => "Bring the portfolio.",
          "with" => [ctx.s],
          "reminder_min" => 30,
          "source" => %{"conversation_id" => ctx.og, "message_ids" => []}
        })
      )

    {201, created} = as(ctx, :h, :post, "/api/v1/risi/calendar/events", body)
    check!("risi_calendar_event_create_reply.json", created)
    id = created["event"]["event_id"]
    path = "/api/v1/risi/calendar/events/#{id}"

    ps = posts()
    {_, _, inv} = invite = post_in(ps, ctx.src, "calendar_invite")
    assert inv["expires_at"] == inv["start"]
    check!("envelope_risi_calendar_invite.json", envelope(invite))

    [changed | _] = events(ctx.h, "risi_calendar_changed")
    check!("event_risi_calendar_changed.json", changed)

    # Shenika accepts with her own reminder.
    accept =
      check!("risi_calendar_respond_accept.json", %{
        "response" => "accept",
        "version" => 1,
        "reminder_min" => 10
      })

    {200, %{"event" => a}} = as(ctx, :s, :post, path <> "/respond", accept)
    assert a["my_status"] == "accepted" and a["my_reminder_min"] == 10
    ps = posts()
    check!("envelope_risi_event_update.json", envelope(post_in(ps, ctx.hrc, "event_update")))

    {200, list} =
      as(
        ctx,
        :h,
        :get,
        "/api/v1/risi/calendar/events?from=2026-10-12T00:00:00Z&to=2026-10-13T00:00:00Z"
      )

    check!("risi_calendar_events_reply.json", list)

    # Harsha's reminder (default 30 min).
    [job] =
      for j <- all_enqueued(worker: Job),
          j.args["kind"] == "calendar_reminder",
          j.args["event_id"] == id,
          j.args["user_id"] == ctx.h,
          do: j

    assert :ok = perform_job(Job, job.args)
    ps = posts()

    check!(
      "envelope_risi_calendar_reminder.json",
      envelope(post_in(ps, ctx.hrc, "calendar_reminder"))
    )

    # Shenika suggests another time; Harsha uses it.
    suggest =
      check!("risi_calendar_respond_suggest.json", %{
        "response" => "suggest",
        "version" => 1,
        "suggest" => %{
          "start" => "2026-10-13T09:30:00.000Z",
          "end" => "2026-10-13T10:30:00.000Z",
          "all_day" => false
        }
      })

    {200, _} = as(ctx, :s, :post, path <> "/respond", suggest)
    ps = posts()

    {_, _, sug} =
      card = post_in(ps, ctx.hrc, "calendar_suggestion")

    check!("envelope_risi_calendar_suggestion.json", envelope(card))
    use = check!("risi_calendar_suggestion_resolve.json", %{"action" => "use"})

    {200, %{"event" => used}} =
      as(ctx, :h, :post, "/api/v1/risi/calendar/suggestions/#{sug["suggestion_id"]}/resolve", use)

    assert used["version"] == 2 and used["start"] == "2026-10-13T09:30:00.000Z"
    posts()

    # The owner's PATCH (notes, and his own reminder).
    patch =
      check!("risi_calendar_event_patch.json", %{
        "version" => 2,
        "notes" => "Bring the portfolio and two references.",
        "reminder_min" => 15
      })

    {200, %{"event" => p}} = as(ctx, :h, :patch, path, patch)
    assert p["version"] == 3 and p["my_reminder_min"] == 15

    {409, conflict} = as(ctx, :h, :patch, path, %{"version" => 2, "title" => "Late"})
    check!("error_version_conflict.json", conflict)

    stranger = RisiMe.Fixtures.logged_in_user(display_name: "Stranger")

    {422, not_invitable} =
      as(
        ctx,
        :h,
        :post,
        "/api/v1/risi/calendar/events",
        interview(%{"with" => [stranger.user.id]})
      )

    check!("error_not_invitable.json", not_invitable)

    old = System.system_time(:millisecond) - 31 * 86_400_000
    expired = "rc1." <> Base.url_encode64("0.#{old}", padding: false)

    {410, gone} =
      as(ctx, :h, :get, "/api/v1/risi/calendar/changes?since=#{URI.encode_www_form(expired)}")

    check!("error_cursor_expired.json", gone)

    # Shenika's lunch with Harsha; Harsha removes it from his calendar (a participant delete).
    {201, %{"event" => lunch}} =
      as(
        ctx,
        :s,
        :post,
        "/api/v1/risi/calendar/events",
        interview(%{
          "title" => "Lunch",
          "start" => "2026-10-14T06:30:00.000Z",
          "end" => "2026-10-14T07:30:00.000Z",
          "with" => [ctx.h]
        })
      )

    assert {204, nil} = as(ctx, :h, :delete, "/api/v1/risi/calendar/events/#{lunch["event_id"]}")
    posts()

    {200, feed} =
      as(ctx, :h, :get, "/api/v1/risi/calendar/changes?since=#{URI.encode_www_form(c0)}")

    assert Enum.any?(feed["changes"], &(&1["removed"] == true))
    check!("risi_calendar_changes_reply.json", feed)

    {200, settings} = as(ctx, :h, :get, "/api/v1/risi/calendar/settings")
    check!("risi_calendar_settings.json", settings)
  end

  ## The action card (§29.8) and the event card `added` (§29.9)

  test "§29.8, §29.9: the risi_calendar_add card, [Edit], the added event card", ctx do
    fake_llm!(fn _name, _body ->
      final("Sure, Monday 12 Oct at 2 PM.", %{
        "kind" => "event",
        "title" => "Interview",
        "date" => "Monday 12 Oct",
        "time" => "2pm",
        "with" => ["Shenika"]
      })
    end)

    assert :ok =
             perform_job(
               Job,
               request!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm").args
             )

    ps = posts()
    {_, _, card} = c = post_in(ps, ctx.hrc, "confirm")
    assert card["tool"] == "risi_calendar_add"
    check!("envelope_risi_confirm_risi_calendar_add.json", envelope(c))

    edit =
      check!(
        "envelope_risi_action_confirm_write_edit_risi_calendar.json",
        action(card["write_id"], "confirm_write", %{
          "start" => "2026-10-12T09:00:00.000Z",
          "end" => "2026-10-12T10:00:00.000Z",
          "reminder_min" => 15,
          "with" => [ctx.s]
        })
      )

    act!(ctx, :h, ctx.hrc, edit)
    ps = posts()
    check!("envelope_risi_event_card_added.json", envelope(post_in(ps, ctx.hrc, "event_card")))
  end

  ## Risi-made events: the Official card, the digest, the card actions (§29.9, §29.11, §29.12)

  test "§29.9, §29.11, §29.12: the Official event card, the digest's events, accept and suggest",
       ctx do
    item!(ctx)
    assert %{items: 1} = CalendarOffers.backfill()
    ps = posts()

    {_, _, official} = o = post_in(ps, ctx.og, "event_card")
    check!("envelope_risi_event_card_official.json", envelope(o))

    # Mon 12 Oct 09:05 Colombo: the digest lists today's (still proposed) interview.
    now = ~U[2026-10-12 03:35:00Z]
    Application.put_env(:risime, :risi_now, now)
    assert :sent = LedgerReminders.digest(ctx.h, now)
    ps = posts()
    check!("envelope_risi_digest_personal_v129.json", envelope(post_in(ps, ctx.hrc, "digest")))

    eid = official["event_id"]

    accept =
      check!(
        "envelope_risi_action_event_accept.json",
        action(eid, "event_accept", %{"reminder_min" => 10})
      )

    act!(ctx, :h, ctx.og, accept)
    {:ok, e} = Calendar.get(ctx.h, eid)
    assert e["my_status"] == "accepted" and e["my_reminder_min"] == 10
    posts()

    suggest =
      check!(
        "envelope_risi_action_event_suggest.json",
        action(eid, "event_suggest", %{
          "start" => "2026-10-12T10:30:00.000Z",
          "end" => "2026-10-12T11:30:00.000Z",
          "all_day" => false
        })
      )

    act!(ctx, :h, ctx.hrc, suggest)
    assert {_, _, %{"event_id" => ^eid}} = post_in(posts(), ctx.src, "calendar_suggestion")
  end

  ## The honesty rule (§29.7)

  test "§29.7: the answer with Risi Calendar and the phone calendar, and its sources", ctx do
    item!(ctx)
    CalendarOffers.backfill()
    posts()
    change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

    {200, _} =
      api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, ctx.hd)

    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: final("You have a tentative interview at 2 PM on Monday."),
        else: %{
          "tool" => "risi_calendar_check",
          "args" => %{"from" => "2026-10-12T08:00:00Z", "to" => "2026-10-12T10:00:00Z"}
        }
    end)

    job = request!(ctx, "Am I free Monday 2pm?")
    task = Task.async(fn -> perform_job(Job, job.args) end)
    call = wait_call(ctx.h)
    assert call["data"]["tool"] == "calendar_check"

    result = %{
      "blocks" => [],
      "sources" => [
        %{
          "source" => "phone_provider",
          "calendars" => [%{"name" => "Work", "account_type" => "com.google", "events" => 0}],
          "read_ok" => true,
          "reason" => nil
        },
        %{
          "source" => "google_api",
          "calendars" => [],
          "read_ok" => false,
          "reason" => "not_connected"
        }
      ],
      "connected_sources" => ["phone_provider"]
    }

    {204, _} =
      api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => result},
        ctx.hd
      )

    assert :ok = Task.await(task)
    {_, body, _} = a = post_in(posts(), ctx.hrc, "answer")
    assert body =~ "Checked: Risi Calendar · Phone calendar (Work)."
    refute body =~ "Google Calendar"
    check!("envelope_risi_answer_calendar_sources_risi.json", envelope(a))
  end
end
