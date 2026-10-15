defmodule RisiMe.Agent.CallSummaryLongCallTest do
  @moduledoc """
  P0 2026-10-15 (docs/status/incident-2026-10-14-call-summary.md, "15 Oct"): a long Official call
  with a colleague got its action items but no summary. With RISI_ACTION_ITEMS on, a transcribed
  call with items now gets its discussion summary card (one line and key points) **and** the
  `action_items` card; a long transcript is summarised in sections (map-reduce,
  `CallTranscribe.Sections`) and posts one summary; a failed section still posts, with one line
  naming the part left out. The action-items-off path (`Ledger.call_ended`) is sectioned the
  same way.

  The 30-minute calls are synthetic: 150 buffered `call_seg` rows (12 s each, both speakers)
  written straight into the sealed buffer, as `CallTranscribe.Call` buffers them, so the test
  runs in about a second.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.CallTranscribeHelpers
  import RisiMe.RisiHelpers, only: [fake_llm!: 1, llm_requests: 0, ref_of: 2]

  alias RisiMe.Agent.CallTranscribe.{Sections, Session, Summary}
  alias RisiMe.Agent.{Discussion, Secretary, Transcript}
  alias RisiMe.TimeUUID
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    w = transcribe_world!()
    RisiMe.LedgerHelpers.ledger_on!()
    RisiMe.RisiHelpers.restore_on_exit([:risi_action_items, :risi_call_sections])
    Application.put_env(:risime, :risi_action_items, true)
    Application.delete_env(:risime, :risi_call_sections)
    w
  end

  # An ended session of `min` minutes with a buffered segment every 12 s, A and B in turn.
  defp call!(w, min) do
    started =
      DateTime.utc_now()
      |> DateTime.add(-(min * 60 + 30), :second)
      |> DateTime.truncate(:second)
      |> Map.put(:microsecond, {0, 6})

    s = %Session{
      session_id: Ecto.UUID.generate(),
      call_id: w.call_id,
      conversation_id: w.og,
      chat_id: Secretary.chat_id(w.og),
      state: "ended",
      discard: false,
      requested_at: started,
      requested_by: w.a.user.id,
      participants: [],
      expires_at: DateTime.add(started, 60, :second),
      started_at: started,
      ended_at: DateTime.add(started, min * 60, :second)
    }

    Repo.insert!(s)

    for i <- 0..(div(min * 60, 12) - 1) do
      a = DateTime.add(started, i * 12, :second)
      who = if rem(i, 2) == 0, do: w.a, else: w.b

      env = %{
        "v" => 1,
        "type" => "call_seg",
        "call_id" => s.call_id,
        "session_id" => s.session_id,
        "text" => "Line #{i}: we went through the quote and the transport cost.",
        "language" => "en",
        "model" => "faster-whisper-large-v3",
        "start" => DateTime.to_iso8601(a),
        "end" => DateTime.to_iso8601(DateTime.add(a, 11, :second)),
        "turn" => i,
        "p" => 0.97
      }

      :ok =
        Transcript.put(w.og, %{
          message_id: TimeUUID.at(DateTime.add(a, 1, :second)),
          sender_id: who.user.id,
          sender_device: nil,
          plaintext: Jason.encode!(env)
        })
    end

    s
  end

  defp job(w, s, attempt \\ 1),
    do:
      perform_job(
        Job,
        %{"kind" => "call_transcribe_summary", "conv" => w.og, "session_id" => s.session_id},
        attempt: attempt
      )

  defp part(body) do
    case Regex.run(~r/part (\d+) of (\d+)/, get_in(body, ["messages", Access.at(0), "content"])) do
      [_, i, _n] -> String.to_integer(i)
      nil -> 1
    end
  end

  defp first_line(body),
    do:
      body["messages"]
      |> List.last()
      |> Map.fetch!("content")
      |> String.split("\n")
      |> Enum.find_value(fn l ->
        case Jason.decode(l) do
          {:ok, %{"ref" => "m" <> _ = r}} -> r
          _ -> nil
        end
      end)

  # The same promise in every section (merged into one), one more in part 2, and per-part key
  # points; `fail` = the parts whose extraction answers 500.
  defp llm!(opts \\ []) do
    fail = Keyword.get(opts, :fail, [])
    reduce = Keyword.get(opts, :reduce, :ok)

    fake_llm!(fn
      "action_items", body ->
        i = part(body)

        if i in fail do
          {:status, 500}
        else
          item = fn text ->
            %{
              "kind" => "promise",
              "text" => text,
              "owner" => ref_of(body, "Test User A"),
              "counterparts" => [],
              "due_local" => nil,
              "due_text" => nil,
              "source" => [first_line(body)],
              "lang" => "en",
              "confidence" => 0.9
            }
          end

          %{
            "summary" => "Part #{i}: the quote.",
            "key_points" => ["Point of part #{i}."],
            "items" =>
              [item.("Send the revised quote")] ++
                if(i == 2, do: [item.("Check the transport cost")], else: [])
          }
        end

      "call_summary", _body ->
        if reduce == :ok,
          do: %{
            "key_points" => ["The quote was revised.", "Transport cost is open."],
            "summary" => "The revised quote and its transport cost."
          },
          else: {:status, 500}

      "discussion_summary", body ->
        %{
          "key_points" => ["Point of part #{part(body)}."],
          "summary" => "Part #{part(body)}: the quote.",
          "items" => []
        }

      _, _ ->
        %{"commitments" => []}
    end)
  end

  defp tasks,
    do: Enum.map(llm_requests(), &get_in(&1, ["response_format", "json_schema", "name"]))

  test "a short call with action items gets its summary card too", w do
    llm!()
    s = call!(w, 4)
    assert :ok = job(w, s)

    assert tasks() == ["action_items"]
    {body, card} = await_post(w.og, "discussion_card")
    assert card["source"] == "call" and card["summary"] == "Part 1: the quote. — Point of part 1."
    assert card["key_points"] == ["Point of part 1."]
    assert body =~ "Summary: Part 1: the quote.\n• Point of part 1."

    {_body, items} = await_post(w.og, "action_items")
    assert items["summary_id"] == card["summary_id"]
    assert [%{"text" => "Send the revised quote"}] = items["items"]

    assert [%Discussion{source: "call"} = d] = Repo.all(Discussion)
    {:ok, d} = Discussion.open(d)
    assert d.key_points == ["Point of part 1."] and d.items_count == 1
    assert Summary.segments(w.og, s) == []
  end

  test "a 30-minute call goes through map-reduce and posts one summary and one items card", w do
    llm!()
    s = call!(w, 30)
    assert :ok = job(w, s)

    # Three 10-minute sections, then one reduce.
    assert tasks() == ["action_items", "action_items", "action_items", "call_summary"]

    reqs = llm_requests()

    for {r, i} <- Enum.with_index(Enum.take(reqs, 3), 1),
        do: assert(hd(r["messages"])["content"] =~ "part #{i} of 3")

    {_body, card} = await_post(w.og, "discussion_card")

    assert card["summary"] ==
             "The revised quote and its transport cost. — The quote was revised; Transport cost is open."

    assert card["key_points"] == ["The quote was revised.", "Transport cost is open."]
    refute_post(w.og, "discussion_card")

    {_body, items} = await_post(w.og, "action_items")
    # The promise said in every part is one item; part 2's own item is kept.
    assert items["items"] |> Enum.map(& &1["text"]) |> Enum.sort() ==
             ["Check the transport cost", "Send the revised quote"]

    refute_post(w.og, "action_items")
    refute_post(w.og, "answer")
    assert [%Discussion{}] = Repo.all(Discussion)
    assert Summary.segments(w.og, s) == []
  end

  test "a failed section: the rest is summarised and one line says which part is missing", w do
    llm!(fail: [2])
    s = call!(w, 30)
    assert :ok = job(w, s)

    {_body, card} = await_post(w.og, "discussion_card")

    assert card["summary"] ==
             "The revised quote and its transport cost. — The quote was revised; Transport cost is open."

    {_body, items} = await_post(w.og, "action_items")
    assert Enum.map(items["items"], & &1["text"]) == ["Send the revised quote"]

    {line, _} = await_post(w.og, "answer")
    assert line == "I couldn't summarise minutes 10–20 of this call; the summary covers the rest."
    assert Summary.segments(w.og, s) == []
  end

  test "the reduce fails: the sections' own points are merged, a summary still posts", w do
    llm!(reduce: :fail)
    s = call!(w, 30)
    assert :ok = job(w, s)

    {_body, card} = await_post(w.og, "discussion_card")
    assert card["key_points"] == ["Point of part 1.", "Point of part 2.", "Point of part 3."]

    assert card["summary"] ==
             "Part 1: the quote. Part 2: the quote. Part 3: the quote. — Point of part 1; Point of part 2; Point of part 3."

    {_body, _items} = await_post(w.og, "action_items")
  end

  test "every section fails: the never-silent line once, the segments kept for the retry", w do
    llm!(fail: [1, 2, 3])
    s = call!(w, 30)
    assert {:error, _} = job(w, s, 1)
    {line, _} = await_post(w.og, "answer")
    assert line =~ "I couldn't summarise this call just now"
    refute_post(w.og, "discussion_card")
    assert Summary.segments(w.og, s) != []
  end

  test "action items off: the §27 summary of a 30-minute call is sectioned the same way", w do
    Application.put_env(:risime, :risi_action_items, false)
    llm!()
    s = call!(w, 30)
    assert :ok = job(w, s)

    assert tasks() == [
             "discussion_summary",
             "discussion_summary",
             "discussion_summary",
             "call_summary"
           ]

    {_body, card} = await_post(w.og, "discussion_card")

    assert card["summary"] ==
             "The revised quote and its transport cost. — The quote was revised; Transport cost is open."

    refute_post(w.og, "discussion_card")
    refute_post(w.og, "action_items")
  end

  # The gate (15 Oct): the phones show only the Official card's line, so the key points go to
  # each participant's Risi chat as the §27.3 copy, items or not.
  test "the key points reach each participant's Risi chat (with items, and without)", w do
    rca = RisiMe.RisiHelpers.own_risi_chat!(w.a.user)
    rcb = RisiMe.RisiHelpers.own_risi_chat!(w.b.user)
    llm!()
    s = call!(w, 4)
    assert :ok = job(w, s)

    {_body, card} = await_post(w.og, "discussion_card")

    for rc <- [rca, rcb] do
      {body, copy} = await_post(rc, "discussion_summary")
      assert copy["summary_id"] == card["summary_id"]
      assert copy["key_points"] == ["Point of part 1."] and copy["items"] == []
      assert body =~ "• Point of part 1."
    end

    # A owns the promise: its confirm card, too.
    {_body, confirm} = await_post(rca, "action_confirm")
    assert [%{"text" => "Send the revised quote"}] = confirm["items"]

    # No items (action items off): the §27 path sends the copies as well.
    Application.put_env(:risime, :risi_action_items, false)

    RisiMe.RisiHelpers.fake_llm!(fn
      "discussion_summary", _ ->
        %{"key_points" => ["Only talk."], "summary" => "Talk.", "items" => []}

      _, _ ->
        %{"commitments" => []}
    end)

    s2 = call!(w, 4)
    assert :ok = job(w, s2)
    {_body, _card} = await_post(w.og, "discussion_card")
    {_body, copy} = await_post(rca, "discussion_summary")
    assert copy["key_points"] == ["Only talk."] and copy["items"] == []
  end

  test "the card line: the summary, then the key points that fit in 280 characters" do
    alias RisiMe.Agent.LedgerOut

    assert LedgerOut.card_line("Quote talk.", ["Quote talk", "Price is 45000."]) ==
             "Quote talk. — Price is 45000."

    assert LedgerOut.card_line("Quote talk.", []) == "Quote talk."
    long = for i <- 1..8, do: "Point #{i} " <> String.duplicate("x", 50)
    line = LedgerOut.card_line("Quote talk.", long)
    assert String.length(line) <= 280 and line =~ "Point 1" and not (line =~ "Point 8")
  end

  test "sections: at most max_chars of text and max_span_s of call time each" do
    t0 = ~U[2026-10-14 22:00:00Z]
    lines = for i <- 0..149, do: %{text: String.duplicate("x", 40), at: DateTime.add(t0, i * 12)}

    assert lines |> Sections.split(& &1.at) |> Enum.map(&length/1) == [50, 50, 50]

    Application.put_env(:risime, :risi_call_sections, max_chars: 1_200, max_span_s: 10_000)
    # 40 characters + 80 per line: 10 lines a section.
    assert lines |> Sections.split(& &1.at) |> Enum.map(&length/1) |> Enum.uniq() == [10]
    assert Sections.split([], & &1.at) == []
  end
end
