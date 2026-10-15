defmodule RisiMe.Agent.CallRisi.HearingTest do
  @moduledoc """
  Harsha 2026-10-16 (call 56f51277, 04:43 SLT): Whisper made up "Bye-bye. Ready?", "You're on.",
  "you know", "It'll be fun." … from noise, silence and echo, and Risi answered them. The hearing
  gate (`CallRisi.Hearing`) and the session around it: made-up words get no answer and never
  swallow the real request; an unsure utterance gets one "Sorry, I didn't catch that." in a row.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{Hearing, Session}

  ## The gate

  defp seg(text, lp, nsp, cr \\ 1.2),
    do: %{
      offset_ms: 0,
      text: text,
      avg_logprob: lp,
      no_speech_prob: nsp,
      compression_ratio: cr
    }

  defp res(text, segs), do: %{text: text, speech: true, segments: segs}

  # 1 s of a tone at `amp` (16-kHz s16le).
  defp pcm(amp),
    do: for(i <- 0..15_999, into: <<>>, do: <<round(amp * :math.sin(i / 8))::little-signed-16>>)

  test "made-up tail sentences are stripped from a real request" do
    assert Hearing.strip_made_up(
             "All right. Can I have a meeting set up today for two? Bye-bye. Ready?"
           ) ==
             "All right. Can I have a meeting set up today for two?"

    assert Hearing.made_up?("You're on.")
    assert Hearing.made_up?("you know")
    assert Hearing.made_up?("Bye-bye. ")
    assert Hearing.made_up?("Thanks for watching!")
    refute Hearing.made_up?("Yeah.")
    refute Hearing.made_up?("Okay.")
    refute Hearing.made_up?("Yes, add it.")
    refute Hearing.made_up?("Do I have anything to do today?")
  end

  test "the call's made-up lines are dropped; real words are kept" do
    loud = %{pcm: pcm(6_000), speech_ms: 2_000}
    quiet = %{pcm: pcm(300), speech_ms: 2_000}

    # Short blocklisted phrases (any energy).
    assert {:drop, "blocklist"} = Hearing.judge(res("You're on.", []), loud)
    assert {:drop, "blocklist"} = Hearing.judge(res("you know", []), loud)
    # Whisper says it is not speech.
    assert {:drop, "no_speech_prob"} =
             Hearing.judge(res("It'll be fun.", [seg("It'll be fun.", -0.9, 0.82)]), loud)

    # Quiet and unsure (noise).
    long = "Yeah, I didn't know I was going to promote. And, uh... And I'd say it is."

    assert {:drop, "quiet"} = Hearing.judge(res(long, [seg(long, -0.7, 0.4)]), quiet)
    # Echo: heard while Risi spoke, no louder than her echo.
    assert {:drop, "echo"} =
             Hearing.judge(
               res("And I tend to come out. So do I.", [seg("x", -0.3, 0.05)]),
               %{pcm: pcm(1_000), speech_ms: 1_800, during_tts?: true}
             )

    # Unsure but loud: unclear.
    assert {:unclear, "low_logprob"} =
             Hearing.judge(
               res("Some murky words here", [seg("Some murky words here", -0.9, 0.1)]),
               loud
             )

    # The real request, its made-up tail stripped (per segment and per sentence).
    req = "All right. Can I have a meeting set up today for two?"

    assert {:ok, ^req} =
             Hearing.judge(
               res(req <> " Bye-bye. Ready?", [
                 seg(req, -0.25, 0.02),
                 seg("Bye-bye. Ready?", -1.3, 0.7)
               ]),
               loud
             )

    assert {:ok, ^req} = Hearing.judge(res(req <> " Bye-bye. Ready?", []), loud)
    # A loud, clear "Yeah." and a service without scores are kept.
    assert {:ok, "Yeah."} = Hearing.judge(res("Yeah.", [seg("Yeah.", -0.2, 0.05)]), loud)
    assert {:ok, "Good morning."} = Hearing.judge(res("Good morning.", []), %{})
    assert {:drop, "no_speech"} = Hearing.judge(%{text: "", speech: false}, loud)
  end

  test "energy: p90 of the frame RMS" do
    assert Hearing.energy(pcm(1_000)) in 650..760
    assert Hearing.energy(<<>>) == nil
  end

  ## The session (a replay of the 04:43 call's shape)

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
    # "en:<text>" a clear segment; "noise:<text>" Whisper on noise (no_speech 0.9);
    # "mumble:<text>" speech the model is unsure of.
    @impl true
    def transcribe(<<_hdr::binary-size(44), script::binary>>, _opts) do
      [kind, text] = String.split(script, ":", parts: 2)

      {lp, nsp} =
        case kind do
          "noise" -> {-1.1, 0.9}
          "mumble" -> {-0.95, 0.1}
          _ -> {-0.2, 0.02}
        end

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
             avg_logprob: lp,
             no_speech_prob: nsp,
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
      # Long enough for the caller's noise to arrive while Risi checks.
      Process.sleep(400)
      send(pid, {:risi_voice_answer, request_id, "I've put that in your Risi chat."})
      :ok
    end
  end

  defmodule Runs do
    def record(_row), do: :ok
  end

  setup do
    keys = [
      :risi_call_media,
      :risi_speech,
      :risi_call_llm,
      :risi_call_turn,
      :risi_call_runs,
      :risi_call_merge_ms
    ]

    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_merge_ms, -100_000)
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_llm, LLM)
    Application.put_env(:risime, :risi_call_turn, Turn)
    Application.put_env(:risime, :risi_call_runs, Runs)
    :persistent_term.put({LLM, :test}, self())

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
    %{pid: pid}
  end

  defp ev(pid, map), do: send(pid, {:call_risi_media, map})

  defp utter(pid, utt, script, speech_ms \\ 1_200) do
    ev(pid, %{"ev" => "speech_start", "speaking" => false})

    ev(pid, %{
      "ev" => "utterance",
      "utt" => utt,
      "pcm16k" => Base.encode64(script),
      "speech_ms" => speech_ms,
      "eot_wait_ms" => 400
    })
  end

  defp says(utt, acc \\ [], wait \\ 3_000) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => ^utt} = c} -> says(utt, acc ++ [c], 500)
    after
      wait -> acc
    end
  end

  defp heard(pid, cmds) do
    for c <- cmds,
        do: ev(pid, %{"ev" => "said", "seq" => c["seq"], "interrupted" => false})

    ev(pid, %{"ev" => "idle"})
  end

  defp texts(cmds), do: Enum.map(cmds, & &1["text"])

  test "made-up words during and after the check: no answer, the request is not swallowed", %{
    pid: pid
  } do
    utter(pid, 1, "en:Do I have anything to do today?")
    assert_receive {:turn_run, %{"text" => "Do I have anything to do today?"}}, 3_000
    heard(pid, says(1))

    # The request with its made-up tail, then two made-up fragments while Risi checks.
    utter(
      pid,
      2,
      "en:All right. Can I have a meeting set up today for two? Bye-bye. Ready?",
      3_500
    )

    Process.sleep(150)
    utter(pid, 3, "noise:You're on.", 600)
    utter(pid, 4, "noise:you know", 500)

    s2 = says(2)
    said = Enum.join(texts(s2), " ")
    assert said =~ "Risi chat" or said =~ "Sure"
    refute said =~ "not sure what you mean"
    assert says(3, [], 300) == [] and says(4, [], 300) == []
    heard(pid, s2)

    # Made-up lines after the answer: silence (never "That sounds nice").
    utter(pid, 5, "noise:It'll be fun.", 700)
    utter(pid, 6, "en:you know", 500)

    utter(
      pid,
      7,
      "noise:Yeah, I didn't know I was going to promote. And, uh... It's fascinating, isn't it?",
      2_500
    )

    assert says(5, [], 400) == [] and says(6, [], 400) == [] and says(7, [], 600) == []

    # A real short "Yeah." still gets its minimal answer.
    utter(pid, 8, "en:Yeah.", 400)
    assert [_ | _] = s8 = says(8)
    heard(pid, s8)

    # Unsure twice in a row: one "Sorry, I didn't catch that.", then silence.
    utter(pid, 9, "mumble:And I tend to come out", 1_400)
    assert texts(says(9)) == ["Sorry, I didn't catch that."]
    utter(pid, 10, "mumble:So do I", 1_400)
    assert says(10, [], 600) == []

    ev(pid, %{"ev" => "caller_left"})
    assert_receive {:posted, _, body, _}, 3_000
    assert body =~ "Can I have a meeting set up today for two?"

    for made_up <- ["Bye-bye", "You're on", "you know", "It'll be fun", "promote", "So do I"],
        do: refute(body =~ made_up, made_up)
  end
end
