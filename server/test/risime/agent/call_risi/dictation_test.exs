defmodule RisiMe.Agent.CallRisi.DictationTest do
  @moduledoc """
  Harsha's call 22e016e0 (16 Oct 10:20Z) and the architect's 16:05 entry, the pure parts:
  dictation state (`Dictation`), the read tools' lines (`Reads`), find_contact with the call's
  context (`BrainV3.contact/3`), the session's dictation mode (no turn per pause, at most one
  "Mm-hm", silence ends it) and the v1.55 §41.11 voice send lines.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{BrainV3, Decider, Dialogue, Dictation, Reads, Session}

  describe "Dictation state" do
    test "pieces collect as message words; noise is dropped; a restart replaces them" do
      d = Dictation.new(%{to: "Nimali Perera", parts: ["Hi, Nimali."]})

      d =
        d |> Dictation.add("So, how are you?", 3) |> Dictation.add("You know, pissing greasy.", 4)

      assert Dictation.pending(d) == {"So, how are you? You know, pissing greasy.", 2}

      assert {:more, d2} =
               Dictation.apply(
                 d,
                 1,
                 {:ok, %{kind: "more", text: "how are you?", fixed: ""}},
                 false
               )

      assert Dictation.texts(d2) == ["Hi, Nimali.", "how are you?"]

      assert {:more, d3} =
               Dictation.apply(d2, 1, {:ok, %{kind: "noise", text: "", fixed: ""}}, false)

      assert d3.new == [] and Dictation.texts(d3) == Dictation.texts(d2)

      # A text the model made up for a piece is never used: the words as said are.
      d4 = Dictation.add(d3, "Bring the keys.", 5)

      assert {:more, d5} =
               Dictation.apply(
                 d4,
                 1,
                 {:ok, %{kind: "more", text: "Bring cake.", fixed: ""}},
                 false
               )

      assert List.last(Dictation.texts(d5)) == "Bring the keys."

      d6 = Dictation.add(d5, "Let me start again. Hi Nimali, call me.", 6)
      r = {:ok, %{kind: "restart", text: "Hi Nimali, call me.", fixed: ""}}
      assert {:more, d7} = Dictation.apply(d6, 1, r, false)
      assert Dictation.texts(d7) == ["Hi Nimali, call me."]
    end

    test "an end signal finishes with the pieces; words after it keep listening" do
      d =
        Dictation.new(%{to: "Nimali", parts: ["Hi Nimali."]})
        |> Dictation.add("I'm late. That's it.", 2)

      r = {:ok, %{kind: "end", text: "I'm late.", fixed: ""}}

      assert {:finish, _, %{text: "Hi Nimali. I'm late.", raw: raw, fixed: ""}} =
               Dictation.apply(d, 1, r, false)

      assert raw =~ "That's it"

      d = Dictation.add(d, "Oh, and bring the keys.", 3)
      assert {:more, d} = Dictation.apply(d, 1, r, false)
      assert d.new == ["Oh, and bring the keys."]
    end

    test "an announced dictation doesn't end on the model's guess, only on an end signal" do
      d =
        Dictation.new(%{to: "Nimali", parts: ["Hi Nimali."]})
        |> Dictation.add("See you at six.", 2)

      r = {:ok, %{kind: "end", text: "See you at six.", fixed: ""}}
      assert {:more, _} = Dictation.apply(d, 1, r, false)
      # Asked for the text ("What should I say to …?"), a whole answer ends it.
      d = %{d | asked?: true}
      assert {:finish, _, %{text: "Hi Nimali. See you at six."}} = Dictation.apply(d, 1, r, false)
    end

    test "a misheard word: the corrected version too" do
      d =
        Dictation.new(%{to: "Nimali"})
        |> Dictation.add("See whether a receipt can send it. Done.", 2)

      r =
        {:ok,
         %{
           kind: "end",
           text: "See whether a receipt can send it.",
           fixed: "See whether Risi can send it."
         }}

      assert {:finish, _,
              %{
                text: "See whether a receipt can send it.",
                fixed: "See whether Risi can send it."
              }} =
               Dictation.apply(d, 1, r, false)
    end

    test "an instruction leaves dictation with its words" do
      d =
        Dictation.new(%{to: "Nimali", asked?: true})
        |> Dictation.add("No, read me her messages.", 2)

      assert {:instruction, _, "No, read me her messages."} =
               Dictation.apply(d, 1, {:ok, %{kind: "instruction", text: "", fixed: ""}}, false)
    end

    test "silence ends it even when the model fails" do
      d =
        Dictation.new(%{to: "Nimali", parts: ["Hi Nimali."]})
        |> Dictation.add("See you at six.", 2)

      assert {:finish, _,
              %{text: "Hi Nimali. See you at six.", raw: "Hi Nimali. See you at six."}} =
               Dictation.apply(d, 1, {:error, :x}, true)

      assert {:finish, _, %{text: "Hi Nimali."}} =
               Dictation.close(Dictation.new(%{parts: ["Hi Nimali."]}))
    end

    test "the dictation prompt shows what was said and what is new" do
      p =
        Decider.user_prompt(%{
          mode: :dictation,
          dialogue: %{dictation: %{to: "Nimali Perera", asked?: true}},
          parts: ["Hi, Nimali."],
          text: "That's it.",
          ended?: false
        })

      assert p =~ "The message is for: Nimali Perera"
      assert p =~ "Risi asked what the message should say"
      assert p =~ "\"Hi, Nimali.\""
      assert p =~ "newest words: \"That's it.\""
    end
  end

  describe "find_contact with the call's context (bug B)" do
    @people [
      {"f-nimali", "Nimali Perera"},
      {"f-kasun", "Kasun Senaratne"},
      {"f-kamal", "Kamal Jayasuriya"}
    ]

    test "a near name goes to this call's recipient, never silently to someone else" do
      # Without context "Senarali" is nearer Kasun Senaratne; Nimali was this call's recipient.
      refute match?({:one, %{name: "Nimali Perera"}}, BrainV3.contact("Senarali", @people, %{}))

      assert {:one, %{name: "Nimali Perera"}} =
               BrainV3.contact("Senarali", @people, %{people: ["Nimali Perera"], pinned: nil})

      # The pinned one (the caller corrected to her) wins too.
      assert {:one, %{name: "Nimali Perera"}} =
               BrainV3.contact("Senarali", @people, %{
                 people: ["Kasun Senaratne"],
                 pinned: "Nimali Perera"
               })

      # An exact other name is that person.
      assert {:one, %{name: "Kasun Senaratne"}} =
               BrainV3.contact("Kasun", @people, %{people: ["Nimali Perera"], pinned: nil})
    end

    test "the call's people, as the model reads them" do
      d =
        Dialogue.new()
        |> Dialogue.add_person("Kasun Senaratne")
        |> Dialogue.add_person("Nimali Perera", true)

      assert Dialogue.focus(d) == "Nimali Perera"

      assert Dialogue.people_line(d) =~
               "in focus: Nimali Perera (Nimali Perera confirmed by the user)"
    end
  end

  describe "lines" do
    test "read-back of a long draft is one line with its start" do
      long = Enum.map_join(1..40, " ", &"w#{&1}")
      line = Dialogue.message_line("Send to Nimali Perera (Official): " <> long)
      assert line =~ "w22 …"
      refute line =~ "w23"
      assert line =~ "18 more words"

      assert Dialogue.send_question("Nimali", "See you at six.") ==
               "I'll send Nimali on Official: \"See you at six.\" Send it?"
    end

    test "yes, no and fix" do
      assert Dialogue.fix_yes?("Yes, fix them.")
      assert Dialogue.fix_yes?("please correct it")
      refute Dialogue.fix_yes?("No, don't fix it")
      refute Dialogue.yes?("Yes, fix them.")
      assert Dialogue.yes?("Yes, send it.")
    end

    test "a message card's tab" do
      assert %{tab: "official", to: "Nimali Perera"} =
               Dialogue.message_parts("Send to Nimali Perera (Official): Hi")
    end
  end

  describe "read tools" do
    defmodule Data do
      @behaviour RisiMe.Agent.CallRisi.Reads
      @impl true
      def messages(_user, _since), do: :persistent_term.get({__MODULE__, :m}, [])
      @impl true
      def commitments(_user), do: :persistent_term.get({__MODULE__, :c}, [])
    end

    setup do
      prev = Application.get_env(:risime, :risi_call_reads)
      prev_c = Application.get_env(:risime, :risi_contacts)
      Application.put_env(:risime, :risi_call_reads, Data)

      Application.put_env(:risime, :risi_contacts, fn _ ->
        [{"f-nimali", "Nimali Perera"}, {"f-ruwan", "Ruwan Silva"}]
      end)

      on_exit(fn ->
        for {k, v} <- [risi_call_reads: prev, risi_contacts: prev_c],
            do:
              if(v,
                do: Application.put_env(:risime, k, v),
                else: Application.delete_env(:risime, k)
              )
      end)

      :ok
    end

    @now ~U[2026-10-16 10:00:00Z]
    defp msg(from, mins, text, chat \\ "c1"),
      do: %{
        sender_id: from,
        conversation_id: chat,
        at: DateTime.add(@now, -mins * 60),
        text: text,
        from_risi: false
      }

    test "did she reply: yes, not yet, nothing; Private is never read" do
      n = %{user_id: "f-nimali", name: "Nimali Perera"}

      :persistent_term.put({Data, :m}, [
        msg("me", 30, "Home time?"),
        msg("f-nimali", 8, "Around six.")
      ])

      line = Reads.read_messages("me", n, now: @now, tz: "UTC")
      assert line =~ "Yes, Nimali replied"
      assert line =~ "\"Around six.\""

      :persistent_term.put({Data, :m}, [msg("f-nimali", 60, "Ok."), msg("me", 30, "Home time?")])
      assert Reads.read_messages("me", n, now: @now, tz: "UTC") =~ "Not yet."

      :persistent_term.put({Data, :m}, [])

      assert Reads.read_messages("me", n, now: @now, tz: "UTC") =~
               "I don't see any Official messages from Nimali"

      assert Reads.read_messages("me", n, now: @now, tz: "UTC", private?: true) =~
               "can't see your Private chats"
    end

    test "any new messages: by sender, the latest quoted" do
      :persistent_term.put({Data, :m}, [
        msg("f-ruwan", 50, "Car's ready.", "c2"),
        msg("f-nimali", 9, "Around six.")
      ])

      line = Reads.read_messages("me", nil, now: @now, tz: "UTC")
      assert line =~ "1 from Ruwan" and line =~ "1 from Nimali"
      assert line =~ "The latest, from Nimali"
    end

    test "promises by a person and my promises (the ledger, never the calendar)" do
      :persistent_term.put({Data, :c}, [
        %{owner_id: "f-ruwan", counterpart_ids: ["me"], text: "Deliver the computer", due: nil},
        %{owner_id: "me", counterpart_ids: ["f-ruwan"], text: "Pay the deposit", due: nil}
      ])

      line = Reads.promises_by("me", %{user_id: "f-ruwan", name: "Ruwan Silva"}, tz: "UTC")
      assert line =~ "Ruwan's open promises to you: Deliver the computer"
      assert line =~ "Yours to Ruwan: Pay the deposit"
      assert Reads.my_promises("me", tz: "UTC") =~ "Pay the deposit"
      refute Reads.my_promises("me", tz: "UTC") =~ "calendar"
    end
  end

  describe "session dictation mode" do
    defmodule Media do
      @behaviour RisiMe.Agent.CallRisi.Media
      @impl true
      def open(_owner, args), do: {:ok, args.test}
      @impl true
      def command(test, cmd), do: send(test, {:media_cmd, cmd}) && :ok
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
      def transcribe(<<_hdr::binary-size(44), text::binary>>, _opts),
        do: {:ok, %{text: text, language: "en", model: "fake", elapsed_ms: 5, speech: text != ""}}
    end

    # The dictation check: "more" until "that's it"; the call's first turn starts the dictation.
    defmodule Decider do
      @behaviour RisiMe.Agent.CallRisi.Decider
      @impl true
      def decide(%{mode: :dictation} = i, _ctx) do
        send(:persistent_term.get({__MODULE__, :test}), {:check, i.text})

        if i.text =~ ~r/that's it/i or i[:ended?],
          do: {:ok, %{"tool" => "end", "text" => Enum.join(i.parts, " ")}},
          else: {:ok, %{"tool" => "more"}}
      end

      def decide(_i, _ctx),
        do: {:ok, %{"tool" => "dictate", "action" => "tool", "to" => "Nimali", "text" => ""}}
    end

    setup do
      keys =
        ~w(risi_call_media risi_speech risi_call_decider risi_contacts risi_call_dict_pause_ms
                risi_call_dict_mmhm_ms risi_call_dict_silence_ms risi_call_runs risi_agent_flags risi_call_turn
                risi_call_write_state)a

      prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
      Application.put_env(:risime, :risi_call_media, Media)
      Application.put_env(:risime, :risi_speech, Speech)
      Application.put_env(:risime, :risi_call_decider, Decider)
      Application.put_env(:risime, :risi_contacts, fn _ -> [{"f-nimali", "Nimali Perera"}] end)
      Application.put_env(:risime, :risi_call_dict_pause_ms, 100)
      Application.put_env(:risime, :risi_call_dict_mmhm_ms, 400)
      Application.put_env(:risime, :risi_call_dict_silence_ms, 1_200)
      Application.put_env(:risime, :risi_call_runs, RisiMe.Agent.CallRisi.DictationTest.Runs)

      Application.put_env(:risime, :risi_agent_flags, %{
        "RISI_CALL_RISI" => "on",
        "RISI_CALL_BRAIN" => "v3"
      })

      :persistent_term.put({Decider, :test}, self())
      :persistent_term.put({RisiMe.Agent.CallRisi.DictationTest.Turn, :test}, self())
      Application.put_env(:risime, :risi_call_turn, RisiMe.Agent.CallRisi.DictationTest.Turn)

      Application.put_env(:risime, :risi_call_write_state, fn _ ->
        %{state: "pending", tool: "send_message", confirmed?: false}
      end)

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

    defp utter(pid, utt, text),
      do:
        send(
          pid,
          {:call_risi_media,
           %{
             "ev" => "utterance",
             "utt" => utt,
             "pcm16k" => Base.encode64(text),
             "speech_ms" => 900,
             "eot_wait_ms" => 300
           }}
        )

    defp says(acc \\ [], wait \\ 600) do
      receive do
        {:media_cmd, %{"cmd" => "say"} = c} ->
          # Each piece heard in full, then the worker goes idle.
          pid = :persistent_term.get({__MODULE__, :pid})

          send(
            pid,
            {:call_risi_media, %{"ev" => "said", "seq" => c["seq"], "interrupted" => false}}
          )

          send(pid, {:call_risi_media, %{"ev" => "idle"}})
          says(acc ++ [c["text"]], wait)
      after
        wait -> acc
      end
    end

    test "pauses start no turn; one check per pause; one Mm-hm; silence ends it with a read-back" do
      me = self()

      args = %{
        conv: "grp:risi-chat",
        call_id: Ecto.UUID.generate(),
        user_id: Ecto.UUID.generate(),
        device_id: Ecto.UUID.generate(),
        room: "r",
        risi_token: "t",
        worker_url: "ws://x",
        test: me,
        metrics_to: me,
        tools?: true,
        cal?: false,
        stt?: false,
        v3?: true,
        post: fn _, _, _ -> :ok end
      }

      {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
      :persistent_term.put({__MODULE__, :pid}, pid)
      send(pid, {:call_risi_media, %{"ev" => "joined"}})
      send(pid, {:call_risi_media, %{"ev" => "caller_joined"}})
      assert ["Hi, it's Risi. How can I help?"] = says()

      utter(pid, 1, "Take this message down for Nimali.")
      assert [l | _] = says()
      assert l =~ "Go ahead"

      utter(pid, 2, "Hi Nimali, how are you?")
      Process.sleep(50)
      utter(pid, 3, "Are you going home or staying at work?")
      assert_receive {:check, _}, 1_000
      # Nothing said for the pieces; then exactly one "Mm-hm." in the pause.
      heard = says([], 1_500)
      # Exactly one "Mm-hm." in the pause, then (silence) the composed text read back once.
      assert ["Mm-hm." | rest] = heard
      assert [read | _] = Enum.reject(rest, &(&1 in RisiMe.Agent.CallRisi.Fillers.all()))
      assert Enum.count(heard, &(&1 == "Mm-hm.")) == 1
      assert read =~ "Here's your message to Nimali Perera"
      assert_received {:drafted, "Nimali Perera", text}
      assert text == "Hi Nimali, how are you? Are you going home or staying at work?"
      refute_received {:call_risi_turn, %{status: :replaced}}
      send(pid, {:call_risi_media, %{"ev" => "caller_left"}})
    end
  end

  defmodule Runs do
    def record(_row), do: :ok
  end

  # The §25 turn: a message card with the words it was given.
  defmodule Turn do
    def run(_conv, env, rid, _user) do
      {pid, _} = Process.get(:risi_voice_sink)
      %{"to" => to, "text" => t} = env[:call_send]
      send(:persistent_term.get({__MODULE__, :test}), {:drafted, to, t})
      send(pid, {:risi_voice_card, rid, "Send to #{to} (Private): #{t}"})
      send(pid, {:risi_voice_answer, rid, "Drafted."})
      :ok
    end
  end
end
