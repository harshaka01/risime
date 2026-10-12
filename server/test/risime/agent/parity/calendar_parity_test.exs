defmodule RisiMe.Agent.Parity.CalendarTest do
  @moduledoc """
  Deep agents step 0: parity pins for Risi Calendar as it is today (§29, fixed clock Fri 9 Oct
  2026 10:30 Colombo, fake `risi-l1`): an ask whose `final` carries an event draft → the
  `risi_calendar_add` confirm card (exact fields) → [Add] creates the event with no model call;
  and a model-chosen `risi_calendar_check` → the honest "Checked:" answer.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.{Calendar, TurnSteps}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :risi_parity
  @moduletag capture_log: true

  @bound_ms 3_000

  setup do
    calendar_world!()
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  defp schema_names,
    do: Enum.map(llm_requests(), &get_in(&1, ~w(response_format json_schema name)))

  defp timed(fun) do
    t = System.monotonic_time(:millisecond)
    r = fun.()
    {System.monotonic_time(:millisecond) - t, r}
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
    {ms, res} = timed(fn -> perform_job(Job, job.args) end)
    assert res == :ok
    assert ms < @bound_ms
    rid
  end

  defp act!(ctx, action, target) do
    id =
      envelope!(
        ctx.hrc,
        ctx.harsha.user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => nil
        },
        ctx.hd
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    {ms, res} = timed(fn -> perform_job(Job, job.args) end)
    assert res == :ok
    assert ms < @bound_ms
  end

  defp final(answer, draft \\ nil) do
    f = %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}
    if draft, do: Map.put(f, "draft", draft), else: f
  end

  defp tool_enum(req) do
    get_in(req, ["response_format", "json_schema", "schema", "anyOf"])
    |> Enum.flat_map(&get_in(&1, ["properties", "tool", "enum"]))
  end

  defp answer_of(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)

  defp events_of(user) do
    {:ok, %{"events" => evs}} =
      Calendar.list(user, "2026-10-09T00:00:00Z", "2026-10-31T00:00:00Z")

    evs
  end

  test "an event draft becomes the risi_calendar_add confirm card; [Add] creates it with no model call",
       ctx do
    fake_llm!(fn _name, _body ->
      final("Sure, Monday 12 Oct at 2 PM.", %{
        "kind" => "event",
        "title" => "Interview",
        "date" => "Monday 12 Oct",
        "time" => "2pm",
        "with" => ["Shenika"]
      })
    end)

    rid = ask!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm")
    ps = posts()
    # The card first, then the answer, both in the asker's Risi chat.
    assert Enum.map(ps, fn {c, _, r} -> {c, r["kind"]} end) ==
             [{ctx.hrc, "confirm"}, {ctx.hrc, "answer"}]

    [{_, card_body, _} | _] = ps

    assert card_body ==
             "Add to your Risi Calendar: Interview · Mon 12 Oct, 2–3 PM · with Shenika? Update RisiMe to answer."

    {_, abody, _} = answer_of(ps)
    assert abody == "Tap Add on the card to put it in your Risi Calendar, or Cancel."
    assert [card] = of_kind(ps, "confirm")

    assert keys(card) ==
             Enum.sort(
               ~w(args buttons calendar call_ref expires_at for item_id kind made_by notify origin
                  request_id skill_id summary text tool turn_ref v when write_id)
             )

    assert card["text"] == "Interview" and card["notify"] == [ctx.h]
    assert card["calendar"] == nil and card["origin"] == nil and card["skill_id"] == nil
    assert card["item_id"] == nil and is_binary(card["expires_at"])
    assert card["made_by"]["model"] == "risi-l1" and is_binary(card["call_ref"])

    assert card["when"] == %{
             "all_day" => false,
             "start" => "2026-10-12T08:30:00.000Z",
             "end" => "2026-10-12T09:30:00.000Z"
           }

    {_, _, a} = answer_of(ps)
    assert a["steps"] == [%{"tool" => "risi_calendar_add", "status" => "ok"}]
    assert a["turn_ref"] == card["turn_ref"] and a["call_ref"] == card["call_ref"]

    assert card["tool"] == "risi_calendar_add" and card["request_id"] == rid
    assert card["buttons"] == ["add", "edit", "cancel"]
    assert card["for"] == [ctx.h]

    assert card["summary"] ==
             "Add to your Risi Calendar: Interview · Mon 12 Oct, 2–3 PM · with Shenika"

    assert card["args"] == %{
             "title" => "Interview",
             "start" => "2026-10-12T08:30:00.000Z",
             "end" => "2026-10-12T09:30:00.000Z",
             "all_day" => false,
             "tz" => "Asia/Colombo",
             "with" => [ctx.s],
             "reminder_min" => 30,
             "notes" => nil,
             "source" => %{"conversation_id" => ctx.hrc, "message_ids" => [], "item_id" => nil}
           }

    assert schema_names() == ["risi_next_action"]

    assert Enum.map(TurnSteps.list(card["turn_ref"]), &{&1.tool, &1.status}) ==
             [{"risi_calendar_add", "ok"}, {"final", "ok"}]

    assert events_of(ctx.h) == []

    # [Add]: the event is made at once, no model call, the event card in the Risi chat.
    act!(ctx, "confirm_write", card["write_id"])
    assert length(llm_requests()) == 1
    ps = posts()
    assert [{rc, body, ec}] = for({cv, b, %{"kind" => "event_card"} = r} <- ps, do: {cv, b, r})
    assert rc == ctx.hrc and ec["mode"] == "added"
    assert body == "Added to your Risi Calendar: Interview · Mon 12 Oct, 2–3 PM · with Shenika."

    assert keys(ec) ==
             Enum.sort(
               ~w(v kind mode event_id version title start end all_day tz owner participants
                  source_conversation_id item_id buttons made_by notify call_ref)
             )

    assert [{src, _, _}] = for({cv, b, %{"kind" => "calendar_invite"} = r} <- ps, do: {cv, b, r})
    assert src == ctx.src

    assert [e] = events_of(ctx.h)
    assert e["title"] == "Interview" and e["start"] == "2026-10-12T08:30:00.000Z"
    assert e["my_status"] == "accepted" and e["my_reminder_min"] == 30
  end

  test "a model-chosen risi_calendar_check: two model calls, the 'Checked:' line, the source",
       ctx do
    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: final("Nothing much on Monday afternoon."),
        else: %{
          "tool" => "risi_calendar_check",
          "args" => %{"from" => "2026-10-12T08:00:00Z", "to" => "2026-10-12T10:00:00Z"}
        }
    end)

    ask!(ctx, "Monday 2pm ok for the call?")
    {conv, body, a} = answer_of(posts())
    assert conv == ctx.hrc
    assert body == "Nothing much on Monday afternoon.\n\nChecked: Risi Calendar."
    assert a["steps"] == [%{"tool" => "risi_calendar_check", "status" => "ok"}]
    assert a["made_by"]["model"] == "risi-l1" and a["refs"] == []

    assert a["sources"] == [
             %{
               "type" => "calendar_source",
               "source" => "risi_calendar",
               "names" => ["Risi Calendar"],
               "read_ok" => true,
               "reason" => nil
             }
           ]

    [first, second] = llm_requests()
    assert "risi_calendar_check" in tool_enum(first) and "risi_calendar_add" in tool_enum(first)
    refute "calendar_check" in tool_enum(first)
    result = Jason.decode!(List.last(second["messages"])["content"])
    assert result["ok"] == true and result["result"]["source"] == "risi_calendar"
    assert schema_names() == ["risi_next_action", "risi_next_action"]
  end
end
