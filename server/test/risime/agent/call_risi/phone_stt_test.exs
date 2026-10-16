defmodule RisiMe.Agent.CallRisi.PhoneSttTest do
  @moduledoc """
  Contract v1.53 §41.10 (Harsha 2026-10-16 04:52 SLT, P0 hearing): the caller's English speech
  recognised on the phone, sent over `risi.stt`. The server gate: `mode: "phone"` only with the
  capability (and only once the worker acked it); a final drives a turn (no server STT); low
  `conf` gets no answer plus one "Sorry, I didn't catch that."; `during_risi` without barge-in
  energy gets no answer; after `off` (or 3 utterances with no final) the server's STT takes over;
  parity without the capability; the phone's text is never logged or recorded.
  """
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  alias RisiMe.Agent.CallRisi.{PhoneStt, Session}

  ## Pure rules

  test "capability only with call_risi; mode commands; finals normalised and judged" do
    assert PhoneStt.capable?(~w(group_calls risi_tools call_risi call_risi_stt))
    refute PhoneStt.capable?(~w(call_risi_stt))
    refute PhoneStt.capable?(~w(call_risi call_risi_cal))
    assert PhoneStt.capability() == "call_risi_stt" and PhoneStt.topic() == "risi.stt"
    assert PhoneStt.mode_cmd("phone") == %{"cmd" => "stt_mode", "mode" => "phone"}

    ex =
      Path.expand("../../../../../contract/v1/examples/call_risi_stt_final.json", __DIR__)
      |> File.read!()
      |> Jason.decode!()

    assert {:ok, %{text: t, conf: c, during_risi?: false} = f} =
             PhoneStt.final(Map.put(ex, "ev", "stt"))

    assert t == ex["text"] and c == ex["conf"]
    assert PhoneStt.judge(f, false) == {:ok, t}
    assert PhoneStt.judge(%{f | conf: 0.3}, false) == {:unclear, "low_conf"}
    assert PhoneStt.judge(%{f | conf: nil}, false) == {:ok, t}
    assert PhoneStt.judge(%{f | during_risi?: true}, false) == {:drop, "echo"}
    assert PhoneStt.judge(%{f | during_risi?: true}, true) == {:ok, t}
    assert PhoneStt.final(%{"t" => "final", "text" => "  "}) == :error
    assert PhoneStt.final(%{"t" => "final", "text" => "x", "conf" => 7}) == :error
    assert PhoneStt.final(%{"t" => "partial", "text" => "x"}) == :error
  end

  ## The session

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
    def transcribe(<<_hdr::binary-size(44), text::binary>>, _opts) do
      send(:persistent_term.get({__MODULE__, :test}), {:server_stt, text})

      {:ok,
       %{
         text: text,
         language: "en",
         model: "fake-asr",
         elapsed_ms: 10,
         speech: true,
         segments: [
           %{
             offset_ms: 0,
             text: text,
             avg_logprob: -0.2,
             no_speech_prob: 0.02,
             compression_ratio: 1.1
           }
         ]
       }}
    end
  end

  defmodule LLM do
    def stream(messages, _opts, on_delta) do
      send(:persistent_term.get({__MODULE__, :test}), {:llm, messages})
      on_delta.("Sure, I can help with that.")
      {:ok, %{model: "fake-l1", first_token_ms: 42, tokens: 3}}
    end
  end

  defmodule Turn do
    def run(_conv, env, request_id, _user) do
      send(:persistent_term.get({LLM, :test}), {:turn_run, env})
      {pid, _call} = Process.get(:risi_voice_sink)
      send(pid, {:risi_voice_answer, request_id, "Nothing else is on today."})
      :ok
    end
  end

  defmodule Runs do
    def record(row) do
      send(:persistent_term.get({LLM, :test}), {:run_row, row})
      :ok
    end
  end

  @keys [
    :risi_call_media,
    :risi_speech,
    :risi_call_llm,
    :risi_call_turn,
    :risi_call_runs,
    :risi_call_merge_ms,
    :risi_call_stt_miss_ms,
    :risi_call_stt_pair_ms
  ]

  setup do
    prev = for k <- @keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_merge_ms, -100_000)
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_llm, LLM)
    Application.put_env(:risime, :risi_call_turn, Turn)
    Application.put_env(:risime, :risi_call_runs, Runs)
    Application.put_env(:risime, :risi_call_stt_miss_ms, 300)
    Application.put_env(:risime, :risi_call_stt_pair_ms, 300)
    :persistent_term.put({LLM, :test}, self())
    :persistent_term.put({Speech, :test}, self())

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

    :ok
  end

  defp start(stt?) do
    me = self()

    args = %{
      conv: "grp:risi-chat",
      call_id: Ecto.UUID.generate(),
      user_id: Ecto.UUID.generate(),
      device_id: Ecto.UUID.generate(),
      room: "room1",
      risi_token: "tok",
      worker_url: "ws://127.0.0.1:7880",
      test: me,
      metrics_to: me,
      tools?: true,
      cal?: false,
      stt?: stt?,
      caps: ~w(risi_calendar_check risi_calendar_add),
      post: fn conv, body, risi -> send(me, {:posted, conv, body, risi}) end
    }

    {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
    assert_receive {:media_cmd, %{"cmd" => "join"}}
    ev(pid, %{"ev" => "joined"})
    ev(pid, %{"ev" => "caller_joined"})
    assert_receive {:media_cmd, %{"cmd" => "say", "seq" => 1, "utt" => 0}}
    ev(pid, %{"ev" => "said", "seq" => 1, "utt" => 0, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})
    pid
  end

  # A phone-mode call: the mode command, then the worker's ack.
  defp phone_call do
    pid = start(true)
    assert_receive {:media_cmd, %{"cmd" => "stt_mode", "mode" => "phone"}}
    ev(pid, %{"ev" => "stt_mode", "mode" => "phone"})
    pid
  end

  defp ev(pid, map), do: send(pid, {:call_risi_media, map})

  defp utter(pid, utt, words, speech_ms \\ 1_200) do
    ev(pid, %{"ev" => "speech_start", "speaking" => false})

    ev(pid, %{
      "ev" => "utterance",
      "utt" => utt,
      "pcm16k" => Base.encode64(words),
      "speech_ms" => speech_ms,
      "eot_wait_ms" => 400
    })
  end

  defp final(pid, text, extra \\ %{}),
    do:
      ev(
        pid,
        Map.merge(
          %{"ev" => "stt", "t" => "final", "seq" => 1, "text" => text, "conf" => 0.9},
          extra
        )
      )

  defp says(utt, acc \\ [], wait \\ 2_000) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => ^utt} = c} -> says(utt, acc ++ [c], 400)
    after
      wait -> acc
    end
  end

  defp any_says(wait) do
    receive do
      {:media_cmd, %{"cmd" => "say"} = c} -> c
    after
      wait -> nil
    end
  end

  defp heard(pid, cmds) do
    for c <- cmds,
        do: ev(pid, %{"ev" => "said", "seq" => c["seq"], "interrupted" => false})

    ev(pid, %{"ev" => "idle"})
  end

  # The words a turn was asked with (a tool turn's text, or the chat model's last user message).
  defp asked(wait \\ 2_000) do
    receive do
      {:turn_run, %{"text" => t}} ->
        t

      {:llm, msgs} ->
        msgs |> Enum.filter(&(&1["role"] == "user")) |> List.last() |> Map.get("content")
    after
      wait -> nil
    end
  end

  test "parity without the capability: no mode, the server's STT drives every turn" do
    pid = start(false)
    refute_received {:media_cmd, %{"cmd" => "stt_mode"}}
    utter(pid, 1, "Do I have anything to do today?")
    assert_receive {:server_stt, "Do I have anything to do today?"}, 2_000
    assert asked() =~ "Do I have anything to do today?"
    # A stray stt event (no phone mode) is ignored.
    final(pid, "made up")
    refute_receive {:turn_run, _}, 300
  end

  test "an older worker that never acks the mode keeps the server's STT" do
    pid = start(true)
    assert_receive {:media_cmd, %{"cmd" => "stt_mode", "mode" => "phone"}}
    utter(pid, 1, "Tell me a fun fact about tea.")
    assert_receive {:server_stt, "Tell me a fun fact about tea."}, 2_000
  end

  test "phone mode: the final drives the turn, no server STT, never logged" do
    log =
      capture_log(fn ->
        pid = phone_call()

        # The audio (what Whisper would have heard) is not transcribed; the phone's words are used.
        utter(pid, 1, "You're on. Bye-bye.")
        Process.sleep(50)
        final(pid, "Do I have anything to do today?")
        assert asked() =~ "Do I have anything to do today?"
        refute_received {:server_stt, _}
        s1 = says(1)
        assert s1 != []
        heard(pid, s1)

        assert_receive {:run_row, row}, 2_000
        assert row.decision =~ ";src=phone"
        refute inspect(row) =~ "anything to do"

        assert_receive {:call_risi_turn, %{utt: 1, stt_model: "phone", stt_ms: ms}}
        assert is_integer(ms)

        # The final before its utterance (the phone's endpoint was first): paired, one turn.
        ev(pid, %{"ev" => "speech_start", "speaking" => false})
        final(pid, "Tell me a fun fact about tea.", %{"seq" => 2})
        Process.sleep(50)

        ev(pid, %{
          "ev" => "utterance",
          "utt" => 2,
          "pcm16k" => Base.encode64("noise"),
          "speech_ms" => 900,
          "eot_wait_ms" => 400
        })

        assert asked() =~ "Tell me a fun fact about tea."
        s2 = says(2)
        assert s2 != []
        heard(pid, s2)
        refute_received {:server_stt, _}

        ev(pid, %{"ev" => "caller_left"})
        assert_receive {:posted, _, body, _}, 3_000
        # The transcript carries the phone's words (§41.5), never the audio's made-up ones.
        assert body =~ "Do I have anything to do today?"
        refute body =~ "Bye-bye"
      end)

    refute log =~ "anything to do"
    refute log =~ "fun fact"
  end

  test "low conf: one Sorry, then silence; during_risi without barge-in: no answer" do
    pid = phone_call()

    utter(pid, 1, "x")
    final(pid, "and I tend to come", %{"conf" => 0.2})
    s1 = says(1)
    assert Enum.map(s1, & &1["text"]) == ["Sorry, I didn't catch that."]
    heard(pid, s1)

    utter(pid, 2, "x")
    final(pid, "so do I", %{"conf" => 0.3, "seq" => 2})
    assert says(2, [], 500) == []
    refute_received {:turn_run, _}
    refute_received {:llm, _}

    # Risi's own voice recognised by the phone: no answer (no barge-in on the audio).
    final(pid, "Hi, it's Risi", %{"during_risi" => true, "seq" => 3})
    assert any_says(500) == nil

    # A clear one after: answered (and the next unclear may say Sorry again).
    utter(pid, 4, "x")
    final(pid, "Tell me a fun fact about tea.", %{"seq" => 4})
    assert asked() =~ "Tell me a fun fact about tea."
    heard(pid, says(4))

    # During Risi's speech with a real barge-in on the audio: answered.
    ev(pid, %{"ev" => "barge_in", "utt_playing" => nil})
    final(pid, "Do I have anything to do today?", %{"during_risi" => true, "seq" => 5})
    assert asked() =~ "Do I have anything to do today?"
  end

  test "off: the server's STT takes over for the rest of the call" do
    pid = phone_call()
    ev(pid, %{"ev" => "stt", "t" => "off", "reason" => "error"})
    Process.sleep(50)
    utter(pid, 1, "Tell me a fun fact about tea.")
    assert_receive {:server_stt, "Tell me a fun fact about tea."}, 2_000
    assert asked() =~ "Tell me a fun fact about tea."
    # A final after off is ignored.
    final(pid, "Do I have anything to do today?")
    refute_receive {:turn_run, _}, 300
  end

  test "3 utterances with clear speech and no final: back to server STT (the 3rd is heard)" do
    pid = phone_call()
    utter(pid, 1, "one", 900)
    Process.sleep(450)
    # Short noise does not count.
    utter(pid, 2, "two", 200)
    Process.sleep(450)
    utter(pid, 3, "three", 900)
    Process.sleep(450)
    refute_received {:media_cmd, %{"cmd" => "stt_mode", "mode" => "server"}}
    refute_received {:server_stt, _}
    utter(pid, 4, "Tell me a fun fact about tea.", 900)
    assert_receive {:media_cmd, %{"cmd" => "stt_mode", "mode" => "server"}}, 1_000
    assert_receive {:server_stt, "Tell me a fun fact about tea."}, 2_000
    assert asked() =~ "Tell me a fun fact about tea."
  end
end
