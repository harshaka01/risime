defmodule RisiMe.Agent.SpeechHttpTest do
  @moduledoc """
  Contract v1.39 §36.5 / decisions 071 and 080: the loopback client of the local speech service
  (`infra/whisper/server.py`) through a `Req.Test` stub: `model=auto` routing with a fallback
  for an older service, the compare endpoint (and its per-model fallback), the Bearer key, VAD
  silence, errors, and the loopback-only rule.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.Speech.Http

  setup do
    old = Application.get_env(:risime, :risi_speech_http)
    on_exit(fn -> Application.put_env(:risime, :risi_speech_http, old) end)
    :persistent_term.erase({Http, :no_auto})
    Http.reset_health()
    :ok
  end

  defp stub(fun) do
    {:ok, rec} = Agent.start_link(fn -> [] end)

    Req.Test.stub(Http, fn conn ->
      {:ok, body, conn} = Plug.Conn.read_body(conn, length: 1_000_000)

      Agent.update(
        rec,
        &[{conn.request_path, body, Plug.Conn.get_req_header(conn, "authorization")} | &1]
      )

      fun.(conn, body)
    end)

    rec
  end

  defp field(body, name) do
    case Regex.run(~r/name="#{name}"\r\n\r\n([^\r]*)\r\n/, body) do
      [_, v] -> v
      _ -> nil
    end
  end

  test "transcribe asks for model=auto (verbose_json) and reads the routed model" do
    rec =
      stub(fn conn, body ->
        assert field(body, "model") == "auto"
        assert field(body, "response_format") == "verbose_json"
        assert body =~ "si-audio-bytes"

        Req.Test.json(conn, %{
          "text" => " හෙට හමුවෙමු. ",
          "language" => "si",
          "model" => "whisper-small-sinhala",
          "processing_s" => 0.64,
          "duration_after_vad" => 4.2
        })
      end)

    assert {:ok, r} = Http.transcribe("si-audio-bytes")
    assert r.text == "හෙට හමුවෙමු."
    assert r.language == "si"
    assert r.model == "whisper-small-sinhala"
    assert r.elapsed_ms == 640
    assert r.speech

    assert [{"/v1/audio/transcriptions", _, ["Bearer test-speech-key"]}] = Agent.get(rec, & &1)
  end

  test "VAD silence is not speech; an unknown language is en" do
    stub(fn conn, _ ->
      Req.Test.json(conn, %{"text" => "", "language" => "fr", "duration_after_vad" => 0})
    end)

    assert {:ok, %{speech: false, language: "en"}} = Http.transcribe("x")
  end

  test "an older service without auto (404): asks for faster-whisper-large-v3" do
    rec =
      stub(fn conn, body ->
        case field(body, "model") do
          "auto" ->
            conn |> Plug.Conn.put_status(404) |> Req.Test.json(%{"error" => %{}})

          m ->
            Req.Test.json(conn, %{"text" => "hello", "language" => "en", "model" => m})
        end
      end)

    assert {:ok, %{model: "faster-whisper-large-v3", text: "hello"}} = Http.transcribe("x")
    # Remembered: the next chunk goes straight to the alias.
    assert {:ok, _} = Http.transcribe("y")
    assert length(Agent.get(rec, & &1)) == 3
  end

  test "errors: 503 → unavailable, 500 → failed" do
    stub(fn conn, _ -> conn |> Plug.Conn.put_status(503) |> Req.Test.json(%{}) end)
    assert {:error, :unavailable} = Http.transcribe("x")
    stub(fn conn, _ -> conn |> Plug.Conn.put_status(500) |> Req.Test.json(%{}) end)
    assert {:error, :failed} = Http.transcribe("x")
  end

  test "compare: per-model results from /v1/audio/compare" do
    stub(fn conn, _ ->
      assert conn.request_path == "/v1/audio/compare"

      Req.Test.json(conn, %{
        "detected" => %{"language" => "si", "probability" => 0.91},
        "routed_model" => "whisper-small-sinhala",
        "results" => [
          %{
            "model" => "whisper-small-sinhala",
            "text" => "අ",
            "language" => "si",
            "processing_s" => 0.64
          },
          %{
            "model" => "tamil-asr-qwen3",
            "error" => "not enough memory: x",
            "processing_s" => 30.0
          },
          %{
            "model" => "faster-whisper-large-v3",
            "text" => "ආ",
            "language" => "si",
            "processing_s" => 1.81
          }
        ]
      })
    end)

    assert {:ok, c} = Http.compare("x")
    assert c.language_detected == "si"
    assert c.chosen == "whisper-small-sinhala"

    assert [
             %{model: "whisper-small-sinhala", text: "අ", elapsed_ms: 640, error: nil},
             %{model: "tamil-asr-qwen3", text: nil, error: "failed"},
             %{model: "faster-whisper-large-v3", elapsed_ms: 1810}
           ] = c.results
  end

  test "compare without the endpoint (404): each model by itself" do
    stub(fn conn, body ->
      case conn.request_path do
        "/v1/audio/compare" ->
          conn |> Plug.Conn.put_status(404) |> Req.Test.json(%{})

        _ ->
          m = field(body, "model")
          Req.Test.json(conn, %{"text" => "t-" <> m, "language" => "ta", "model" => m})
      end
    end)

    assert {:ok, c} = Http.compare("x")
    assert c.language_detected == "ta"
    assert c.chosen == "tamil-asr-qwen3"

    assert Enum.map(c.results, & &1.text) |> Enum.sort() ==
             Enum.sort(Enum.map(RisiMe.Agent.Speech.models(), &("t-" <> &1)))
  end

  test "health, cached; a non-loopback URL is refused before any request" do
    stub(fn conn, _ -> Req.Test.json(conn, %{"status" => "ok"}) end)
    assert Http.healthy?()

    Application.put_env(:risime, :risi_speech_http, url: "http://10.0.0.5:8200", api_key: "k")
    Http.reset_health()
    refute Http.healthy?()
    assert {:error, :unavailable} = Http.transcribe("x")
  end
end
