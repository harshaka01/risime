defmodule RisiMe.Agent.Parity.CallSummaryTest do
  @moduledoc """
  Deep agents step 0: parity pins for the after-call summary (§36.7, `CallTranscribe` +
  `call_transcribe_summary` job) as it is today: chunks uploaded through the real endpoint
  (fake speech service, fake `risi-l1`) → ≥ 30 s from 2 speakers → one `discussion_summary`
  call → the Official `discussion_card` (source call, lang, the speech models in `made_by.also`)
  and the stored item; < 30 s or one speaker → no model call, no card.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.CallTranscribeHelpers

  alias RisiMe.Agent.{CallTranscribe, Commitment, Discussion}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :risi_parity
  @moduletag capture_log: true

  @bound_ms 3_000

  setup do
    transcribe_world!()
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  defp say!(w, who, sid, seq, offset_s, audio, dur_ms \\ 12_000) do
    assert {202, %{"seq" => ^seq, "accepted" => true, "duplicate" => false}} =
             upload!(w, who, sid, audio, %{
               "seq" => seq,
               "offset_ms" => offset_s * 1000,
               "duration_ms" => dur_ms
             })

    assert_receive {:fake_speech, _, ^audio}, 2_000
    Process.sleep(60)
  end

  defp end_call!(w, sid) do
    tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
    _ = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))

    eventually(fn ->
      all_enqueued(worker: Job) |> Enum.any?(&(&1.args["kind"] == "call_transcribe_summary"))
    end)

    t = System.monotonic_time(:millisecond)

    assert :ok =
             perform_job(Job, %{
               "kind" => "call_transcribe_summary",
               "conv" => w.og,
               "session_id" => sid
             })

    assert System.monotonic_time(:millisecond) - t < @bound_ms
  end

  test "≥ 30 s from 2 speakers: one discussion_summary call, the call card, the item", w do
    RisiMe.LedgerHelpers.ledger_on!()

    RisiMe.RisiHelpers.fake_llm!(fn
      "discussion_summary", body ->
        %{
          "key_points" => ["The quote needs the new transport cost."],
          "summary" => "Quote talk.",
          "items" => [RisiMe.LedgerHelpers.item(body, "Test User A", "Send the revised quote")]
        }

      "discussion_translate", _body ->
        %{"key_points" => ["ප්‍රවාහන වියදම සමඟ quote එක යවන්න ඕනේ."]}
    end)

    sid = start_on!(w)
    say!(w, :a, sid, 0, 0, "en:Let's send the revised quote")
    say!(w, :a, sid, 1, 12, "en:by Friday.")
    say!(w, :b, sid, 0, 24, "si:හරි, මම බලන්නම්.")
    end_call!(w, sid)

    # The summary, then one translation of its key points for the Sinhala speaker.
    assert Enum.map(
             RisiMe.RisiHelpers.llm_requests(),
             &get_in(&1, ~w(response_format json_schema name))
           ) ==
             ["discussion_summary", "discussion_translate"]

    [req, tr] = RisiMe.RisiHelpers.llm_requests()
    input = List.last(req["messages"])["content"]
    assert input =~ "Let's send the revised quote by Friday."
    assert hd(tr["messages"])["content"] =~ "into Sinhala"

    {body, card} = await_post(w.og, "discussion_card")
    assert body == "Risi summarised this discussion (1 item). Details in your Risi chat."

    assert keys(card) ==
             ~w(call_id call_ref duration_s ended_at items_count kind lang made_by media notify
                source started_at summary summary_id v with)

    assert card["media"] == "audio" and card["notify"] == [] and card["v"] == 1
    assert Enum.sort(card["with"]) == Enum.sort([w.a.user.id, w.b.user.id])
    assert is_binary(card["call_ref"]) and is_integer(card["duration_s"])
    assert card["source"] == "call" and card["call_id"] == w.call_id and card["lang"] == "en"
    assert card["items_count"] == 1 and card["summary"] == "Quote talk."
    assert card["made_by"]["model"] == "risi-l1"
    models = Enum.map(card["made_by"]["also"], & &1["model"])
    assert "faster-whisper-large-v3" in models and "whisper-small-sinhala" in models
    assert Enum.all?(card["made_by"]["also"], &(&1["task"] == "transcribe"))

    [d] = Repo.all(Discussion)
    assert d.source == "call" and d.conversation_id == w.og and d.items_count == 1
    [c] = Repo.all(Commitment)
    assert c.owner_id == w.a.user.id and c.source == "call" and c.state == "proposed"
    assert card["summary_id"] == d.summary_id

    assert CallTranscribe.Summary.segments(w.og, session!(sid)) == []
  end

  test "< 30 s of speech: no model call, no card", w do
    RisiMe.LedgerHelpers.ledger_on!()
    RisiMe.LedgerHelpers.discussion_llm!(fn _ -> flunk("no model call") end)
    sid = start_on!(w)
    say!(w, :a, sid, 0, 0, "en:hello there")
    say!(w, :b, sid, 0, 12, "en:hi")
    end_call!(w, sid)
    refute_post(w.og, "discussion_card")
    assert RisiMe.RisiHelpers.llm_requests() == []
    assert Repo.aggregate(Discussion, :count) == 0
  end
end
