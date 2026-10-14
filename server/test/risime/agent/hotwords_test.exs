defmodule RisiMe.Agent.HotwordsTest do
  @moduledoc """
  Proposal 2026-10-14-send-for-me §42.6: speech hints. "Risi" (and රිසි, ரிசி) always; the
  sender's own contacts only in the sender's own Risi chat; the `hotwords` field sent per request
  (an old service ignores it); never in the logs; "Rishi, …" read as "Risi, …" at the start of a
  Risi-chat request only.
  """
  use RisiMe.DataCase, async: false

  import ExUnit.CaptureLog

  alias RisiMe.Agent.{Hotwords, Speech}
  alias RisiMe.Agent.Speech.Http

  setup do
    RisiMe.Admin.Settings.refresh()
    :persistent_term.erase({Http, :no_auto})
    :ok
  end

  defp field(body, name) do
    case Regex.run(~r/name="#{name}"\r\n\r\n([^\r]*)\r\n/u, body) do
      [_, v] -> v
      _ -> nil
    end
  end

  test "the field: joined, de-duplicated, commas removed, at most 60 entries / 1000 chars" do
    assert Speech.hotwords_field([]) == nil
    assert Speech.hotwords_field(hotwords: []) == nil

    assert Speech.hotwords_field(hotwords: ["Risi", "Risi", " Upali, Perera ", 3]) ==
             "Risi,Upali  Perera"

    many = for i <- 1..100, do: "Name#{i}"
    assert Speech.hotwords_field(hotwords: many) |> String.split(",") |> length() == 60

    long = for i <- 1..60, do: String.duplicate("x", 30) <> "#{i}"
    assert String.length(Speech.hotwords_field(hotwords: long)) <= 1000
  end

  test "Http sends `hotwords` with the transcription, and logs never hold the names" do
    {:ok, rec} = Agent.start_link(fn -> nil end)

    Req.Test.stub(Http, fn conn ->
      {:ok, body, conn} = Plug.Conn.read_body(conn, length: 1_000_000)
      Agent.update(rec, fn _ -> field(body, "hotwords") end)

      Req.Test.json(conn, %{
        "text" => "Risi, send Upali a message",
        "language" => "en",
        "model" => "auto",
        "processing_s" => 0.1
      })
    end)

    log =
      capture_log(fn ->
        assert {:ok, _} = Http.transcribe("audio", hotwords: ["Risi", "රිසි", "Zxqwv Upali"])
      end)

    assert Agent.get(rec, & &1) == "Risi,රිසි,Zxqwv Upali"
    refute log =~ "Zxqwv"

    # Without hotwords: no field at all (parity with today's request).
    {:ok, _} = Http.transcribe("audio", [])
    assert Agent.get(rec, & &1) == nil
  end

  describe "for_note/2" do
    setup do
      a = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
      b = RisiMe.Fixtures.logged_in_user(display_name: "Upali Perera")
      RisiMe.Fixtures.befriend!(a, b)
      og = RisiMe.RisiHelpers.risi_chat!([a.user, b.user])
      rc = RisiMe.RisiHelpers.own_risi_chat!(a.user)
      %{a: a.user.id, b: b.user.id, og: og, rc: rc}
    end

    test "the sender's own Risi chat: Risi plus their contacts; elsewhere only Risi", w do
      assert Hotwords.for_note(w.rc, w.a) == ["Risi", "රිසි", "ரிசி", "Upali Perera", "Upali"]
      # Official (others hear the transcript): only the name.
      assert Hotwords.for_note(w.og, w.a) == Hotwords.always()
      # Never another user's contacts, even for the same Risi chat id.
      assert Hotwords.for_note(w.rc, w.b) == Hotwords.always()
    end
  end

  test "fix_risi/1: only an opening mis-hearing, in its own script" do
    assert Hotwords.fix_risi("Rishi, can you send a message to Upali") ==
             "Risi, can you send a message to Upali"

    assert Hotwords.fix_risi("risky send Upali a message") == "Risi send Upali a message"
    assert Hotwords.fix_risi("රිෂි, උපාලිට message එකක් යවන්න") == "රිසි, උපාලිට message එකක් යවන්න"
    # Not at the start, or a longer word: unchanged.
    assert Hotwords.fix_risi("Tell Rishi to call me") == "Tell Rishi to call me"
    assert Hotwords.fix_risi("Rishikesh is far") == "Rishikesh is far"
    assert Hotwords.fix_risi(nil) == nil
  end
end
