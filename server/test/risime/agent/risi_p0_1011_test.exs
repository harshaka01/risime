defmodule RisiMe.Agent.RisiP01011Test do
  @moduledoc """
  v1.35 §34 gate (P0 2026-10-11, Harsha on nightly.48), with a fixed clock (Fri 9 Oct 2026,
  10:30 Colombo) and a scripted fake model:

    1. schedule questions (English, Sinhala, Tamil, Singlish) force the read before any answer,
       even when the model picks `schedule_message` or answers at once;
    2. the Monday chip reads Mon 12 Oct and never runs a write tool;
    3. a pending card is superseded by the next request (confirm → `superseded`, nothing runs);
    4. "Ask me again" is an `ask` with the ORIGINAL text, and re-runs it;
    5. "what have you set up for me?" answers from the ledger only;
    6. "Shutazi" asks "Did you mean Shirazi?" before any card.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.{Commitment, RisiItems, TurnSteps, Writes}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    ctx = calendar_world!()
    shirazi = RisiMe.Fixtures.logged_in_user(display_name: "Shirazi")
    RisiMe.Fixtures.befriend!(ctx.harsha.user, shirazi.user)
    Map.put(ctx, :shirazi, shirazi)
  end

  ## Helpers

  defp question(body) do
    text = Enum.find(body["messages"], &(&1["role"] == "user"))["content"]
    [_, line] = Regex.run(~r/<question>\n(.*)\n<\/question>/, text)
    Jason.decode!(line)["q"]
  end

  defp final(answer, next \\ [], draft \\ nil) do
    f = %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => next}
    if draft, do: Map.put(f, "draft", draft), else: f
  end

  # The model always tries `schedule_message` first, then answers from "memory".
  defp hijacking_model! do
    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: final("You have a dentist appointment at 9."),
        else: %{
          "tool" => "schedule_message",
          "args" => %{"to" => "Shenika", "text" => "hi", "at" => "Monday 9am"}
        }
    end)
  end

  defp ask!(ctx, text) do
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
    assert :ok = perform_job(Job, job.args)
    rid
  end

  defp act!(ctx, action, target) do
    id =
      envelope!(
        ctx.hrc,
        ctx.harsha.user,
        %{"v" => 1, "type" => "risi_action", "target" => target, "action" => action},
        ctx.hd
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    assert :ok = perform_job(Job, job.args)
  end

  defp answer_of(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)
  defp card_of(ps), do: Enum.find_value(ps, fn {_, _, r} -> if r["kind"] == "confirm", do: r end)

  defp event!(ctx, title, start, stop) do
    {201, _} =
      as(ctx, :h, :post, "/api/v1/risi/calendar/events", %{
        "client_event_id" => Ecto.UUID.generate(),
        "title" => title,
        "start" => start,
        "end" => stop,
        "all_day" => false,
        "tz" => "Asia/Colombo"
      })

    :ok
  end

  defp enums(req) do
    get_in(req, ["response_format", "json_schema", "schema", "anyOf"])
    |> Enum.flat_map(&get_in(&1, ["properties", "tool", "enum"]))
  end

  ## 1. Schedule questions force the read (every language)

  @questions [
    {"what appointment do I have tomorrow", "Sat 10 Oct"},
    {"හෙට මට තියෙන appointments මොනවද?", "Sat 10 Oct"},
    {"நாளை எனக்கு என்ன சந்திப்புகள் உள்ளன?", "Sat 10 Oct"},
    {"heta mata meeting thiyenawada?", "Sat 10 Oct"},
    {"sanduda mokada thiyenne?", "Mon 12 Oct"}
  ]

  test "schedule questions in every language run risi_calendar_check first, even when the model picks schedule_message",
       ctx do
    hijacking_model!()

    for {q, day} <- @questions do
      ask!(ctx, q)
      ps = posts()
      {_, body, a} = answer_of(ps)
      refute card_of(ps), q
      assert [%{"tool" => "risi_calendar_check", "status" => "ok"} | _] = a["steps"], q
      refute Enum.any?(a["steps"], &(&1["tool"] == "schedule_message")), q
      assert String.starts_with?(body, day <> ":"), "#{q}: #{body}"
      refute body =~ "dentist", q
      assert body =~ "Checked: Risi Calendar · Risi's items."
      assert [%{tool: "risi_calendar_check"} | _] = TurnSteps.list(a["turn_ref"])
    end

    # The model was never offered a write tool after the read.
    for req <- llm_requests() do
      e = enums(req)
      refute "schedule_message" in e
      refute "risi_calendar_add" in e
      assert "final" in e
    end
  end

  test "a model that answers at once still gets the read first", ctx do
    fake_llm!(fn _name, _body -> final("You're free all day tomorrow!") end)
    ask!(ctx, "what appointment do I have tomorrow")
    {_, body, a} = answer_of(posts())
    assert [%{"tool" => "risi_calendar_check"}] = a["steps"]

    assert body ==
             "Sat 10 Oct: nothing on your calendars.\n\nChecked: Risi Calendar · Risi's items."
  end

  ## 2. The Monday chip

  test "the chip 'Check my calendar for Monday, October 12th' reads Mon 12 Oct and lists every event",
       ctx do
    event!(ctx, "Meeting with Shirazi", "2026-10-12T04:30:00.000Z", "2026-10-12T05:30:00.000Z")
    event!(ctx, "Interview with Shenika", "2026-10-12T08:30:00.000Z", "2026-10-12T09:30:00.000Z")
    event!(ctx, "Next day", "2026-10-13T08:30:00.000Z", "2026-10-13T09:30:00.000Z")
    posts()
    hijacking_model!()

    ask!(ctx, "Check my calendar for Monday, October 12th")
    ps = posts()
    refute card_of(ps)
    {_, body, a} = answer_of(ps)

    assert body ==
             "Mon 12 Oct:\n- 10:00–11:00 · Meeting with Shirazi (Risi Calendar)\n" <>
               "- 14:00–15:00 · Interview with Shenika (Risi Calendar)\n\n" <>
               "Checked: Risi Calendar · Risi's items."

    assert a["steps"] |> Enum.map(& &1["tool"]) |> hd() == "risi_calendar_check"
    refute Enum.any?(a["steps"], &(&1["tool"] in ~w(schedule_message set_reminder calendar_add)))

    assert Enum.map(a["sources"], & &1["source"]) |> Enum.take(2) == [
             "risi_calendar",
             "risi_items"
           ]

    # The read's window is the asker's Monday.
    [req | _] = llm_requests()
    tool_msg = Enum.find(req["messages"], &(&1["role"] == "assistant"))

    assert Jason.decode!(tool_msg["content"])["args"] == %{
             "from" => "2026-10-11T18:30:00.000Z",
             "to" => "2026-10-12T18:30:00.000Z"
           }
  end

  ## 3. A pending card never hijacks the next turn

  test "a new request supersedes the open card; confirming it runs nothing", ctx do
    fake_llm!(fn _name, body ->
      case question(body) do
        "add dentist Friday 16 Oct 10am" ->
          final("Dentist on Friday.", [], %{
            "kind" => "event",
            "title" => "Dentist",
            "date" => "Friday 16 Oct",
            "time" => "10am"
          })

        _ ->
          final("Hello!")
      end
    end)

    ask!(ctx, "add dentist Friday 16 Oct 10am")
    card = card_of(posts())
    assert card["tool"] == "risi_calendar_add"

    rid2 = ask!(ctx, "say hello")
    ps = posts()
    refute card_of(ps)
    {_, "Hello!", _} = answer_of(ps)
    assert Writes.get(card["write_id"]).state == "superseded"
    # The unpatched draft is gone: no card from it later.
    assert RisiMe.Agent.ActionDraft.get(ctx.h, ctx.hrc) == nil

    act!(ctx, "confirm_write", card["write_id"])

    assert [{_, "This was replaced by your newer request.", err}] = posts()
    assert err["kind"] == "error" and err["code"] == "superseded"
    assert err["write_id"] == card["write_id"]

    assert {:ok, %{"events" => []}} =
             RisiMe.Agent.Calendar.list(ctx.h, "2026-10-09T00:00:00Z", "2026-10-31T00:00:00Z")

    # A cancel of it is a silent no-op.
    act!(ctx, "cancel_write", card["write_id"])
    assert posts() == []
    assert is_binary(rid2)
  end

  test "a Phase 2 (risi_items) device gets a silent confirm_update", ctx do
    RisiMe.TabsHelpers.tabs_device!(ctx.harsha.user,
      caps:
        ~w(groups member_devices tabs risi_tools risi_skills risi_ledger risi_events risi_items)
    )

    fake_llm!(fn _name, body ->
      if question(body) == "add dentist Friday 16 Oct 10am",
        do:
          final("Dentist.", [], %{
            "kind" => "event",
            "title" => "Dentist",
            "date" => "Friday 16 Oct",
            "time" => "10am"
          }),
        else: final("Hi.")
    end)

    ask!(ctx, "add dentist Friday 16 Oct 10am")
    card = card_of(posts())
    rid = ask!(ctx, "hi")
    ps = posts()

    assert {_, "Replaced by your newer request.", u} =
             Enum.find(ps, fn {_, _, r} -> r["kind"] == "confirm_update" end)

    assert u["write_id"] == card["write_id"] and u["state"] == "superseded"
    assert u["by_request_id"] == rid and u["notify"] == []
  end

  ## 4. Ask me again

  test "a failed turn's chip asks the ORIGINAL question again; the old 'Ask me again' words too",
       ctx do
    q = "summarise my week of work please"
    fake_llm!(fn _name, _body -> {:raw, "not json"} end)
    ask!(ctx, q)
    {_, _body, a} = answer_of(posts())

    assert a["next_actions"] == [%{"label" => "Ask me again", "action" => "ask", "text" => q}]
    n = length(llm_requests())
    fake_llm!(fn _name, _body -> final("Done.") end)
    ask!(ctx, "Ask me again")
    posts()
    [req | _] = llm_requests()
    assert question(req) == q
    assert n > 0
  end

  ## 5. What have you set up for me?

  test "'what have you set up for me?' lists the ledger only, with risi_item sources", ctx do
    fake_llm!(fn _name, _body -> final("I set up a trip to the moon for you.") end)

    w = %Writes.Write{write_id: Ecto.UUID.generate(), user_id: ctx.h}

    :ok =
      RisiItems.record_phone_event(
        w,
        %{
          "title" => "Call with Kamal",
          "wire" => %{
            "start" => "2026-10-12T08:30:00.000Z",
            "end" => "2026-10-12T09:30:00.000Z",
            "all_day" => false
          }
        },
        ctx.hd,
        %{
          "event_id" => "4711",
          "verified" => true,
          "calendar" => %{"name" => "Work", "account" => "Google"}
        }
      )

    event!(ctx, "Interview with Shenika", "2026-10-12T04:30:00.000Z", "2026-10-12T05:30:00.000Z")
    [e] = RisiMe.Repo.all(RisiMe.Agent.Calendar.Event)
    :ok = RisiItems.record_risi_event(ctx.h, e)

    {:ok, c} =
      Commitment.seal(%Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: ctx.og,
        chat_id: ctx.og,
        state: "confirmed",
        item_state: "confirmed",
        source: "chat",
        proposed_at: ~U[2026-10-09 05:00:00.000000Z],
        text: "Send the revised quote",
        owner_id: ctx.h,
        counterpart_ids: [ctx.s],
        due: ~U[2026-10-14 11:30:00.000000Z],
        due_kind: "datetime",
        all_day: false,
        source_message_ids: []
      })

    RisiMe.Repo.insert!(c)
    posts()

    for q <- ["what have you set up for me?", "ඔයා මට මොනවද set කරලා තියෙන්නේ?"] do
      ask!(ctx, q)
      {_, body, a} = answer_of(posts())

      assert body ==
               "Here's what I've set up for you:\n" <>
                 "- Mon 12 Oct, 10:00–11:00 · Interview with Shenika (Risi Calendar)\n" <>
                 "- Mon 12 Oct, 14:00–15:00 · Call with Kamal (added by Risi to Work)\n" <>
                 "- Wed 14 Oct, 17:00 · Your promise: Send the revised quote"

      assert a["steps"] == [%{"tool" => "risi_items", "status" => "ok"}]

      assert Enum.map(a["sources"], & &1["kind"]) ==
               ~w(risi_calendar_event phone_event_added promise)

      assert Enum.all?(a["sources"], &(&1["type"] == "risi_item"))
      assert a["made_by"]["model"] == nil
    end

    assert llm_requests() == []

    # The REST list, from the device that added the phone event.
    {200, %{"items" => items}} = as(ctx, :h, :get, "/api/v1/risi/items")
    assert Enum.map(items, & &1["kind"]) == ~w(risi_calendar_event phone_event_added promise)
    phone = Enum.find(items, &(&1["kind"] == "phone_event_added"))
    assert phone["actions"] == ~w(open edit delete)
    assert phone["calendar"] == %{"name" => "Work", "account" => "Google"}
    assert {422, _} = as(ctx, :h, :get, "/api/v1/risi/items?kinds=nope")

    # The schedule answer merges them too (the phone's own event with its title).
    hijacking_model!()
    ask!(ctx, "what's on Monday 12 Oct")
    {_, body, _} = answer_of(posts())
    assert body =~ "- 10:00–11:00 · Interview with Shenika (Risi Calendar)"
    assert body =~ "- 14:00–15:00 · Call with Kamal (added by Risi to Work)"
  end

  ## 6. The name check

  defp add_model!(name) do
    fake_llm!(fn _n, body ->
      q = question(body)

      if String.starts_with?(q, "add a meeting"),
        do: %{
          "tool" => "risi_calendar_add",
          "args" => %{"title" => "Meeting with #{name}", "start" => "2026-10-13T04:30:00Z"}
        },
        else: final("?")
    end)
  end

  test "'Shutazi' asks 'Did you mean Shirazi?' before any card; Use / Keep continue the write",
       ctx do
    add_model!("Shutazi")
    ask!(ctx, "add a meeting with Shutazi Tuesday 10am")
    ps = posts()
    refute card_of(ps)
    {_, body, a} = answer_of(ps)
    assert body == "Did you mean Shirazi?"

    assert a["next_actions"] == [
             %{"label" => "Shirazi", "action" => "ask", "text" => "Use Shirazi"},
             %{"label" => "Keep \"Shutazi\"", "action" => "ask", "text" => "Keep Shutazi"}
           ]

    assert %{"about" => "name", "said" => "Shutazi", "keep" => true, "write_id" => wid} =
             a["clarify"]

    assert [%{"name" => "Shirazi", "user_id" => sid}] = a["clarify"]["options"]
    assert sid == ctx.shirazi.user.id

    n = length(llm_requests())
    ask!(ctx, "Use Shirazi")
    card = card_of(posts())
    assert card["write_id"] == wid
    assert card["text"] =~ "Meeting with Shirazi"
    assert length(llm_requests()) == n

    # Keep: the name as said.
    ask!(ctx, "add a meeting with Shutazi Tuesday 10am")
    {_, _, a} = answer_of(posts())
    wid2 = a["clarify"]["write_id"]
    ask!(ctx, "keep shutazi")
    card = card_of(posts())
    assert card["write_id"] == wid2 and card["text"] =~ "Meeting with Shutazi"
  end

  test "an exact name or one with no close match goes straight to the card", ctx do
    for name <- ["Shirazi", "Zebediah"] do
      add_model!(name)
      ask!(ctx, "add a meeting with #{name} Tuesday 10am")
      ps = posts()
      assert card_of(ps)["text"] =~ "Meeting with #{name}"
      refute answer_of(ps) |> elem(2) |> Map.has_key?("clarify")
    end
  end
end
