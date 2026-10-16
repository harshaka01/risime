defmodule RisiMe.Agent.CallRisi.BrainV3Test do
  @moduledoc """
  Call Risi brain v3 (architect P0 2026-10-16, Harsha's call 75ec8683), one test per fix:

    * a message request without its text asks "What should I say to <name>?"; a text the model
      made up is never drafted (grounding);
    * "cancel that" cancels the open card for real (and says so honestly), also when the model
      wrote it as a reply;
    * a message card is read back with its real buttons (Tap Send), never "Tap Add" / "I haven't
      added it yet";
    * a cut-off utterance is held and merged with the words that follow (never decided alone);
    * an unclear utterance in Risi's echo window is dropped silently (legacy still says Sorry);
    * a reply that claims an action loses that sentence (the claim guard);
    * the model failing: the legacy brain answers.
  Pure pieces (Dialogue, Hearing, WriteTruth, ClaimGuard) are tested directly; the rest through
  the REAL session with a scripted decision model.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{Decider, Dialogue, Fillers, Hearing, Session, WriteTruth}

  ## Pure pieces

  describe "Dialogue" do
    test "grounded?: the caller's words carry the text; a made-up text fails" do
      refute Dialogue.grounded?("Hi Nimali, how are you", "Can you send a message to Nimali?")

      assert Dialogue.grounded?(
               "Good morning, Nimali. How are you doing?",
               "No, just cancel that. Just say, good morning, Nimali. How are you doing?"
             )

      # A correction uses the open card's words and the recipient's name.
      assert Dialogue.grounded?(
               "Good evening, Nimali.",
               "Actually say good evening instead. Nimali"
             )

      refute Dialogue.grounded?("", "anything")
    end

    test "unfinished?: cut-off words are held, a whole thought is not" do
      assert Dialogue.unfinished?("Do you have anything")
      assert Dialogue.unfinished?("Check my calendar and")
      assert Dialogue.unfinished?("Send a message to,")
      refute Dialogue.unfinished?("on the 18th?")
      refute Dialogue.unfinished?("This is a message just to make sure.")
      # The phone's recogniser adds no punctuation: only a dangling word counts.
      refute Dialogue.unfinished?("what is on my calendar today", false)
      assert Dialogue.unfinished?("what is on my calendar and", false)
    end

    test "the message card is read back with its real buttons" do
      line = Dialogue.message_line("Send to Nimali Perera (Private): Good morning, Nimali.")
      assert line =~ "Nimali Perera"
      assert line =~ "\"Good morning, Nimali.\""
      assert line =~ "Tap Send"
      refute line =~ "Tap Add"
      refute line =~ "added"
      assert Dialogue.ask_text("Nimali") == "What should I say to Nimali?"
    end

    test "the open state, as the model reads it" do
      d = %{
        card: %{kind: "message", to: "Nimali", text: "Hi", request_id: "r"},
        awaiting: %{slot: "message_text", to: "Nimali"}
      }

      p =
        Decider.user_prompt(%{
          now: "Friday 16 October 2026, 09:27",
          tz: "Asia/Colombo",
          dialogue: d,
          history: [%{"role" => "user", "content" => "Can you send a message to Nimali?"}],
          text: "Good morning"
        })

      assert p =~ "Open card: a message to Nimali, not sent yet: \"Hi\""
      assert p =~ "Risi is waiting for: the text of the message to Nimali"
      assert p =~ "User: Can you send a message to Nimali?"
      assert p =~ "Latest user words: \"Good morning\""
    end
  end

  describe "honest lines for a message card" do
    test "WriteTruth: card and status lines say Send, never Add" do
      line = WriteTruth.card_line("Send to Nimali Perera (Private): Hi there", ~D[2026-10-16])
      assert line =~ "Tap Send"
      refute line =~ "haven't added"
      refute line =~ "Tap Add"

      pending = fn _ -> %{state: "pending", tool: "send_message", confirmed?: false} end
      s = WriteTruth.status_line(%{request_id: "r", route: "message_write"}, :complaint, pending)
      assert s =~ "isn't sent yet" and s =~ "Tap Send"
      refute s =~ "added"

      done = fn _ -> %{state: "cancelled", tool: "send_message", confirmed?: false} end
      assert WriteTruth.status_line(%{request_id: "r"}, :claim, done) =~ "nothing was sent"

      # A calendar card keeps its own wording.
      cal = fn _ -> %{state: "pending", tool: "risi_calendar_add", confirmed?: false} end

      assert WriteTruth.status_line(%{request_id: "r", route: "calendar_write"}, :confirm, cal) =~
               "Tap Add"
    end

    test "ClaimGuard: 'I've sent it' on a pending message card becomes the Send line" do
      prev = Application.get_env(:risime, :risi_claim_results)

      on_exit(fn ->
        if prev,
          do: Application.put_env(:risime, :risi_claim_results, prev),
          else: Application.delete_env(:risime, :risi_claim_results)
      end)

      Application.put_env(:risime, :risi_claim_results, fn _ ->
        [%{write_id: "w", state: "pending", tool: "send_message", via: "ask"}]
      end)

      assert RisiMe.Agent.ClaimGuard.honest("r") ==
               "I've drafted it. Tap Send to send it, or Cancel."

      {text, :violation} =
        RisiMe.Agent.ClaimGuard.guard("I've sent it to Nimali. Tap Send to confirm.", "r", "t")

      assert text == "I've drafted it. Tap Send to send it, or Cancel."

      # The calendar wording is unchanged.
      Application.put_env(:risime, :risi_claim_results, fn _ ->
        [%{write_id: "w", state: "pending", tool: "risi_calendar_add", via: "ask"}]
      end)

      assert RisiMe.Agent.ClaimGuard.honest("r") == "I've drafted it. Tap Add to confirm."
    end
  end

  describe "Hearing.clear_speech?" do
    test "only clear speech outside Risi's echo window is worth a Sorry" do
      assert Hearing.clear_speech?(%{speech_ms: 1_200, since_risi_ms: 4_000, during_tts?: false})
      assert Hearing.clear_speech?(%{speech_ms: 1_200, since_risi_ms: nil})
      refute Hearing.clear_speech?(%{speech_ms: 1_200, since_risi_ms: 300})
      refute Hearing.clear_speech?(%{speech_ms: 1_200, since_risi_ms: -200})
      refute Hearing.clear_speech?(%{speech_ms: 1_200, during_tts?: true})
      refute Hearing.clear_speech?(%{speech_ms: 400, since_risi_ms: 9_000})
    end
  end

  test "Fillers: the neutral line names no tool and counts as a filler" do
    assert Fillers.neutral() == "One moment…"
    assert Fillers.neutral() in Fillers.all()
  end

  ## Through the real session, with a scripted decision model

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
    def transcribe(<<_hdr::binary-size(44), "unclear:", text::binary>>, _opts) do
      {:ok,
       %{
         text: text,
         language: "en",
         model: "fake",
         elapsed_ms: 5,
         speech: true,
         segments: [
           %{
             offset_ms: 0,
             text: text,
             avg_logprob: -1.1,
             no_speech_prob: 0.2,
             compression_ratio: 1.0
           }
         ]
       }}
    end

    def transcribe(<<_hdr::binary-size(44), text::binary>>, _opts),
      do: {:ok, %{text: text, language: "en", model: "fake", elapsed_ms: 5, speech: text != ""}}
  end

  defmodule LLM do
    def stream(_messages, _opts, on_delta) do
      on_delta.("Legacy here.")
      {:ok, %{model: "fake-l1", first_token_ms: 5, tokens: 1}}
    end
  end

  defmodule Script do
    @behaviour RisiMe.Agent.CallRisi.Decider
    @impl true
    def decide(input, _ctx) do
      send(:persistent_term.get({__MODULE__, :test}), {:decided, input.text, input.dialogue})

      case Map.get(:persistent_term.get({__MODULE__, :script}), input.text) do
        nil -> {:error, :model_unavailable}
        d -> {:ok, Map.merge(blank(), d)}
      end
    end

    defp blank,
      do: %{
        "tool" => "none",
        "action" => "reply",
        "cancel_open" => false,
        "request" => "",
        "say" => "",
        "text" => "",
        "to" => ""
      }
  end

  defmodule Turn do
    def run(_conv, env, rid, _user) do
      send(:persistent_term.get({Script, :test}), {:turn_run, env})
      {pid, _} = Process.get(:risi_voice_sink)

      case env[:call_send] do
        %{"to" => _, "text" => body} ->
          :persistent_term.put({__MODULE__, rid}, "pending")
          send(pid, {:risi_voice_card, rid, "Send to Nimali Perera (Private): #{body}"})

          send(
            pid,
            {:risi_voice_answer, rid, "Tap Send to send it to Nimali, or Edit to change it."}
          )

        _ ->
          send(pid, {:risi_voice_answer, rid, "Done reading."})
      end

      :ok
    end

    def state(rid) do
      case :persistent_term.get({__MODULE__, rid}, nil) do
        nil -> nil
        s -> %{state: s, confirmed?: false, tool: "send_message"}
      end
    end
  end

  defmodule Runs do
    def record(_), do: :ok
  end

  setup do
    keys = ~w(risi_call_media risi_speech risi_call_llm risi_call_turn risi_call_runs
              risi_agent_flags risi_call_write_state risi_call_decider risi_call_cancel
              risi_call_hold_ms risi_call_neutral_ms risi_contacts)a

    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
    me = self()
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_llm, LLM)
    Application.put_env(:risime, :risi_call_turn, Turn)
    Application.put_env(:risime, :risi_call_runs, Runs)
    Application.put_env(:risime, :risi_call_write_state, &Turn.state/1)
    Application.put_env(:risime, :risi_call_decider, Script)
    Application.put_env(:risime, :risi_call_hold_ms, 400)

    Application.put_env(:risime, :risi_call_cancel, fn _user, rid ->
      send(me, {:cancelled, rid})
      :persistent_term.put({Turn, rid}, "cancelled")
      :ok
    end)

    Application.put_env(:risime, :risi_agent_flags, %{
      "RISI_CALL_RISI" => "on",
      "RISI_AGENTS" => "on"
    })

    :persistent_term.put({Script, :test}, me)
    :persistent_term.put({Script, :script}, %{})

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

  defp script(map), do: :persistent_term.put({Script, :script}, map)

  defp call!(v3? \\ true) do
    args = %{
      conv: "grp:risi-chat",
      call_id: Ecto.UUID.generate(),
      user_id: Ecto.UUID.generate(),
      device_id: Ecto.UUID.generate(),
      room: "room1",
      risi_token: "tok",
      worker_url: "ws://127.0.0.1:7880",
      test: self(),
      tools?: true,
      cal?: false,
      stt?: false,
      v3?: v3?,
      caps: ~w(risi_calendar_check send_message),
      post: fn _c, _b, _r -> :ok end
    }

    {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
    ev(pid, %{"ev" => "joined"})
    ev(pid, %{"ev" => "caller_joined"})
    assert_receive {:media_cmd, %{"cmd" => "say", "seq" => 1}}
    ev(pid, %{"ev" => "said", "seq" => 1, "utt" => 0, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})
    pid
  end

  defp ev(pid, m), do: send(pid, {:call_risi_media, m})

  defp utter(pid, utt, text, speech_ms \\ 1_200) do
    ev(pid, %{
      "ev" => "utterance",
      "utt" => utt,
      "pcm16k" => Base.encode64(text),
      "speech_ms" => speech_ms,
      "eot_wait_ms" => 300
    })
  end

  # What Risi said for `utt` (not the holding lines), once it is done.
  defp said(pid, utt, acc \\ [], wait \\ 3_000) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => ^utt} = c} ->
        ev(pid, %{"ev" => "said", "seq" => c["seq"], "utt" => utt, "interrupted" => false})
        said(pid, utt, acc ++ [c["text"]], 400)
    after
      wait ->
        ev(pid, %{"ev" => "idle"})
        acc |> Enum.reject(&(&1 in Fillers.all())) |> Enum.join(" ")
    end
  end

  test "a message request without its text asks for it; the next words are the text" do
    pid = call!()

    script(%{
      "Can you send a message to Nimali?" => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "Nimali",
        # risi-l1 made this up on Harsha's call: never drafted.
        "text" => "Hi Nimali, how are you"
      },
      "Good morning, and I'm testing this." => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "Nimali",
        "text" => "Good morning, and I'm testing this."
      }
    })

    utter(pid, 1, "Can you send a message to Nimali?")
    assert said(pid, 1) == "What should I say to Nimali?"
    refute_received {:turn_run, _}

    utter(pid, 2, "Good morning, and I'm testing this.")
    assert_receive {:decided, "Good morning, and I'm testing this.", d}, 2_000
    assert %{slot: "message_text", to: "Nimali"} = d.awaiting
    assert_receive {:turn_run, env}, 2_000
    assert env[:call_send] == %{"to" => "Nimali", "text" => "Good morning, and I'm testing this."}
    line = said(pid, 2)
    assert line =~ "Tap Send"
    refute line =~ "Tap Add"
    refute line =~ "haven't added"
  end

  test "'you' or 'Risi' is no recipient: Risi asks who, keeps the text, then drafts it" do
    pid = call!()

    script(%{
      # The device test: Whisper lost the name ("send a message to, … hey, how are you doing?").
      "Send a message to you saying hey, how are you doing?" => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "you",
        "text" => "hey, how are you doing?"
      },
      "To Nimali." => %{"tool" => "message_draft", "action" => "tool", "to" => "Nimali"}
    })

    utter(pid, 1, "Send a message to you saying hey, how are you doing?")
    assert said(pid, 1) == "Who should I send it to?"
    refute_received {:turn_run, _}

    utter(pid, 2, "To Nimali.")

    assert_receive {:turn_run,
                    %{call_send: %{"to" => "Nimali", "text" => "Hey, how are you doing?"}}},
                   2_000

    assert said(pid, 2) =~ "Tap Send"
  end

  test "'cancel that' cancels the open card for real, also when the model wrote a reply" do
    pid = call!()

    script(%{
      "Tell Nimali good morning." => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "Nimali",
        "text" => "Good morning"
      },
      # The model's mix on Harsha's call: a reply that claims the cancel.
      "Cancel that." => %{
        "tool" => "cancel_open",
        "action" => "reply",
        "cancel_open" => true,
        "say" => "Okay, I've cancelled that."
      }
    })

    utter(pid, 1, "Tell Nimali good morning.")
    assert_receive {:turn_run, %{"request_id" => rid}}, 2_000
    assert said(pid, 1) =~ "Tap Send"

    utter(pid, 2, "Cancel that.")

    assert_receive {:decided, "Cancel that.", %{card: %{request_id: ^rid, kind: "message"}}},
                   2_000

    assert_receive {:cancelled, ^rid}, 2_000
    line = said(pid, 2)
    assert line == "Okay, cancelled. Nothing was sent."
    refute line =~ "I've cancelled"
  end

  test "'cancel that and …' cancels, then the new request in the same words runs" do
    pid = call!()

    script(%{
      "Tell Nimali good morning." => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "Nimali",
        "text" => "Good morning"
      },
      "No, cancel that and just say good evening to Nimali." => %{
        "tool" => "message_draft",
        "action" => "tool",
        "cancel_open" => true,
        "to" => "Nimali",
        "text" => "Good evening"
      }
    })

    utter(pid, 1, "Tell Nimali good morning.")
    assert_receive {:turn_run, %{"request_id" => rid}}, 2_000
    said(pid, 1)

    utter(pid, 2, "No, cancel that and just say good evening to Nimali.")
    assert_receive {:cancelled, ^rid}, 2_000
    assert_receive {:turn_run, %{call_send: %{"text" => "Good evening"}}}, 2_000
    assert said(pid, 2) =~ "\"Good evening\""
  end

  test "a cut-off utterance is held and merged: the model never decides the fragment alone" do
    pid = call!()

    script(%{
      "Do you have anything on the 18th?" => %{
        "tool" => "calendar_read",
        "action" => "tool",
        "request" => "Do you have anything on the 18th?"
      }
    })

    utter(pid, 1, "Do you have anything", 800)
    Process.sleep(150)
    utter(pid, 2, "on the 18th?", 600)
    assert_receive {:decided, "Do you have anything on the 18th?", _}, 3_000
    refute_received {:decided, "Do you have anything", _}
    assert_receive {:turn_run, %{"__schedule" => %{from: from}}}, 2_000
    assert from.day == 18
    said(pid, 2)
  end

  test "unclear words in Risi's echo window: v3 is silent, the legacy brain says Sorry" do
    for {v3?, expect} <- [{true, ""}, {false, "Sorry, I didn't catch that."}] do
      pid = call!(v3?)
      # Right after Risi's greeting finished (its echo window).
      utter(pid, 1, "unclear:and the rest of it", 1_200)
      assert said(pid, 1, [], 1_500) == expect
      ev(pid, %{"ev" => "caller_left"})
    end
  end

  test "a reply that claims an action loses that sentence" do
    pid = call!()

    script(%{
      "Great, that works for me." => %{
        "action" => "reply",
        "say" => "I've sent it to Nimali. Anything else I can help with?"
      }
    })

    utter(pid, 1, "Great, that works for me.")
    assert said(pid, 1) == "Anything else I can help with?"
  end

  test "the decision model fails: the legacy brain answers the turn" do
    pid = call!()
    script(%{})
    utter(pid, 1, "How are you doing?")
    assert_receive {:decided, "How are you doing?", _}, 2_000
    assert said(pid, 1) == "Legacy here."
  end

  ## Harsha's call 97edc028 (06:04Z) and the architect's 11:45 fix: the message loop

  describe "find_contact" do
    alias RisiMe.Agent.FindContact

    @people [
      {"1", "Shenika Fernando"},
      {"2", "Shanika Silva"},
      {"3", "Shirazi Hameed"},
      {"4", "Nimali Perera"}
    ]

    test "a misheard name: one clear match is used, 2–3 near ones are offered" do
      assert {:one, %{name: "Nimali Perera"}} =
               FindContact.decide(FindContact.find("Nima Lee", @people))

      # "Shani Kao" (Whisper) sounds like both: ask once.
      assert {:pick, [_, _] = two} = FindContact.decide(FindContact.find("Shani Kao", @people))
      assert Enum.map(two, & &1.name) |> Enum.sort() == ["Shanika Silva", "Shenika Fernando"]

      # With one of them only, it's a clear match (no question).
      one = Enum.reject(@people, &(elem(&1, 0) == "2"))

      assert {:one, %{name: "Shenika Fernando"}} =
               FindContact.decide(FindContact.find("Shani Kao", one))
    end

    test "spelled letter by letter, exact, Sinhala script, and never 'none' at ≥ 0.6" do
      assert {:one, %{name: "Shenika Fernando", score: 1.0}} =
               FindContact.decide(FindContact.find("S-H-E-N-I-K-A", @people))

      assert {:one, %{name: "Shenika Fernando"}} =
               FindContact.decide(FindContact.find("Shenika", @people))

      assert {:one, %{name: "Nimali Perera"}} =
               FindContact.decide(FindContact.find("නිමාලි", @people))

      # The §34.5 case: "Shutazi" is offered as Shirazi, never "not found".
      assert {:pick, [%{name: "Shirazi Hameed"}]} =
               FindContact.decide(FindContact.find("Shutazi", @people))

      assert FindContact.decide(FindContact.find("Bartholomew", @people)) == :none
    end
  end

  describe "Dialogue (architect 11:45)" do
    test "grounding is soft: the model's honest rewording of 'tell her that …' passes" do
      said =
        "Tell her that I called her a couple of times and then she did not answer. " <>
          "And I'll call her back or call me back."

      assert Dialogue.grounded?(
               "I called you a couple of times but you didn't answer. Please call me back.",
               said
             )

      refute Dialogue.grounded?("Hi Nimali, how are you", "Can you send a message to Nimali?")
    end

    test "strip_lead takes only the lead-in off the caller's words" do
      assert Dialogue.strip_lead("Tell her that I called her a couple of times.") ==
               "I called her a couple of times."

      assert Dialogue.strip_lead("Just say, good morning.") == "Good morning."
      assert Dialogue.strip_lead("Good morning, Nimali.") == "Good morning, Nimali."
    end

    test "a non-answer, and what Risi says instead of the same question" do
      for t <- ["Hello", "Hello?", "Sorry?", "What?", "Can you hear me?"],
          do: assert(Dialogue.non_answer?(t), t)

      refute Dialogue.non_answer?("Hello Nimali, how are you?")

      d = %{awaiting: %{slot: "message_text", to: "Nimali Perera", question: "?"}}
      line = Dialogue.heard_line("Hello", d)
      assert line =~ ~s(I heard "Hello")
      assert line =~ "or say cancel"
      refute line =~ "What should I say"
    end
  end

  test "call 7: the name is looked up when given, the text is kept, 'Hello' keeps the task" do
    Application.put_env(:risime, :risi_contacts, fn _ ->
      [{"u1", "Nimali Perera"}, {"u2", "Ruwan Silva"}]
    end)

    pid = call!()
    text = "Tell her that I called her a couple of times and then she did not answer."

    script(%{
      "So can you send the message to her on a private chat?" => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "her"
      },
      # risi-l1 talked back instead of the tool: the words are still the name.
      "Nima Lee." => %{"action" => "ask", "say" => "Got it, Nima Lee. What should it say?"},
      text => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "Nima Lee",
        "text" => "I called you a couple of times but you didn't answer."
      }
    })

    utter(pid, 1, "So can you send the message to her on a private chat?")
    assert said(pid, 1) == "Who should I send it to?"

    utter(pid, 2, "Nima Lee.")
    assert said(pid, 2) == "What should I say to Nimali Perera?"
    refute_received {:turn_run, _}

    # A non-answer: what Risi heard and a choice (never the question again); the task stays.
    utter(pid, 3, "Hello?")
    line = said(pid, 3)
    assert line =~ ~s(I heard "Hello?")
    refute line =~ "What should I say"

    utter(pid, 4, text)
    assert_receive {:turn_run, env}, 3_000

    assert env[:call_send] == %{
             "to" => "Nimali Perera",
             "text" => "I called you a couple of times but you didn't answer."
           }

    assert said(pid, 4) =~ "Tap Send"
  end

  test "two near names: asked once with the text kept; the name picks; no holding line" do
    Application.put_env(:risime, :risi_contacts, fn _ ->
      [{"u1", "Shenika Fernando"}, {"u2", "Shanika Silva"}]
    end)

    pid = call!()

    script(%{
      "Send a message to Shani Kao saying I'll be late." => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "Shani Kao",
        "text" => "I'll be late."
      }
    })

    utter(pid, 1, "Send a message to Shani Kao saying I'll be late.")
    line = said(pid, 1)
    assert line =~ "Did you mean"
    assert line =~ "Shenika Fernando" and line =~ "Shanika Silva"
    refute line =~ "One moment"

    # The name alone answers (no model call needed).
    utter(pid, 2, "Shenika.")
    assert_receive {:turn_run, env}, 3_000
    assert env[:call_send] == %{"to" => "Shenika Fernando", "text" => "I'll be late."}
  end

  test "a reworded text that doesn't match is read back and confirmed; yes drafts it" do
    Application.put_env(:risime, :risi_contacts, fn _ -> [{"u1", "Nimali Perera"}] end)
    pid = call!()

    script(%{
      "Message Nimali." => %{"tool" => "message_draft", "action" => "tool", "to" => "Nimali"},
      "Tell her the meeting moved." => %{
        "tool" => "message_draft",
        "action" => "tool",
        "to" => "Nimali",
        "text" => "Hi Nimali, our sync has been rescheduled to Friday."
      }
    })

    utter(pid, 1, "Message Nimali.")
    assert said(pid, 1) == "What should I say to Nimali Perera?"

    utter(pid, 2, "Tell her the meeting moved.")
    line = said(pid, 2)
    assert line =~ "I'll send Nimali Perera:"
    assert line =~ "Shall I put it on a card?"
    refute_received {:turn_run, _}

    utter(pid, 3, "Yes.")
    assert_receive {:turn_run, %{call_send: %{"to" => "Nimali Perera"}}}, 3_000
  end

  test "the model asking the same question twice: the second time Risi says what it heard" do
    pid = call!()
    q = "Which meeting do you mean?"

    script(%{
      "Move the meeting." => %{"action" => "ask", "say" => q},
      "The one with the team." => %{"action" => "ask", "say" => q}
    })

    utter(pid, 1, "Move the meeting.")
    assert said(pid, 1) == q
    utter(pid, 2, "The one with the team.")
    line = said(pid, 2)
    assert line =~ ~s(I heard "The one with the team.")
    refute line == q
  end

  test "list_contacts reads names only" do
    Application.put_env(:risime, :risi_contacts, fn _ ->
      [{"u1", "Nimali Perera"}, {"u2", "Ruwan Silva"}]
    end)

    pid = call!()

    script(%{
      "Tell me 10 names in my contacts." => %{"tool" => "list_contacts", "action" => "tool"}
    })

    utter(pid, 1, "Tell me 10 names in my contacts.")
    assert said(pid, 1) == "Here are 2 of your contacts: Nimali Perera and Ruwan Silva."
  end
end
