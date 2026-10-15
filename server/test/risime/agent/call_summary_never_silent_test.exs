defmodule RisiMe.Agent.CallSummaryNeverSilentTest do
  @moduledoc """
  P0 2026-10-14 (docs/status/incident-2026-10-14-call-summary.md): with RISI_ACTION_ITEMS on
  (canary covers the dev users) a transcribed Official call that has no action items still gets
  its summary card (the §27 discussion summary, as before §38); a call too short to summarise,
  a transcript stopped before the call ended, and a failed summary each get one plain line from
  Risi instead of silence, and a failed summary keeps its segments for the retry.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.CallTranscribeHelpers

  alias RisiMe.Agent.CallTranscribe.Summary
  alias RisiMe.Agent.Discussion
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    w = transcribe_world!()
    RisiMe.LedgerHelpers.ledger_on!()
    RisiMe.RisiHelpers.restore_on_exit([:risi_action_items])
    Application.put_env(:risime, :risi_action_items, true)
    w
  end

  defp say!(w, who, sid, seq, offset_s, audio) do
    assert {202, %{"accepted" => true}} =
             upload!(w, who, sid, audio, %{
               "seq" => seq,
               "offset_ms" => offset_s * 1000,
               "duration_ms" => 12_000
             })

    assert_receive {:fake_speech, _, ^audio}, 2_000
    Process.sleep(60)
  end

  defp finish!(w, sid, reason) do
    tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => reason})

    eventually(fn ->
      all_enqueued(worker: Job) |> Enum.any?(&(&1.args["kind"] == "call_transcribe_summary"))
    end)
  end

  defp summary_job(w, sid, attempt \\ 1) do
    perform_job(
      Job,
      %{"kind" => "call_transcribe_summary", "conv" => w.og, "session_id" => sid},
      attempt: attempt
    )
  end

  defp conversation!(w, sid) do
    say!(w, :a, sid, 0, 0, "en:Let's send the revised quote")
    say!(w, :a, sid, 1, 12, "en:by Friday, and check the transport cost.")
    say!(w, :b, sid, 0, 24, "en:Yes, I'll look at the numbers.")
  end

  defp llm!(items_for_actions) do
    RisiMe.RisiHelpers.fake_llm!(fn
      "action_items", _body ->
        %{"summary" => "Quote talk.", "items" => items_for_actions}

      "discussion_summary", _body ->
        %{
          "key_points" => ["The quote needs the transport cost."],
          "summary" => "Quote talk.",
          "items" => []
        }

      _, _ ->
        %{"commitments" => []}
    end)
  end

  test "action items on, none found: the call still gets its summary card", w do
    llm!([])
    sid = start_on!(w)
    conversation!(w, sid)
    finish!(w, sid, "ended")
    assert :ok = summary_job(w, sid)

    {_body, card} = await_post(w.og, "discussion_card")

    assert card["source"] == "call" and
             card["summary"] == "Quote talk. — The quote needs the transport cost."

    assert [%Discussion{source: "call"}] = Repo.all(Discussion)
    assert Summary.segments(w.og, session!(sid)) == []
  end

  test "too little speech: one line, no card", w do
    llm!([])
    sid = start_on!(w)
    say!(w, :a, sid, 0, 0, "en:hello there")
    say!(w, :b, sid, 0, 12, "en:hi")
    finish!(w, sid, "ended")
    assert :ok = summary_job(w, sid)

    {body, _} = await_post(w.og, "answer")
    assert body == "This call had too little speech to summarise."
    refute_post(w.og, "discussion_card")
  end

  test "transcription turned off mid-call: the summary says which part it covers", w do
    llm!([])
    sid = start_on!(w)
    conversation!(w, sid)
    finish!(w, sid, "stopped")
    assert session!(sid).state == "stopped"
    assert :ok = summary_job(w, sid)

    {_body, _card} = await_post(w.og, "discussion_card")
    {body, _} = await_post(w.og, "answer")
    assert body =~ ~r/^Summary of the first \d+ min; transcription was turned off after that\.$/
  end

  test "the summary fails: one line on the first attempt, segments kept for the retry", w do
    RisiMe.RisiHelpers.fake_llm!(fn _, _ -> {:status, 500} end)
    sid = start_on!(w)
    conversation!(w, sid)
    finish!(w, sid, "ended")

    assert {:error, _} = summary_job(w, sid, 1)
    {body, _} = await_post(w.og, "answer")
    assert body =~ "I couldn't summarise this call just now"
    assert Summary.segments(w.og, session!(sid)) != []

    # The retry says nothing more until it succeeds.
    assert {:error, _} = summary_job(w, sid, 2)
    refute_post(w.og, "answer")

    llm!([])
    assert :ok = summary_job(w, sid, 3)
    {_body, card} = await_post(w.og, "discussion_card")
    assert card["source"] == "call"
  end
end
