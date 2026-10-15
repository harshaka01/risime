defmodule RisiMe.Agent.Parity.MemoryTest do
  @moduledoc """
  Deep agents step 0: parity pins for Risi memory as it is today (§35, fixed clock Fri 9 Oct
  2026 10:30 Colombo, fake `risi-l1`): the router forces `summarise_range` / `memory_search` as
  step 1 and the answer cites `memory` sources; with nothing stored the server answers the
  honest "I don't have anything about that…" itself, with no model call.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.Memory
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :risi_parity
  @moduletag capture_log: true

  @bound_ms 3_000

  setup do
    ctx = calendar_world!()
    upali = RisiMe.Fixtures.logged_in_user(display_name: "Upali")
    ou = risi_chat!([ctx.harsha.user, upali.user])
    Map.merge(ctx, %{upali: upali, ou: ou})
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  defp remember!(conv, user, text, at) do
    id = say!(conv, user, text, at)
    pt = Jason.encode!(%{"v" => 1, "type" => "text", "body" => text})
    :ok = Memory.put_message(conv, %{message_id: id, sender_id: user.id, plaintext: pt})
    id
  end

  defp at(day, hm \\ "09:00"),
    do: RisiMe.Agent.Clock.to_utc(NaiveDateTime.from_iso8601!("#{day}T#{hm}:00"), "Asia/Colombo")

  defp final(answer, sources),
    do: %{"tool" => "final", "answer" => answer, "sources" => sources, "next_steps" => []}

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
    t = System.monotonic_time(:millisecond)
    assert :ok = perform_job(Job, job.args)
    assert System.monotonic_time(:millisecond) - t < @bound_ms
    {conv, body, a} = Enum.find(posts(), fn {_, _, r} -> r["kind"] == "answer" end)
    assert conv == ctx.hrc
    {body, a}
  end

  defp tool_msg(req) do
    req["messages"]
    |> Enum.find(&(&1["role"] == "assistant"))
    |> Map.fetch!("content")
    |> Jason.decode!()
  end

  defp result_of(req) do
    req["messages"]
    |> Enum.filter(&(&1["role"] == "user"))
    |> Enum.at(1)
    |> Map.fetch!("content")
    |> Jason.decode!()
    |> Map.fetch!("result")
  end

  defp schema_names,
    do: Enum.map(llm_requests(), &get_in(&1, ~w(response_format json_schema name)))

  test "'summarise the work done this month': summarise_range forced, one call, memory sources",
       ctx do
    remember!(ctx.og, ctx.shenika.user, "Old September talk", at("2026-09-28"))
    q = remember!(ctx.og, ctx.harsha.user, "Revised quote agreed with Shenika", at("2026-10-05"))
    remember!(ctx.og, ctx.shenika.user, "Site visit Thursday confirmed", at("2026-10-08"))

    fake_llm!(fn "risi_next_action", body ->
      [r1 | _] = result_of(body)["messages"]
      final("This month: the revised quote was agreed and the site visit confirmed.", [r1["ref"]])
    end)

    {body, a} = ask!(ctx, "summarise the work done this month")
    assert body == "This month: the revised quote was agreed and the site visit confirmed."
    assert a["steps"] == [%{"tool" => "summarise_range", "status" => "ok"}]
    assert a["made_by"]["model"] == "risi-l1"

    assert [%{"type" => "memory", "conversation_id" => og, "message_ids" => [^q]} = src] =
             a["sources"]

    assert og == ctx.og
    assert src["at"] == "2026-10-05T03:30:00.000Z"
    assert keys(src) == ~w(at conversation_id message_ids type)

    assert keys(a) ==
             ~w(answer call_ref confidence kind made_by next_steps notify refs request_id sources
                steps turn_ref v)

    assert schema_names() == ["risi_next_action"]
    [req] = llm_requests()

    assert %{
             "tool" => "summarise_range",
             "args" => %{"range" => %{"from" => "2026-10-01", "to" => "2026-10-09"}}
           } = tool_msg(req)

    msgs = result_of(req)["messages"]

    assert Enum.map(msgs, & &1["text"]) == [
             "Revised quote agreed with Shenika",
             "Site visit Thursday confirmed"
           ]

    assert hd(msgs)["chat"] == "chat with Shenika"
  end

  test "'what did I agree with Upali last week': memory_search with people and range", ctx do
    u =
      remember!(ctx.ou, ctx.upali.user, "Agreed: the price is 40,000 per unit", at("2026-10-01"))

    remember!(ctx.og, ctx.shenika.user, "The price for Shenika's job is 10,000", at("2026-10-01"))

    fake_llm!(fn "risi_next_action", body ->
      msgs = result_of(body)["messages"]
      final("You agreed 40,000 per unit with Upali.", [hd(msgs)["ref"]])
    end)

    {body, a} = ask!(ctx, "what did I agree with Upali last week")
    # Memory starts after the range start: the honest line is added.
    assert body ==
             "You agreed 40,000 per unit with Upali.\n\n" <>
               "My memory of your Official chats starts on 1 Oct, so I don't know what was said before that."

    assert a["steps"] == [%{"tool" => "memory_search", "status" => "ok"}]
    assert [%{"type" => "memory", "conversation_id" => ou, "message_ids" => [^u]}] = a["sources"]
    assert ou == ctx.ou

    [req] = llm_requests()

    assert %{
             "tool" => "memory_search",
             "args" => %{
               "people" => ["Upali"],
               "range" => %{"from" => "2026-09-28", "to" => "2026-10-04"}
             }
           } = tool_msg(req)

    # Only the chats with Upali reached the model.
    assert Enum.map(result_of(req)["messages"], & &1["text"]) ==
             ["Agreed: the price is 40,000 per unit"]
  end

  test "nothing stored: the server's honest answer, no model call", ctx do
    fake_llm!(fn _, _ -> flunk("no model call") end)
    {body, a} = ask!(ctx, "what did I agree with Upali last week")

    assert body ==
             "I've only just started remembering your Official chats, so I don't have anything yet."

    assert a["answer"] == body and a["call_ref"] == nil and a["confidence"] == 1.0
    assert a["steps"] == [%{"tool" => "memory_search", "status" => "ok"}]
    assert a["sources"] == [] and a["refs"] == [] and a["next_actions"] == []

    assert a["made_by"] == %{
             "also" => [],
             "at" => "2026-10-09T05:00:00.000Z",
             "model" => nil,
             "provider" => "risime"
           }

    assert llm_requests() == []
  end

  test "P0 15 Oct: 'what did I promise?' with no chat memory answers from the Ledger", ctx do
    fake_llm!(fn _, _ -> flunk("no model call") end)
    {body, _} = ask!(ctx, "what did I promise?")
    assert body == "You have no open promises in my list."

    {:ok, c} =
      RisiMe.Agent.Commitment.seal(%RisiMe.Agent.Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: ctx.ou,
        chat_id: ctx.ou,
        state: "confirmed",
        item_state: "confirmed",
        text: "Send the price list",
        owner_id: ctx.harsha.user.id,
        counterpart_ids: [ctx.upali.user.id],
        due: ~U[2026-10-10 08:30:00.000000Z],
        due_kind: "datetime",
        all_day: false,
        source: "chat",
        source_message_ids: [],
        proposed_at: ~U[2026-10-09 05:00:00.000000Z]
      })

    RisiMe.Repo.insert!(c)

    # With a promise in the Ledger the memory route finds it, and the model answers from it.
    fake_llm!(fn _, _ -> final("Your open promises:\n- You: Send the price list", []) end)

    q = "We see but have I promised you should be able to read what I promised and let me know"
    {body2, _} = ask!(ctx, q)
    assert body2 =~ "Your open promises:"
    assert body2 =~ "Send the price list"
    refute body2 =~ "only just started"
  end

  test "nothing about that (memory exists): 'I don't have anything about that…', no model call",
       ctx do
    remember!(ctx.og, ctx.shenika.user, "Quote sent", at("2026-10-07"))
    fake_llm!(fn _, _ -> flunk("no model call") end)
    {body, a} = ask!(ctx, "what did I agree with Upali last week")

    assert body ==
             "I don't have anything about that in my memory of your Official chats.\n\n" <>
               "My memory of your Official chats starts on 7 Oct, so I don't know what was said before that."

    assert a["steps"] == [%{"tool" => "memory_search", "status" => "ok"}]
    assert a["sources"] == [] and a["made_by"]["model"] == nil
    assert llm_requests() == []
  end
end
