defmodule RisiMe.Agent.TTSLabTest do
  @moduledoc """
  TTS candidates (2026-10-14): the risime-tts-lab engines (Chatterbox, IndicF5, Indic Parler,
  Orpheus) through `RisiMe.Agent.TTS.Lab`, the "downloading tonight" / review statuses, live
  calls first, the licence catalogue, the console ratings feeding the Voice agent's scoreboard,
  and parity: Call Risi keeps Kokoro whatever is chosen on the Voices page.
  """
  use RisiMe.DataCase, async: false

  import Ecto.Query

  alias RisiMe.Admin.{AuditEntry, Settings}
  alias RisiMe.Agent.{CallRisi, TTS, VoiceAuto}
  alias RisiMe.Agent.TTS.{Chatterbox, IndicF5, IndicParler, Lab, Orpheus, Ratings}
  alias RisiMeWeb.Admin.VoicesLive

  @engines %{
    "engines" => [
      %{
        "id" => "chatterbox",
        "status" => "downloading",
        "voices" => [%{"id" => "chatterbox:default", "name" => "Default", "languages" => ["en"]}]
      },
      %{"id" => "indicf5", "status" => "review_pending", "voices" => []},
      %{
        "id" => "indic-parler",
        "status" => "ready",
        "voices" => [
          %{"id" => "indic-parler:ta:jaya", "name" => "Jaya (ta)", "languages" => ["ta"]},
          %{"id" => "indic-parler:en:mary", "name" => "Mary (en)", "languages" => ["en"]}
        ]
      },
      %{"id" => "orpheus", "status" => "broken", "voices" => []}
    ]
  }

  setup do
    Settings.refresh()
    old = Application.get_env(:risime, :risi_tts_engines)
    Application.put_env(:risime, :risi_tts_live_calls, fn -> 0 end)

    on_exit(fn ->
      Application.put_env(:risime, :risi_tts_engines, old)
      Application.delete_env(:risime, :risi_tts_live_calls)
      Settings.refresh()
    end)

    :ok
  end

  defp stub_engines do
    Req.Test.stub(Lab, fn conn ->
      case conn.request_path do
        "/v1/engines" -> Req.Test.json(conn, @engines)
        _ -> Plug.Conn.send_resp(conn, 404, "")
      end
    end)
  end

  defp admin do
    entry = RisiMe.Fixtures.allowlist_entry()
    {:ok, u} = RisiMe.Accounts.map_identity(%{sub: Ecto.UUID.generate(), email: entry.email})
    u
  end

  test "engine statuses: ready → voices; downloading / review pending / broken are not installed" do
    stub_engines()
    assert Chatterbox.voices() == {:error, :downloading}
    assert IndicF5.voices() == {:error, :review_pending}
    assert Orpheus.voices() == {:error, :unavailable}
    assert {:ok, [%{id: "indic-parler:ta:jaya", languages: ["ta"]}, _]} = IndicParler.voices()

    Application.put_env(:risime, :risi_tts_engines, [Chatterbox, IndicF5, IndicParler, Orpheus])
    installed = TTS.installed()

    assert Enum.map(installed, &{&1.id, &1.status}) == [
             {"chatterbox", :downloading},
             {"indicf5", :review_pending},
             {"indic-parler", :ok},
             {"orpheus", :unavailable}
           ]

    assert VoicesLive.engine_status(installed, TTS.info("chatterbox")) == "downloading tonight"
    assert VoicesLive.engine_status(installed, TTS.info("indic-parler")) == "installed: 2 voices"
    assert VoicesLive.engine_status(installed, TTS.info("kokoro")) == "not installed"
    assert VoicesLive.engine_status(installed, TTS.info("mms")) == "not available (licence)"
    assert VoicesLive.engine_status(installed, TTS.info("phone")) =~ "phone only"

    assert Enum.map(TTS.candidates(TTS.voices(installed), "ta"), & &1.id) == [
             "indic-parler:ta:jaya"
           ]
  end

  test "the lab service down → every lab engine is unavailable, no crash" do
    Req.Test.stub(Lab, fn conn -> Req.Test.transport_error(conn, :econnrefused) end)
    assert Orpheus.voices() == {:error, :unavailable}

    assert IndicParler.synthesize("hello", "indic-parler:en:mary", []) ==
             {:error, :unavailable}
  end

  test "synthesize: one WAV with load, first audio and audio length from the service" do
    Req.Test.stub(Lab, fn conn ->
      {:ok, body, conn} = Plug.Conn.read_body(conn)
      assert %{"text" => "vanakkam", "voice" => "indic-parler:ta:jaya"} = Jason.decode!(body)

      conn
      |> Plug.Conn.put_resp_header("x-first-audio-ms", "640")
      |> Plug.Conn.put_resp_header("x-load-ms", "9100")
      |> Plug.Conn.put_resp_header("x-audio-ms", "1500")
      |> Plug.Conn.put_resp_content_type("audio/wav")
      |> Plug.Conn.send_resp(200, "RIFFdata")
    end)

    assert {:ok, a} = IndicParler.synthesize("vanakkam", "indic-parler:ta:jaya", [])
    assert a.audio == "RIFFdata" and a.content_type == "audio/wav"
    assert a.first_audio_ms == 640 and a.load_ms == 9100 and a.audio_ms == 1500
  end

  test "refusals: memory floor, paused, another engine's voice, live calls first" do
    Req.Test.stub(Lab, fn conn ->
      conn
      |> Plug.Conn.put_resp_content_type("application/json")
      |> Plug.Conn.send_resp(503, ~s({"error":"no_memory"}))
    end)

    assert Orpheus.synthesize("hi", "orpheus:tara", []) == {:error, :no_memory}
    assert Orpheus.synthesize("hi", "kokoro:af_heart", []) == {:error, :failed}

    Req.Test.stub(Lab, fn conn -> Plug.Conn.send_resp(conn, 503, ~s({"error":"paused"})) end)
    assert Orpheus.synthesize("hi", "orpheus:tara", []) == {:error, :paused}

    Application.put_env(:risime, :risi_tts_live_calls, fn -> 1 end)
    Req.Test.stub(Lab, fn _ -> flunk("no request during a live call") end)
    assert Orpheus.synthesize("hi", "orpheus:tara", []) == {:error, :live_call}
    assert VoicesLive.error_text(:live_call) =~ "live calls first"
  end

  test "catalogue: every candidate has a licence and a production flag; MMS and Llama flagged" do
    ids = Enum.map(TTS.catalogue_info(), & &1.id)

    for id <- ~w(kokoro orpheus chatterbox indicf5 indic-parler mms phone),
        do: assert(id in ids)

    for e <- TTS.catalogue_info() do
      assert is_binary(e.licence) and e.production in [:yes, :conditional, :no]
    end

    assert TTS.info("mms").production == :no and TTS.info("mms").licence =~ "NC"

    assert TTS.info("orpheus").production == :conditional and
             TTS.info("orpheus").licence =~ "Llama"

    assert TTS.info("chatterbox").note =~ "watermark"
    assert TTS.info("indicf5").production == :no
    assert {"phone", _} = List.keyfind(TTS.catalogue(), "phone", 0)
  end

  test "test voices: one per engine or all, by language" do
    vs = [
      %{id: "kokoro:a", engine: "kokoro", languages: ["en"]},
      %{id: "kokoro:b", engine: "kokoro", languages: ["en"]},
      %{id: "indic-parler:ta:jaya", engine: "indic-parler", languages: ["ta"]}
    ]

    assert Enum.map(VoicesLive.pick(vs, "each_engine", "any"), & &1.id) ==
             ["kokoro:a", "indic-parler:ta:jaya"]

    assert length(VoicesLive.pick(vs, "all", "any")) == 3
    assert Enum.map(VoicesLive.pick(vs, "all", "ta"), & &1.id) == ["indic-parler:ta:jaya"]
  end

  test "ratings: audit-logged with latency, summed into the Voice agent's live_tts row" do
    u = admin()

    assert Ratings.rate(u.id, "kokoro:af_heart", "up", %{first_audio_ms: 300, total_ms: 900}) !=
             nil

    Ratings.rate(u.id, "kokoro:af_heart", "down", %{first_audio_ms: 500, total_ms: 1000})
    Ratings.rate(u.id, "indic-parler:ta:jaya", "up", %{first_audio_ms: 700, load_ms: 9000})
    assert Ratings.rate(u.id, "kokoro:af_heart", "meh", %{}) == {:error, :invalid}

    entries =
      Repo.all(
        from e in AuditEntry,
          where: e.action == "tts_rating",
          order_by: e.id,
          select: {e.target, e.after}
      )

    assert [{"kokoro:af_heart", "up first_ms=300 total_ms=900 load_ms=0"}, _, _] = entries

    [row] =
      Repo.all(
        from s in "risi_agent_scores",
          where: s.agent == "voice" and s.source == "live_tts",
          select: %{n: s.n, acceptance: s.acceptance, p50_ms: s.p50_ms, detail: s.detail}
      )

    assert row.n == 3
    assert close?(row.acceptance, 2 / 3)
    assert row.p50_ms == 500
    assert row.detail["voices"]["kokoro:af_heart"] == %{"up" => 1, "down" => 1, "p50_ms" => 500}

    # A live rating is not the Index: Auto still keeps today's route.
    assert %{index?: false, basis: :todays_route} = VoiceAuto.status(:tts)
  end

  defp close?(a, b), do: abs(a - b) < 1.0e-9

  test "the rate event records once per reply" do
    u = admin()

    results = [
      %{
        voice: %{id: "kokoro:af_heart"},
        result: {:ok, %{first_audio_ms: 200, total_ms: 600, load_ms: 0}},
        rated: nil
      }
    ]

    socket = %Phoenix.LiveView.Socket{
      assigns: %{__changed__: %{}, admin_id: u.id, results: results, flash: %{}}
    }

    {:noreply, s} = VoicesLive.handle_event("rate", %{"i" => "0", "score" => "up"}, socket)
    assert [%{rated: "up"}] = s.assigns.results
    {:noreply, _} = VoicesLive.handle_event("rate", %{"i" => "0", "score" => "down"}, s)

    assert Repo.aggregate(from(e in AuditEntry, where: e.action == "tts_rating"), :count) == 1
  end

  test "parity: Call Risi speaks Kokoro whatever the Voices page chose for English" do
    u = admin()
    assert CallRisi.voice("en") == "kokoro:af_heart"

    assert TTS.put("en", "indic-parler:en:mary", u.id, ["indic-parler:en:mary"]) == :ok
    assert TTS.voice_for("en") == {:manual, "indic-parler:en:mary"}
    assert CallRisi.voice("en") == "kokoro:af_heart"

    assert TTS.put("en", "kokoro:bf_emma", u.id, ["kokoro:bf_emma"]) == :ok
    assert CallRisi.voice("en") == "kokoro:bf_emma"
  end
end
