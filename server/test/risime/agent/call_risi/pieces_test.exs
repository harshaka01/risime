defmodule RisiMe.Agent.CallRisi.PiecesTest do
  @moduledoc """
  Decision 090: the pure parts of Call Risi: the sentence cutter (first audio from the first clause),
  the fillers (only on tool routes, never a result), the SSE decoder of the streamed answer, the
  transcript post (proposal §41.5) and the Kokoro WAV header.
  """
  use ExUnit.Case, async: true

  alias RisiMe.Agent.CallRisi
  alias RisiMe.Agent.CallRisi.{Fillers, LLMStream, Sentences}

  describe "Sentences" do
    test "a piece is ready at a sentence end; the rest waits" do
      assert {["Sure."], "I can"} = Sentences.feed("", "Sure. I can")
      assert {["I can help with that."], ""} = Sentences.feed("I can", " help with that.\n")
    end

    test "the first piece may end at a clause of 4+ words, later ones not" do
      assert {["Tomorrow you have two meetings,"], "one at ten"} =
               Sentences.feed("", "Tomorrow you have two meetings, one at ten", true)

      assert {[], "Tomorrow you have two meetings, one at ten"} =
               Sentences.feed("", "Tomorrow you have two meetings, one at ten", false)

      # Too short a clause is not cut.
      assert {[], "Well, okay"} = Sentences.feed("", "Well, okay", true)
    end

    test "markdown, bullets and emoji are not spoken; flush returns the tail" do
      assert Sentences.clean("**Hello** there 😀") == "Hello there"
      assert Sentences.clean("- one item") == "one item"
      assert Sentences.flush("  the end  ") == ["the end"]
      assert Sentences.flush("   ") == []
    end
  end

  describe "Fillers" do
    test "no filler on the chat route; one on every tool route" do
      assert Fillers.pick("chat", "en") == nil
      refute Fillers.tool_route?("chat")

      for r <-
            ~w(calendar_read calendar_write reminder ledger_items memory summary_chat message_write planner) do
        assert Fillers.tool_route?(r)
        assert is_binary(Fillers.pick(r, "en"))
      end

      assert Fillers.pick("calendar_read", "en", 0) != Fillers.pick("calendar_read", "en", 1)
      # An unknown language falls back to English.
      assert Fillers.pick("memory", "si") == Fillers.pick("memory", "en")
    end

    test "no filler states or implies a result" do
      for l <- Fillers.all() do
        refute l =~ ~r/\b(free|busy|done|added|booked|saved|sent|scheduled|you have|nothing)\b/i,
               "filler claims a result: #{l}"
      end
    end
  end

  describe "LLMStream SSE" do
    test "splits events and decodes deltas and [DONE]" do
      {events, rest} =
        LLMStream.split_events(
          ~s(data: {"model":"m","choices":[{"delta":{"content":"Hi"}}]}\n\ndata: [DONE]\n\ndata: {"cho)
        )

      assert rest == ~s(data: {"cho)
      assert [{:delta, "Hi", "m"}, :done] = Enum.map(events, &LLMStream.decode/1)
      assert LLMStream.decode(": keep-alive") == :skip
    end
  end

  describe "transcript (§41.5)" do
    test "the post's fields, body and duration" do
      start = ~U[2026-10-14 09:00:00.000Z]
      ended = ~U[2026-10-14 09:03:12.000Z]

      lines = [
        %{"who" => "user", "text" => "What's on tomorrow?", "lang" => "en", "at" => "t1"},
        %{
          "who" => "risi",
          "text" => "Hmm, let me check your calendar…",
          "lang" => "en",
          "at" => "t2",
          "filler" => true
        }
      ]

      args = %{call_id: "c1", user_id: "u1", conv: "grp:x"}
      {risi, body} = CallRisi.transcript(args, lines, start, ended, :hangup)

      assert risi["kind"] == "risi_call_transcript"
      assert risi["call_id"] == "c1"
      assert risi["duration_s"] == 192
      assert risi["reason"] == "hangup"
      assert risi["e2ee"] == false
      assert risi["notify"] == ["u1"]
      assert risi["lines"] == lines
      refute Map.has_key?(risi, "truncated")

      assert body ==
               "Call with Risi · 3:12\nYou: What's on tomorrow?\nRisi: Hmm, let me check your calendar…"
    end

    test "at most 400 lines, marked truncated" do
      lines =
        for i <- 1..450, do: %{"who" => "user", "text" => "#{i}", "lang" => "en", "at" => "t"}

      args = %{call_id: "c1", user_id: "u1", conv: "grp:x"}

      {risi, _} =
        CallRisi.transcript(args, lines, DateTime.utc_now(), DateTime.utc_now(), :hangup)

      assert length(risi["lines"]) == 400
      assert risi["truncated"] == true
      assert hd(risi["lines"])["text"] == "51"
    end

    test "nothing is posted for a call without a user line" do
      me = self()

      args = %{
        call_id: "c1",
        user_id: "u1",
        conv: "grp:x",
        post: fn _, _, _ -> send(me, :posted) end
      }

      CallRisi.post_transcript(
        args,
        [%{"who" => "risi", "text" => "Hi", "lang" => "en", "at" => "t"}],
        DateTime.utc_now(),
        :hangup
      )

      refute_receive :posted, 100
    end
  end

  test "the scoreboard counts Call Risi's model calls for the voice_call agent" do
    assert RisiMe.Agents.Scoreboard.agent_of_task("voice_call_answer") == "voice_call"
  end

  test "Kokoro WAV header: 24 kHz mono s16" do
    wav = RisiMe.Agent.TTS.Kokoro.wav(<<0, 0, 1, 0>>)

    assert <<"RIFF", 40::little-32, "WAVEfmt ", 16::little-32, 1::little-16, 1::little-16,
             24_000::little-32, _::binary>> = wav

    assert byte_size(wav) == 48
  end

  test "the session's WAV for the speech service is 16 kHz mono s16" do
    wav = CallRisi.Session.wav16k(<<1, 2>>)

    assert <<"RIFF", 38::little-32, "WAVEfmt ", 16::little-32, 1::little-16, 1::little-16,
             16_000::little-32, _::binary>> = wav
  end

  test "Risi's token claims: microphone only, data (v1.52 §41.8), not hidden" do
    c = CallRisi.risi_claims("APIk", "room1", 1000)
    assert c["sub"] == CallRisi.risi_identity()
    assert c["video"]["room"] == "room1"
    assert c["video"]["canPublish"] == true
    assert c["video"]["canSubscribe"] == true
    assert c["video"]["canPublishSources"] == ["microphone"]
    assert c["video"]["canPublishData"] == true
    refute Map.has_key?(c["video"], "hidden")
    refute Map.has_key?(c["video"], "roomAdmin")
  end
end
