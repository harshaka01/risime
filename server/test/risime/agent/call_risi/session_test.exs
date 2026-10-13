defmodule RisiMe.Agent.CallRisi.SessionTest do
  @moduledoc """
  Decision 090: the Voice agent's turn-taking in one Call Risi session, against a fake media worker,
  a fake speech service, a fake streamed model and a fake §25 turn: Risi answers first (greeting), a
  chat turn is streamed and spoken piece by piece, a tool turn says a filler first and then the
  turn's own answer, barge-in stops the audio and kills the turn, each turn records its timings
  (never text), and the transcript is posted when the caller leaves. Plus the Turn hook's parity.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.Session

  defmodule Media do
    @behaviour RisiMe.Agent.CallRisi.Media
    @impl true
    def open(_owner, args), do: {:ok, args.test}
    @impl true
    def command(test, cmd) do
      send(test, {:media_cmd, cmd})
      :ok
    end

    @impl true
    def close(_), do: :ok
  end

  defmodule Speech do
    @behaviour RisiMe.Agent.Speech
    @impl true
    def healthy?, do: true
    @impl true
    def compare(_, _), do: {:error, :failed}
    @impl true
    def transcribe(<<_hdr::binary-size(44), script::binary>>, _opts) do
      case String.split(script, ":", parts: 2) do
        ["fail"] ->
          {:error, :unavailable}

        [lang, text] ->
          {:ok,
           %{text: text, language: lang, model: "fake-asr", elapsed_ms: 10, speech: text != ""}}
      end
    end
  end

  defmodule LLM do
    def stream(messages, _opts, on_delta) do
      send(:persistent_term.get({__MODULE__, :test}), {:llm, messages})

      for d <- :persistent_term.get({__MODULE__, :deltas}) do
        Process.sleep(5)
        on_delta.(d)
      end

      {:ok, %{model: "fake-l1", first_token_ms: 42, tokens: 3}}
    end
  end

  defmodule Turn do
    def run(conv, env, request_id, user) do
      test = :persistent_term.get({LLM, :test})
      send(test, {:turn_run, conv, env, request_id, user})
      {pid, _call} = Process.get(:risi_voice_sink)
      Process.sleep(20)
      send(pid, {:risi_voice_answer, request_id, "Tomorrow you have one meeting at ten."})
      :ok
    end
  end

  defmodule Runs do
    def record(row) do
      send(:persistent_term.get({LLM, :test}), {:runs, row})
      :ok
    end
  end

  setup do
    keys = [:risi_call_media, :risi_speech, :risi_call_llm, :risi_call_turn, :risi_call_runs]
    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_llm, LLM)
    Application.put_env(:risime, :risi_call_turn, Turn)
    Application.put_env(:risime, :risi_call_runs, Runs)
    :persistent_term.put({LLM, :test}, self())

    :persistent_term.put({LLM, :deltas}, [
      "I'm well, thanks",
      " for asking. How can",
      " I help today?"
    ])

    on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v,
              do: Application.put_env(:risime, k, v),
              else: Application.delete_env(:risime, k)
            )
    end)

    case start_supervised(RisiMe.Agent.CallRisi.Supervisor) do
      {:ok, _} -> :ok
      {:error, {{:already_started, _}, _}} -> :ok
      {:error, {:already_started, _}} -> :ok
    end

    me = self()
    call_id = Ecto.UUID.generate()

    args = %{
      conv: "grp:risi-chat",
      call_id: call_id,
      user_id: Ecto.UUID.generate(),
      device_id: Ecto.UUID.generate(),
      room: "room1",
      risi_token: "tok",
      worker_url: "ws://127.0.0.1:7880",
      test: me,
      metrics_to: me,
      tools?: true,
      post: fn conv, body, risi -> send(me, {:posted, conv, body, risi}) end
    }

    {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
    assert_receive {:media_cmd, %{"cmd" => "join", "token" => "tok", "caller" => caller} = join}
    assert caller == args.user_id <> "/"
    assert join["url"] == "ws://127.0.0.1:7880"
    %{pid: pid, args: args}
  end

  defp ev(pid, map), do: send(pid, {:call_risi_media, map})

  defp utter(pid, utt, script),
    do:
      ev(pid, %{
        "ev" => "utterance",
        "utt" => utt,
        "pcm16k" => Base.encode64(script),
        "speech_ms" => 900,
        "eot_wait_ms" => 450
      })

  defp answer_greeting(pid) do
    ev(pid, %{"ev" => "joined"})
    ev(pid, %{"ev" => "caller_joined"})

    assert_receive {:media_cmd,
                    %{
                      "cmd" => "say",
                      "seq" => 1,
                      "utt" => 0,
                      "text" => "Hi, it's Risi. How can I help?",
                      "voice" => "kokoro:af_heart"
                    }}

    ev(pid, %{"ev" => "said", "seq" => 1, "utt" => 0, "ms" => 1500, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})
  end

  test "a streamed chat turn: pieces in order, timings recorded, transcript on hang-up", %{
    pid: pid,
    args: args
  } do
    answer_greeting(pid)
    utter(pid, 1, "en:how are you")

    assert_receive {:llm, messages}
    assert List.last(messages) == %{"role" => "user", "content" => "how are you"}
    assert hd(messages)["role"] == "system"

    assert_receive {:media_cmd,
                    %{
                      "cmd" => "say",
                      "seq" => 2,
                      "utt" => 1,
                      "text" => "I'm well, thanks for asking."
                    }}

    assert_receive {:media_cmd,
                    %{"cmd" => "say", "seq" => 3, "utt" => 1, "text" => "How can I help today?"}}

    ev(pid, %{
      "ev" => "first_audio",
      "utt" => 1,
      "seq" => 2,
      "after_end_ms" => 980,
      "tts_first_ms" => 300
    })

    ev(pid, %{"ev" => "said", "seq" => 2, "utt" => 1, "ms" => 1200, "interrupted" => false})
    ev(pid, %{"ev" => "said", "seq" => 3, "utt" => 1, "ms" => 1100, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})

    assert_receive {:runs, row}
    assert row.agent == "voice_call"
    assert row.latency_ms == 980
    assert row.status == "ok"
    assert row.budget_ms == 1500
    assert row.by == "chat"
    assert row.decision =~ "first=980"
    assert row.decision =~ "tok=42"
    # Timings only: no utterance or answer text in the row.
    refute inspect(row) =~ "how are you"
    refute inspect(row) =~ "thanks"

    assert_receive {:call_risi_turn, %{utt: 1, after_end_ms: 980, stt_ms: ms, route: "chat"}}
    assert is_integer(ms)

    ev(pid, %{"ev" => "caller_left"})
    assert_receive {:media_cmd, %{"cmd" => "leave", "reason" => "hangup"}}
    assert_receive {:posted, "grp:risi-chat", body, risi}
    assert risi["kind"] == "risi_call_transcript"
    assert risi["call_id"] == args.call_id
    assert risi["reason"] == "hangup"
    assert risi["e2ee"] == false

    assert Enum.map(risi["lines"], &{&1["who"], &1["text"]}) == [
             {"risi", "Hi, it's Risi. How can I help?"},
             {"user", "how are you"},
             {"risi", "I'm well, thanks for asking."},
             {"risi", "How can I help today?"}
           ]

    assert body =~ "You: how are you"
    refute Process.alive?(pid)
  end

  test "a tool turn says a filler first, then the §25 turn's own answer", %{pid: pid, args: args} do
    answer_greeting(pid)
    utter(pid, 1, "en:what's on my calendar tomorrow")

    assert_receive {:media_cmd, %{"cmd" => "say", "seq" => 2, "text" => filler}}
    assert filler in RisiMe.Agent.CallRisi.Fillers.all()
    assert_receive {:turn_run, "grp:risi-chat", env, rid, user}
    assert user == args.user_id
    assert env["type"] == "risi_request" and env["action"] == "ask"
    assert env["text"] == "what's on my calendar tomorrow"
    assert env["__device"] == args.device_id
    assert is_binary(rid)

    assert_receive {:media_cmd,
                    %{
                      "cmd" => "say",
                      "seq" => 3,
                      "text" => "Tomorrow you have one meeting at ten."
                    }}

    refute_receive {:llm, _}, 50

    ev(pid, %{
      "ev" => "first_audio",
      "utt" => 1,
      "seq" => 2,
      "after_end_ms" => 700,
      "tts_first_ms" => 250
    })

    ev(pid, %{"ev" => "said", "seq" => 2, "utt" => 1, "ms" => 900, "interrupted" => false})
    ev(pid, %{"ev" => "said", "seq" => 3, "utt" => 1, "ms" => 1900, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})
    assert_receive {:runs, %{by: "calendar_read", latency_ms: 700, status: "ok"}}

    ev(pid, %{"ev" => "caller_left"})
    assert_receive {:posted, _, _, risi}
    [_, _, f, a] = risi["lines"]
    assert f["filler"] == true
    assert a["request_id"] == rid
  end

  test "barge-in stops the audio and the turn; the next utterance is answered", %{pid: pid} do
    answer_greeting(pid)
    :persistent_term.put({LLM, :deltas}, ["Here is a long answer.", " It goes on.", " And on."])
    utter(pid, 1, "en:tell me a story")
    assert_receive {:media_cmd, %{"cmd" => "say", "utt" => 1, "seq" => s1}}

    ev(pid, %{
      "ev" => "first_audio",
      "utt" => 1,
      "seq" => s1,
      "after_end_ms" => 800,
      "tts_first_ms" => 300
    })

    ev(pid, %{"ev" => "barge_in", "utt_playing" => 1})
    assert_receive {:media_cmd, %{"cmd" => "stop_speaking"}}
    assert_receive {:runs, %{reason: "barged", latency_ms: 800}}

    # What was cut is not in the transcript.
    ev(pid, %{"ev" => "said", "seq" => s1, "utt" => 1, "ms" => 300, "interrupted" => true})

    :persistent_term.put({LLM, :deltas}, ["Okay."])
    utter(pid, 2, "en:stop")
    assert_receive {:media_cmd, %{"cmd" => "say", "utt" => 2, "text" => "Okay."}}

    ev(pid, %{"ev" => "caller_left"})
    assert_receive {:posted, _, _, risi}
    texts = Enum.map(risi["lines"], & &1["text"])
    refute "Here is a long answer." in texts
    assert "tell me a story" in texts
  end

  describe "Turn.voice_sink (parity)" do
    test "without the call's process key the answer card is unchanged" do
      Process.delete(:risi_voice_sink)
      card = %{"kind" => "answer", "request_id" => "r1", "answer" => "x"}
      assert RisiMe.Agent.Turn.voice_sink("x", card) == card
      refute_received {:risi_voice_answer, _, _}
    end

    test "with it, the call gets the answer text and the card the call_id" do
      Process.put(:risi_voice_sink, {self(), "call-1"})
      card = %{"kind" => "answer", "request_id" => "r1"}

      assert RisiMe.Agent.Turn.voice_sink("Ten o'clock.", card) ==
               Map.put(card, "call_id", "call-1")

      assert_received {:risi_voice_answer, "r1", "Ten o'clock."}
      Process.delete(:risi_voice_sink)
    end
  end
end
