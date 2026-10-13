defmodule RisiMe.Agent.SpeechRoutesTest do
  @moduledoc """
  Decision 089: the admin console's per-language speech model choice. **Parity:** with nothing
  set (Auto everywhere), `Speech.Http.transcribe/2` sends exactly today's single request
  (`model=auto`, no `language`, no detect call). A manual model wins for its language only.
  """
  use RisiMe.DataCase, async: false

  alias RisiMe.Admin.Settings
  alias RisiMe.Agent.Speech.Http
  alias RisiMe.Agent.SpeechRoutes

  setup do
    Settings.refresh()
    :persistent_term.erase({Http, :no_auto})
    old = Application.get_env(:risime, :admin_settings)

    on_exit(fn ->
      Application.put_env(:risime, :admin_settings, old)
      Settings.refresh()
    end)

    :ok
  end

  defp routes(map) do
    Application.put_env(
      :risime,
      :admin_settings,
      Map.new(map, fn {k, v} -> {"speech_route_" <> k, v} end)
    )
  end

  defp field(body, name) do
    case Regex.run(~r/name="#{name}"\r\n\r\n([^\r]*)\r\n/, body) do
      [_, v] -> v
      _ -> nil
    end
  end

  # Records {path, model, language, language_hint} of every request; `detect` answers detect.
  defp stub(detect, transcribe \\ nil) do
    {:ok, rec} = Agent.start_link(fn -> [] end)

    Req.Test.stub(Http, fn conn ->
      {:ok, body, conn} = Plug.Conn.read_body(conn, length: 1_000_000)

      Agent.update(
        rec,
        &(&1 ++
            [
              {conn.request_path, field(body, "model"), field(body, "language"),
               field(body, "language_hint")}
            ])
      )

      case conn.request_path do
        "/v1/audio/detect" ->
          Req.Test.json(conn, detect)

        "/v1/audio/transcriptions" ->
          if transcribe,
            do: transcribe.(conn, field(body, "model")),
            else:
              Req.Test.json(conn, %{
                "text" => "hello",
                "language" => "en",
                "model" => field(body, "model"),
                "processing_s" => 0.1
              })
      end
    end)

    rec
  end

  describe "parity: Auto everywhere (nothing set)" do
    test "one request, model=auto, no language field, no detect call" do
      rec = stub(%{"reason" => "detected", "language" => "si"})

      assert SpeechRoutes.choices() == %{
               "si" => "auto",
               "ta" => "auto",
               "en" => "auto",
               "mixed" => "auto"
             }

      refute SpeechRoutes.manual?()
      assert SpeechRoutes.pick("audio", []) == :auto
      assert {:ok, _} = Http.transcribe("audio", language_hint: ["si"])

      assert Agent.get(rec, & &1) == [{"/v1/audio/transcriptions", "auto", nil, "si"}]
    end

    test "explicit auto values are the same as nothing set" do
      routes(%{"si" => "auto", "ta" => "auto", "en" => "auto", "mixed" => "auto"})
      rec = stub(%{"reason" => "detected", "language" => "si"})
      assert {:ok, _} = Http.transcribe("audio")
      assert Agent.get(rec, & &1) == [{"/v1/audio/transcriptions", "auto", nil, nil}]
    end
  end

  describe "manual" do
    test "the chunk's language has a manual model: detect, then that model with the language" do
      routes(%{"si" => "qwen3-asr-0.6b-sinhala"})
      rec = stub(%{"reason" => "hint_prior", "language" => "si", "detected" => "en"})

      assert {:ok, r} = Http.transcribe("audio", language_hint: ["si"])
      assert r.model == "qwen3-asr-0.6b-sinhala"

      assert Agent.get(rec, & &1) == [
               {"/v1/audio/detect", nil, nil, "si"},
               {"/v1/audio/transcriptions", "qwen3-asr-0.6b-sinhala", "si", "si"}
             ]
    end

    test "another language stays on Auto (today's request after the detect)" do
      routes(%{"si" => "qwen3-asr-0.6b-sinhala"})
      rec = stub(%{"reason" => "detected", "language" => "en"})
      assert {:ok, _} = Http.transcribe("audio")

      assert Agent.get(rec, & &1) == [
               {"/v1/audio/detect", nil, nil, nil},
               {"/v1/audio/transcriptions", "auto", nil, nil}
             ]
    end

    test "mixed = the detector's low confidence: that model, language not forced" do
      routes(%{"mixed" => "omnilingual-ctc-1b"})
      rec = stub(%{"reason" => "low_confidence", "language" => nil})
      assert {:ok, _} = Http.transcribe("audio")

      assert Agent.get(rec, & &1) == [
               {"/v1/audio/detect", nil, nil, nil},
               {"/v1/audio/transcriptions", "omnilingual-ctc-1b", nil, nil}
             ]
    end

    test "no speech, or a failed detect: Auto" do
      routes(%{"en" => "faster-whisper-large-v3-turbo"})
      stub(%{"reason" => "no_speech", "language" => nil})
      assert SpeechRoutes.pick("audio", []) == :auto

      Req.Test.stub(Http, fn conn -> Plug.Conn.send_resp(conn, 503, "loading") end)
      assert SpeechRoutes.pick("audio", []) == :auto
    end

    test "a manual model the service no longer serves (404) falls back to Auto" do
      routes(%{"ta" => "gone-model"})

      rec =
        stub(%{"reason" => "detected", "language" => "ta"}, fn conn, model ->
          if model == "gone-model",
            do: Plug.Conn.send_resp(conn, 404, "{}"),
            else: Req.Test.json(conn, %{"text" => "x", "language" => "ta", "model" => "m"})
        end)

      assert {:ok, %{model: "m"}} = Http.transcribe("audio")

      assert Enum.map(Agent.get(rec, & &1), &elem(&1, 1)) == [nil, "gone-model", "auto"]
    end
  end

  describe "put/4 and installed/0" do
    test "only installed models; auto clears" do
      entry = RisiMe.Fixtures.allowlist_entry()

      {:ok, u} =
        RisiMe.Accounts.map_identity(%{sub: Ecto.UUID.generate(), email: entry.email})

      assert SpeechRoutes.put("si", "x", u.id, ["y"]) == {:error, :not_installed}
      assert SpeechRoutes.put("xx", "y", u.id, ["y"]) == {:error, :invalid}
      assert SpeechRoutes.put("si", "y", u.id, ["y"]) == :ok
      assert SpeechRoutes.choice("si") == "y"
      assert SpeechRoutes.put("si", "auto", u.id) == :ok
      assert SpeechRoutes.choice("si") == "auto"
    end

    test "installed lists the served models and today's Auto routes" do
      Req.Test.stub(Http, fn conn ->
        assert conn.request_path == "/v1/models"

        Req.Test.json(conn, %{
          "data" => [
            %{"id" => "faster-whisper-large-v3", "role" => "default", "language" => nil},
            %{"id" => "whisper-small-sinhala", "role" => "route", "language" => "si"},
            %{"id" => "tamil-asr-qwen3", "role" => "route", "language" => "ta"},
            %{
              "id" => "auto",
              "role" => "router",
              "routes" => %{"si" => "whisper-small-sinhala", "ta" => "tamil-asr-qwen3"}
            }
          ]
        })
      end)

      assert {:ok, %{models: models, routes: routes}} = SpeechRoutes.installed()
      assert length(models) == 3

      assert routes == %{
               "si" => "whisper-small-sinhala",
               "ta" => "tamil-asr-qwen3",
               "en" => "faster-whisper-large-v3",
               "mixed" => "faster-whisper-large-v3"
             }

      assert Enum.map(SpeechRoutes.candidates(models, "si"), & &1.id) ==
               ["faster-whisper-large-v3", "whisper-small-sinhala"]

      assert length(SpeechRoutes.candidates(models, "mixed")) == 3
    end
  end
end
