defmodule RisiMe.Agent.VoiceTranslatePivotTest do
  @moduledoc """
  Hotfix 2026-10-15 (Harsha): Tamil → Sinhala came back as the Tamil sounds in Sinhala letters.
  Between Sinhala and Tamil the translation goes through English (meaning, never sounds).
  """
  use RisiMe.DataCase, async: false

  alias RisiMe.Agent.VoiceTranslate

  @moduletag capture_log: true

  @ta "நாளைக்கு வெள்ளிக்கிழமை, எனக்கு ஒரு திருமணம் இருக்கிறது."
  @en "Tomorrow is Friday, I have a wedding to attend."
  @si "හෙට සිකුරාදා, මට මඟුල් ගෙදරකට යන්න තියෙනවා."

  defp system(body), do: body["messages"] |> hd() |> Map.get("content")

  setup do
    RisiMe.RisiHelpers.fake_llm!(fn "translate", body ->
      cond do
        system(body) =~ "into English" -> %{"text" => @en}
        system(body) =~ "into Sinhala" -> %{"text" => @si}
        system(body) =~ "into Tamil" -> %{"text" => @ta}
      end
    end)

    :ok
  end

  defp requests do
    Process.get(:risi_llm_rec) |> Agent.get(& &1) |> Enum.reverse()
  end

  test "Tamil → Sinhala goes through English and gives the meaning" do
    assert {:ok, @si, "risi-l1"} = VoiceTranslate.translate(@ta, "ta", "si", "grp:x")
    [first, second] = requests()
    assert system(first) =~ "into English" and hd(tl(first["messages"]))["content"] =~ @ta
    assert system(second) =~ "into Sinhala" and hd(tl(second["messages"]))["content"] =~ @en
    assert system(second) =~ "never transliterate"
  end

  test "Sinhala → Tamil goes through English too" do
    assert {:ok, @ta, "risi-l1"} = VoiceTranslate.translate(@si, "si", "ta", "grp:x")
    assert length(requests()) == 2
  end

  test "Tamil → English is one request" do
    assert {:ok, @en, "risi-l1"} = VoiceTranslate.translate(@ta, "ta", "en", "grp:x")
    assert length(requests()) == 1
  end

  test "the same language is not translated" do
    assert {:ok, @si, "none"} = VoiceTranslate.translate(@si, "si", "si", "grp:x")
  end
end
