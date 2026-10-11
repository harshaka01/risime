defmodule RisiMe.Agent.MemoryTest do
  @moduledoc """
  v1.36 §35 (decision 077, Risi Brain v2 step 2), with a fixed clock (Fri 9 Oct 2026, 10:30
  Colombo) and a scripted fake model:

    1. storage: Official texts and Risi's posts are kept sealed (never the Risi chat), with a
       vector; opt-outs are dropped before sealing;
    2. the §35.3 REST: delete per chat / everything (X-Confirm), learning PUT/GET;
    3. §15 deletes and Official off remove memory; items appear in (and go with) facts;
    4. the ingest job stores sealed items with resolved people;
    5. the router forces `summarise_range` / `memory_search` as step 1 (en, si, ta), cites
       `memory` sources, answers "nothing" itself, adds the honest "memory starts" line, and
       map-reduces a long range;
    6. a Sinhala/Tamil question answered in English is rewritten once in its own script.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.{Memory, MemoryIngest}
  alias RisiMe.Agent.Memory.{Item, Message}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    ctx = calendar_world!()
    upali = RisiMe.Fixtures.logged_in_user(display_name: "Upali")
    # Harsha and Upali's own Official chat (Risi a member).
    ou = risi_chat!([ctx.harsha.user, upali.user])
    Map.merge(ctx, %{upali: upali, ou: ou})
  end

  ## Helpers

  # A member's Official message as Agent.Conversation hands it over (buffer + memory).
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
    assert :ok = perform_job(Job, job.args)
    {_, body, a} = Enum.find(posts(), fn {_, _, r} -> r["kind"] == "answer" end)
    {body, a}
  end

  defp tool_msg(req, n \\ 0) do
    req["messages"]
    |> Enum.filter(&(&1["role"] == "assistant"))
    |> Enum.at(n)
    |> Map.fetch!("content")
    |> Jason.decode!()
  end

  defp result_of(req, n \\ 0) do
    req["messages"]
    |> Enum.filter(&(&1["role"] == "user"))
    |> Enum.at(n + 1)
    |> Map.fetch!("content")
    |> Jason.decode!()
    |> Map.fetch!("result")
  end

  defp count(schema), do: Repo.aggregate(schema, :count)

  defp vectors, do: Repo.query!("SELECT count(*) FROM risi_memory_vectors").rows |> hd() |> hd()

  ## 1. Storage

  test "Official texts are stored sealed with a vector; the Risi chat and other envelopes never",
       ctx do
    canary = "CANARY" <> String.replace(Ecto.UUID.generate(), "-", "")
    remember!(ctx.og, ctx.shenika.user, "the #{canary} quote goes out Friday", at("2026-10-08"))

    # The Risi chat and a non-text envelope are not memory.
    id = say!(ctx.hrc, ctx.harsha.user, "private to Risi #{canary}")

    Memory.put_message(ctx.hrc, %{
      message_id: id,
      sender_id: ctx.h,
      plaintext: Jason.encode!(%{"type" => "text", "body" => "x"})
    })

    Memory.put_message(ctx.og, %{
      message_id: RisiMe.TimeUUID.generate(),
      sender_id: ctx.h,
      plaintext: Jason.encode!(%{"type" => "risi_request", "text" => "hi"})
    })

    assert [m] = Repo.all(Message)
    assert m.conversation_id == ctx.og and m.sender_id == ctx.s and not m.from_risi
    refute inspect(Repo.query!("SELECT * FROM risi_memory_messages").rows) =~ canary
    assert vectors() == 1
    assert [%{text: "the " <> _}] = Memory.messages([ctx.og], nil, nil)
    # The ingest job of the chat is scheduled (ids only).
    assert [%{args: %{"kind" => "memory_ingest", "conv" => conv}}] =
             all_enqueued(worker: Job) |> Enum.filter(&(&1.args["kind"] == "memory_ingest"))

    assert conv == ctx.og
  end

  test "Risi's own posts in an Official chat are kept (never in a Risi chat)", ctx do
    {:ok, _} =
      RisiMe.Agent.Out.post(ctx.og, "Summary: the quote is agreed.", %{
        "kind" => "summary",
        "notify" => [ctx.h]
      })

    {:ok, _} = RisiMe.Agent.Out.post(ctx.hrc, "Just for you", %{"kind" => "answer"})
    assert [m] = Repo.all(Message)
    assert m.from_risi and m.addressed_to == ctx.h and m.conversation_id == ctx.og
  end

  test "a participant who stopped Risi learning is dropped before sealing (chat, then everywhere)",
       ctx do
    {204, nil} =
      as(ctx, :s, :put, "/api/v1/risi/learning", %{
        "conversation_id" => ctx.og,
        "enabled" => false
      })

    remember!(ctx.og, ctx.shenika.user, "not kept", at("2026-10-08"))
    remember!(ctx.og, ctx.harsha.user, "kept", at("2026-10-08", "10:00"))
    assert [%{sender_id: h}] = Repo.all(Message)
    assert h == ctx.h

    {200, %{"global" => true, "chats" => [%{"conversation_id" => c, "enabled" => false}]}} =
      as(ctx, :s, :get, "/api/v1/risi/learning")

    assert c == ctx.og

    {204, nil} =
      as(ctx, :h, :put, "/api/v1/risi/learning", %{"conversation_id" => nil, "enabled" => false})

    remember!(ctx.ou, ctx.harsha.user, "also not kept", at("2026-10-08", "11:00"))
    assert count(Message) == 1
    {200, %{"global" => false, "chats" => []}} = as(ctx, :h, :get, "/api/v1/risi/learning")

    {422, _} =
      as(ctx, :h, :put, "/api/v1/risi/learning", %{"conversation_id" => "x", "enabled" => false})

    {422, _} = as(ctx, :h, :put, "/api/v1/risi/learning", %{"enabled" => "no"})
  end

  ## 2. Deletion

  test "DELETE /risi/memory per chat removes only the caller's rows, posts to them and items",
       ctx do
    mine = remember!(ctx.og, ctx.harsha.user, "I'll send the quote", at("2026-10-08"))
    remember!(ctx.og, ctx.shenika.user, "Thanks", at("2026-10-08", "09:05"))
    remember!(ctx.ou, ctx.harsha.user, "Upali chat", at("2026-10-08", "09:10"))

    {:ok, _} =
      Memory.put_item(ctx.og, %{
        kind: "promise",
        text: "Harsha sends the quote",
        source_message_ids: [mine]
      })

    {:ok, _} =
      RisiMe.Agent.Out.post(ctx.og, "Noted for Harsha", %{"kind" => "answer", "notify" => [ctx.h]})

    assert count(Message) == 4 and count(Item) == 1 and vectors() == 5

    {204, nil} = as(ctx, :h, :delete, "/api/v1/risi/memory?conversation_id=#{ctx.og}")
    left = Repo.all(Message)
    assert Enum.map(left, & &1.sender_id) |> Enum.sort() == Enum.sort([ctx.s, ctx.h])
    assert Enum.find(left, &(&1.conversation_id == ctx.ou))
    assert count(Item) == 0 and vectors() == 2

    # Everything needs the confirm header.
    {400, _} = as(ctx, :h, :delete, "/api/v1/risi/memory")
    {422, _} = as(ctx, :h, :delete, "/api/v1/risi/memory?conversation_id=nope")

    conn =
      Phoenix.ConnTest.build_conn()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> ctx.harsha.token)
      |> Plug.Conn.put_req_header("x-confirm", "delete-all")
      |> Phoenix.ConnTest.dispatch(RisiMeWeb.Endpoint, :delete, "/api/v1/risi/memory")

    assert conn.status == 204
    assert [%{sender_id: s}] = Repo.all(Message)
    assert s == ctx.s
  end

  test "§15 delete for everyone and Official off remove memory; items show and go with facts",
       ctx do
    a = remember!(ctx.og, ctx.shenika.user, "Meeting Thursday 3pm with Harsha", at("2026-10-07"))
    remember!(ctx.og, ctx.harsha.user, "ok", at("2026-10-07", "09:01"))

    {:ok, item} =
      Memory.put_item(ctx.og, %{
        kind: "meeting",
        text: "Shenika and Harsha meet Thursday 3pm",
        people_ids: [ctx.h, ctx.s],
        source_message_ids: [a]
      })

    {200, %{"facts" => facts}} = as(ctx, :h, :get, "/api/v1/risi/facts")

    assert %{"kind" => "meeting", "text" => "Shenika and Harsha meet Thursday 3pm"} =
             Enum.find(facts, &(&1["fact_id"] == item))

    {204, nil} = as(ctx, :h, :delete, "/api/v1/risi/facts/#{item}")
    assert count(Item) == 0 and vectors() == 2

    RisiMe.Agent.Secretary.message_deleted(ctx.og, [a])
    assert count(Message) == 1

    RisiMe.Agent.Secretary.forget(ctx.og)
    assert count(Message) == 0 and vectors() == 0
  end

  ## 3. The ingest job

  test "the ingest job stores sealed items with people, sources and vectors", ctx do
    a =
      remember!(
        ctx.og,
        ctx.shenika.user,
        "I'll send the revised quote by Monday",
        at("2026-10-08")
      )

    b =
      remember!(ctx.og, ctx.harsha.user, "Agreed, site visit Thursday", at("2026-10-08", "09:02"))

    fake_llm!(fn "memory_extract", body ->
      user = Enum.find(body["messages"], &(&1["role"] == "user"))["content"]
      assert user =~ "revised quote" and user =~ "\"from\":\"Shenika\""

      %{
        "items" => [
          %{
            "kind" => "promise",
            "text" => "Shenika sends the revised quote by Monday",
            "people" => ["Shenika"],
            "project" => "Quote",
            "at" => "2026-10-12",
            "refs" => ["m1"]
          },
          %{
            "kind" => "decision",
            "text" => "Site visit on Thursday",
            "people" => ["Harsha", "Shenika"],
            "project" => nil,
            "at" => nil,
            "refs" => ["m2"]
          },
          # No source: never stored.
          %{
            "kind" => "decision",
            "text" => "Invented",
            "people" => [],
            "project" => nil,
            "at" => nil,
            "refs" => []
          }
        ]
      }
    end)

    assert :ok = perform_job(Job, %{"kind" => "memory_ingest", "conv" => ctx.og})
    items = Repo.all(Item) |> Enum.map(&elem(Memory.open_item(&1), 1)) |> Enum.sort_by(& &1.kind)
    assert [%{kind: "decision"} = d, %{kind: "promise"} = p] = items
    assert d.source_message_ids == [b] and Enum.sort(d.people_ids) == Enum.sort([ctx.h, ctx.s])
    assert p.source_message_ids == [a] and p.people_ids == [ctx.s] and p.project == "Quote"
    refute inspect(Repo.query!("SELECT * FROM risi_memory_items").rows) =~ "revised quote"
    assert vectors() == 4

    # The cursor moved: nothing new, no model call.
    n = length(llm_requests())
    assert :ok = perform_job(Job, %{"kind" => "memory_ingest", "conv" => ctx.og})
    assert length(llm_requests()) == n
  end

  ## 4. Retrieval through the router

  test "'summarise the work done this month' forces summarise_range and cites memory sources",
       ctx do
    remember!(ctx.og, ctx.shenika.user, "Old September talk", at("2026-09-28"))
    q = remember!(ctx.og, ctx.harsha.user, "Revised quote agreed with Shenika", at("2026-10-05"))
    remember!(ctx.og, ctx.shenika.user, "Site visit Thursday confirmed", at("2026-10-08"))

    fake_llm!(fn "risi_next_action", body ->
      res = result_of(body)
      [r1 | _] = res["messages"]

      assert r1["text"] == "Revised quote agreed with Shenika" and
               r1["chat"] == "chat with Shenika"

      refute Enum.any?(res["messages"], &(&1["text"] =~ "September"))
      final("This month: the revised quote was agreed and the site visit confirmed.", [r1["ref"]])
    end)

    {body, a} = ask!(ctx, "summarise the work done this month")
    assert [%{"tool" => "summarise_range", "status" => "ok"}] = a["steps"]
    assert body == "This month: the revised quote was agreed and the site visit confirmed."

    assert [%{"type" => "memory", "conversation_id" => og, "message_ids" => [^q], "at" => _}] =
             a["sources"]

    assert og == ctx.og

    [req] = llm_requests()

    assert %{
             "tool" => "summarise_range",
             "args" => %{"range" => %{"from" => "2026-10-01", "to" => "2026-10-09"}}
           } =
             tool_msg(req)

    # No write tool after the forced read.
    enum =
      get_in(req, ["response_format", "json_schema", "schema", "anyOf"])
      |> Enum.flat_map(&get_in(&1, ["properties", "tool", "enum"]))

    refute "set_reminder" in enum or "risi_calendar_add" in enum
  end

  test "memory that starts after the range start says so honestly; nothing found is answered by the server",
       ctx do
    remember!(ctx.og, ctx.shenika.user, "Quote sent", at("2026-10-07"))

    fake_llm!(fn "risi_next_action", body ->
      final("The quote was sent.", [hd(result_of(body)["messages"])["ref"]])
    end)

    {body, _a} = ask!(ctx, "summarise the work done this month")

    assert body ==
             "The quote was sent.\n\nMy memory of your Official chats starts on 7 Oct, so I don't know what was said before that."

    # Nothing about Upali last week: the server answers, no model call.
    fake_llm!(fn _, _ -> flunk("no model call") end)
    {body, a} = ask!(ctx, "what did I agree with Upali last week")

    assert body ==
             "I don't have anything about that in my memory of your Official chats.\n\n" <>
               "My memory of your Official chats starts on 7 Oct, so I don't know what was said before that."

    assert [%{"tool" => "memory_search", "status" => "ok"}] = a["steps"]
    assert a["made_by"]["model"] == nil
  end

  test "'what did I agree with Upali last week' searches only chats with Upali; 'pending with Shenika' only hers",
       ctx do
    u =
      remember!(ctx.ou, ctx.upali.user, "Agreed: the price is 40,000 per unit", at("2026-10-01"))

    remember!(ctx.og, ctx.shenika.user, "The price for Shenika's job is 10,000", at("2026-10-01"))

    fake_llm!(fn "risi_next_action", body ->
      msgs = result_of(body)["messages"]
      assert Enum.map(msgs, & &1["text"]) == ["Agreed: the price is 40,000 per unit"]
      final("You agreed 40,000 per unit with Upali.", [hd(msgs)["ref"]])
    end)

    {body, a} = ask!(ctx, "what did I agree with Upali last week")
    assert body =~ "40,000"
    assert [%{"type" => "memory", "message_ids" => [^u]}] = a["sources"]
    [req] = llm_requests()

    assert %{
             "tool" => "memory_search",
             "args" => %{
               "people" => ["Upali"],
               "range" => %{"from" => "2026-09-28", "to" => "2026-10-04"}
             }
           } = tool_msg(req)

    fake_llm!(fn "risi_next_action", body ->
      res = result_of(body)
      assert Enum.map(res["messages"], & &1["text"]) == ["The price for Shenika's job is 10,000"]
      assert res["promises"] == []
      final("Shenika's price of 10,000 is noted; nothing else is open.", [])
    end)

    {_body, a} = ask!(ctx, "what's pending with Shenika")
    [req] = llm_requests()
    assert %{"args" => %{"kinds" => kinds, "range" => nil}} = tool_msg(req)
    assert "promise" in kinds
    # The model cited nothing: the chats it was given are the sources.
    assert [%{"type" => "memory", "conversation_id" => og}] = a["sources"]
    assert og == ctx.og
  end

  test "a long range is map-reduced: chunks become cited points, the final cites them", ctx do
    remember!(ctx.og, ctx.shenika.user, "before the month", at("2026-09-28"))

    for i <- 1..60 do
      remember!(
        ctx.og,
        if(rem(i, 2) == 0, do: ctx.shenika.user, else: ctx.harsha.user),
        "Work log #{i}: " <> String.duplicate("installed panels and checked wiring ", 8),
        DateTime.add(at("2026-10-02"), i * 600, :second)
      )
    end

    fake_llm!(fn
      "memory_map", body ->
        user = List.last(body["messages"])["content"]
        [_, ref] = Regex.run(~r/"ref":"(r\d+)"/, user)
        %{"points" => [%{"text" => "Panels installed", "refs" => [ref, "r9999"]}]}

      "risi_next_action", body ->
        res = result_of(body)
        assert res["messages_read"] == 60 and res["points"] != []
        assert Enum.all?(res["points"], &(length(&1["refs"]) == 1))
        final("Panels were installed all week.", [hd(hd(res["points"])["refs"])])
    end)

    {body, a} = ask!(ctx, "summarise the work done this month")
    assert body == "Panels were installed all week."
    assert [%{"type" => "memory"}] = a["sources"]

    maps =
      Enum.count(
        llm_requests(),
        &(get_in(&1, ["response_format", "json_schema", "name"]) == "memory_map")
      )

    assert maps >= 2
  end

  ## 5. Language

  test "a Sinhala memory question answered in English is rewritten once in Sinhala script", ctx do
    remember!(ctx.og, ctx.shenika.user, "Quote එක යැව්වා", at("2026-10-05"))

    fake_llm!(fn "risi_next_action", body ->
      sys = hd(body["messages"])["content"]
      assert sys =~ "Sinhala script"
      ref = hd(result_of(body)["messages"])["ref"]

      if Enum.count(body["messages"], &(&1["role"] == "assistant")) == 1,
        do: final("The quote was sent.", [ref]),
        else: final("Quote එක යැව්වා.", [ref])
    end)

    {body, a} = ask!(ctx, "මේ මාසේ කරපු වැඩ සාරාංශ කරන්න")
    # The honest line is the server's own, in Sinhala too.
    assert body ==
             "Quote එක යැව්වා.\n\nමගේ මතකය ආරම්භ වුණේ 5 Oct සිටයි; ඊට පෙර කතා කළ දේවල් මම දන්නේ නැහැ."

    assert [%{"tool" => "summarise_range"}] = a["steps"]
    assert length(llm_requests()) == 2
  end

  test "an answer that stops after its lead-in (\"Here is a summary:\") is asked again once",
       ctx do
    remember!(ctx.og, ctx.shenika.user, "Panels installed", at("2026-10-05"))
    remember!(ctx.og, ctx.shenika.user, "Kick-off", at("2026-09-30"))

    fake_llm!(fn "risi_next_action", body ->
      ref = hd(result_of(body)["messages"])["ref"]

      if Enum.count(body["messages"], &(&1["role"] == "assistant")) == 1,
        do: final("Here is a summary of the work done this month:", [ref]),
        else: final("Here is a summary:\n- Panels installed (5 Oct).", [ref])
    end)

    {body, _a} = ask!(ctx, "summarise the work done this month")
    assert body == "Here is a summary:\n- Panels installed (5 Oct)."

    assert List.last(llm_requests())["messages"] |> List.last() |> Map.get("content") =~
             "WHOLE answer"
  end

  test "a Tamil question with nothing found gets the server's Tamil answer", ctx do
    remember!(ctx.og, ctx.shenika.user, "unrelated", at("2026-09-20"))
    fake_llm!(fn _, _ -> flunk("no model call") end)
    {body, _a} = ask!(ctx, "கடந்த வாரம் Upali உடன் நான் என்ன ஒப்புக்கொண்டேன்?")
    assert body =~ "எனக்கு எதுவும் நினைவில் இல்லை"
  end

  test "Memory.seed copies the 24-h buffer once and skips opted-out senders", ctx do
    say!(ctx.og, ctx.shenika.user, "buffered before memory")
    say!(ctx.og, ctx.harsha.user, "mine, opted out")
    :ok = Memory.set_learning(ctx.h, nil, false)
    :ok = Memory.seed_all()
    assert [%{sender_id: s}] = Repo.all(Message)
    assert s == ctx.s
    :ok = Memory.seed(ctx.og)
    assert count(Message) == 1
  end

  test "ingest is scheduled once per chat and needs no text in its args", ctx do
    remember!(ctx.og, ctx.shenika.user, "one", at("2026-10-05"))
    remember!(ctx.og, ctx.shenika.user, "two", at("2026-10-05", "09:01"))
    jobs = Enum.filter(all_enqueued(worker: Job), &(&1.args["kind"] == "memory_ingest"))
    assert [%{args: args}] = jobs
    assert Map.keys(args) |> Enum.sort() == ["conv", "kind"]

    assert MemoryIngest.resolve(["shenika", "Zed"], RisiMe.Agent.Secretary.members(ctx.og)) == [
             ctx.s
           ]
  end
end
