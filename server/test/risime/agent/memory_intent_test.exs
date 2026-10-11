defmodule RisiMe.Agent.MemoryIntentTest do
  @moduledoc """
  v1.36 §35.2: the memory route of the deterministic router, in English, Sinhala, Tamil and
  Singlish (Risi Brain v2 step 2). Today is Sun 11 Oct 2026 (the §34 fixture's clock).
  """
  use ExUnit.Case, async: true

  alias RisiMe.Agent.{MemoryIntent, ScheduleIntent}

  @today ~D[2026-10-11]
  @month %{from: ~D[2026-10-01], to: ~D[2026-10-12]}
  @last_week %{from: ~D[2026-09-28], to: ~D[2026-10-05]}

  @cases [
    # {text, mode, range, people}
    {"summarise the work done this month", :summarise, @month, []},
    {"Summarize what we did last week", :summarise, @last_week, []},
    {"what did I agree with Upali last week", :agreed, @last_week, ["Upali"]},
    {"what's pending with Shenika", :pending, nil, ["Shenika"]},
    {"what is still pending with Shenika Fernando?", :pending, nil, ["Shenika Fernando"]},
    {"what did we decide about the quote yesterday?", :agreed,
     %{from: ~D[2026-10-10], to: ~D[2026-10-11]}, []},
    {"recap of the last 7 days", :summarise, %{from: ~D[2026-10-05], to: ~D[2026-10-12]}, []},
    {"summary for September", :summarise, %{from: ~D[2026-09-01], to: ~D[2026-10-01]}, []},
    # Sinhala
    {"මේ මාසේ කරපු වැඩ සාරාංශ කරන්න", :summarise, @month, []},
    {"ගිය සතියේ Upali එක්ක මම මොනවද එකඟ වුණේ?", :agreed, @last_week, ["Upali"]},
    {"Shenika එක්ක ඉතිරි වෙලා තියෙන වැඩ මොනවද?", :pending, nil, ["Shenika"]},
    # Tamil
    {"இந்த மாதம் செய்த வேலையைச் சுருக்கமாகச் சொல்லுங்கள்", :summarise, @month, []},
    {"கடந்த வாரம் Upali உடன் நான் என்ன ஒப்புக்கொண்டேன்?", :agreed, @last_week, ["Upali"]},
    {"Shenika உடன் நிலுவையில் உள்ளவை என்ன?", :pending, nil, ["Shenika"]},
    # Singlish
    {"me mase karapu wada summary ekak denna", :summarise, @month, []},
    {"Upali ekka giya sathiye mokakda agree une?", :agreed, @last_week, ["Upali"]},
    {"Shenika ekka pending monawada?", :pending, nil, ["Shenika"]}
  ]

  test "memory questions route to the memory tools with range and people (en, si, ta, Singlish)" do
    for {text, mode, range, people} <- @cases do
      assert {:memory, m} = ScheduleIntent.classify(text, @today), text
      assert m.mode == mode, text
      assert m.range == range, text
      assert m.people == people, text
      assert m.query == text
    end
  end

  test "not memory: summarise this chat, schedule questions, writes" do
    for text <- [
          "summarise this chat",
          "summarise",
          "what's on this week",
          "what appointment do I have tomorrow",
          "remind me tomorrow at 9 to call Kamal",
          "හෙට මට තියෙන appointments මොනවද?",
          "நாளை எனக்கு என்ன சந்திப்புகள் உள்ளன?"
        ] do
      refute match?({:memory, _}, ScheduleIntent.classify(text, @today)), text
    end
  end

  test "the §34 fixture still routes exactly as before" do
    fixture =
      Path.expand("../../../../contract/v1/risi_routing_cases.json", __DIR__)
      |> File.read!()
      |> Jason.decode!()

    for c <- fixture["routing"] do
      got =
        case ScheduleIntent.classify(c["text"], @today) do
          {:schedule_read, _} -> "schedule_read"
          :risi_items -> "risi_items"
          :none -> "none"
          {:memory, _} -> "memory"
        end

      assert got == c["expect"], c["text"]
    end
  end

  test "people: pronouns, months and stop words are never names" do
    assert MemoryIntent.people("what did we agree with them in October") == []
    assert MemoryIntent.people("ඔයා එක්ක මම කතා කරපු දේ") == []
  end
end
