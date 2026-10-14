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

  test "v1.40 §36.11: the Sinhala script check passes through (transcribe and compare)" do
    stub(fn conn, _ ->
      case conn.request_path do
        "/v1/audio/transcriptions" ->
          Req.Test.json(conn, %{
            "text" => "heta hamuwemu",
            "language" => "si",
            "model" => "whisper-large-v3-sinhala",
            "script" => %{"expected" => "sinh", "sinhala_chars" => 0, "latin_chars" => 12},
            "script_ok" => false,
            "romanised" => true,
            "retried_with" => "whisper-large-v3-sinhala"
          })

        "/v1/audio/compare" ->
          Req.Test.json(conn, %{
            "detected" => %{"language" => "si", "probability" => 0.9},
            "routed_model" => "whisper-small-sinhala",
            "results" => [
              %{
                "model" => "whisper-small-sinhala",
                "text" => "අ",
                "language" => "si",
                "processing_s" => 0.5,
                "script" => %{
                  "expected" => "sinh",
                  "sinhala_chars" => 1,
                  "latin_chars" => 0,
                  "ok" => true
                },
                "romanised" => false
              },
              %{
                "model" => "faster-whisper-large-v3",
                "text" => "a",
                "language" => "si",
                "processing_s" => 0.5,
                "script" => %{
                  "expected" => "sinh",
                  "sinhala_chars" => 0,
                  "latin_chars" => 1,
                  "ok" => false
                },
                "romanised" => true
              },
              %{"model" => "tamil-asr-qwen3", "error" => "x", "romanised" => true}
            ]
          })
      end
    end)

    assert {:ok, %{romanised: true, model: "whisper-large-v3-sinhala"}} = Http.transcribe("x")
    assert {:ok, c} = Http.compare("x")

    assert [
             %{
               script: %{expected: "sinh", sinhala_chars: 1, latin_chars: 0, ok: true},
               romanised: false
             },
             %{script: %{ok: false, latin_chars: 1}, romanised: true},
             %{error: "failed", script: nil, romanised: nil}
           ] = c.results

    # An older service (no fields): not romanised, no script.
    stub(fn conn, _ -> Req.Test.json(conn, %{"text" => "අ", "language" => "si"}) end)
    assert {:ok, %{romanised: false}} = Http.transcribe("x")
  end

  test "v1.41 §37.6: the detection probability and the transcribe script check" do
    stub(fn conn, _ ->
      Req.Test.json(conn, %{
        "text" => "හෙට",
        "language" => "si",
        "language_probability" => 0.91,
        "routing" => %{"reason" => "detected", "language" => "si", "probability" => 0.55},
        "script" => %{
          "expected" => "sinh",
          "sinhala_chars" => 3,
          "latin_chars" => 0,
          "ok" => true
        },
        "romanised" => false
      })
    end)

    # The routing's detection wins over the model's own probability.
    assert {:ok, %{language_probability: 0.55, script: %{ok: true, sinhala_chars: 3}}} =
             Http.transcribe("x")

    stub(fn conn, _ ->
      Req.Test.json(conn, %{"text" => "hi", "language" => "en", "language_probability" => 0.8})
    end)

    assert {:ok, %{language_probability: 0.8, script: nil}} = Http.transcribe("x")
    stub(fn conn, _ -> Req.Test.json(conn, %{"text" => "hi", "language" => "en"}) end)
    assert {:ok, %{language_probability: nil}} = Http.transcribe("x")
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

  test "decision 071 d: language_hint is sent on transcribe and compare, omitted without one" do
    rec =
      stub(fn conn, body ->
        case conn.request_path do
          "/v1/audio/compare" ->
            conn |> Plug.Conn.put_status(404) |> Req.Test.json(%{})

          _ ->
            m = field(body, "model")
            Req.Test.json(conn, %{"text" => "t", "language" => "si", "model" => m})
        end
      end)

    hints = fn ->
      rec
      |> Agent.get(& &1)
      |> Enum.map(fn {path, body, _} -> {path, field(body, "language_hint")} end)
    end

    assert {:ok, _} = Http.transcribe("x", language_hint: ["si", "en", "fr", "si"])
    assert [{"/v1/audio/transcriptions", "si,en"}] = hints.()

    Agent.update(rec, fn _ -> [] end)
    assert {:ok, _} = Http.transcribe("x")
    assert {:ok, _} = Http.transcribe("x", language_hint: [])
    assert {:ok, _} = Http.transcribe("x", language_hint: ["fr"])
    assert [{_, nil}, {_, nil}, {_, nil}] = hints.()

    # Compare (and its per-model fallback) carries it too.
    Agent.update(rec, fn _ -> [] end)
    assert {:ok, _} = Http.compare("x", language_hint: ["ta"])
    assert [{"/v1/audio/compare", "ta"} | each] = Enum.reverse(hints.())
    assert length(each) == 3
    assert Enum.all?(each, &(&1 == {"/v1/audio/transcriptions", "ta"}))

    Agent.update(rec, fn _ -> [] end)
    assert {:ok, _} = Http.compare("x")
    assert Enum.all?(hints.(), &(elem(&1, 1) == nil))
  end

  describe "extra compare candidates (omniASR eval service, 2026-10-14)" do
    defp main_compare(conn) do
      Req.Test.json(conn, %{
        "detected" => %{"language" => "si", "probability" => 0.9},
        "routed_model" => "qwen3-asr-0.6b-sinhala",
        "results" => [
          %{"model" => "qwen3-asr-0.6b-sinhala", "text" => "අ", "processing_s" => 0.5}
        ]
      })
    end

    defp with_extra(kv) do
      cfg = Application.get_env(:risime, :risi_speech_http)
      Application.put_env(:risime, :risi_speech_http, Keyword.merge(cfg, kv))
    end

    test "off by default: only the speech service is asked" do
      rec = stub(fn conn, _ -> main_compare(conn) end)
      with_extra(compare_extra_url: nil)
      assert {:ok, c} = Http.compare("x")
      assert [%{model: "qwen3-asr-0.6b-sinhala"}] = c.results
      assert [{"/v1/audio/compare", _, _}] = Agent.get(rec, & &1)
    end

    test "appended after the speech service's results, with the detected language" do
      rec =
        stub(fn conn, body ->
          case conn.port do
            8200 ->
              main_compare(conn)

            8210 ->
              assert field(body, "language") == "si"
              assert field(body, "models") == "omniasr-llm-1b,omniasr-llm-7b"

              Req.Test.json(conn, %{
                "language" => "si",
                "results" => [
                  %{
                    "model" => "omniasr-llm-1b",
                    "text" => " ආ ",
                    "language" => "si",
                    "processing_s" => 1.2,
                    "script" => %{
                      "expected" => "sinh",
                      "sinhala_chars" => 1,
                      "latin_chars" => 0,
                      "ok" => true
                    },
                    "romanised" => false
                  },
                  %{"model" => "omniasr-llm-7b", "error" => "not enough memory: x"}
                ]
              })
          end
        end)

      with_extra(
        compare_extra_url: "http://127.0.0.1:8210",
        compare_extra_models: "omniasr-llm-1b,omniasr-llm-7b"
      )

      assert {:ok, c} = Http.compare("x", language_hint: ["si"])
      assert c.chosen == "qwen3-asr-0.6b-sinhala"
      assert c.language_detected == "si"

      assert [
               %{model: "qwen3-asr-0.6b-sinhala", text: "අ"},
               %{
                 model: "omniasr-llm-1b",
                 text: "ආ",
                 elapsed_ms: 1200,
                 error: nil,
                 romanised: false,
                 script: %{ok: true, sinhala_chars: 1}
               },
               %{model: "omniasr-llm-7b", text: nil, error: "failed"}
             ] = c.results

      assert [_main, _extra] = Agent.get(rec, & &1)
      assert Enum.all?(Agent.get(rec, & &1), &(elem(&1, 2) == ["Bearer test-speech-key"]))
    end

    test "a failing extra service only marks its models failed" do
      stub(fn conn, _ ->
        case conn.port do
          8200 -> main_compare(conn)
          8210 -> conn |> Plug.Conn.put_status(503) |> Req.Test.json(%{})
        end
      end)

      with_extra(compare_extra_url: "http://127.0.0.1:8210")
      assert {:ok, c} = Http.compare("x")

      # Default list: the 1B only (the streamed 7B is a console tick).
      assert [
               %{model: "qwen3-asr-0.6b-sinhala", error: nil},
               %{model: "omniasr-llm-1b", text: nil, error: "failed"}
             ] = c.results
    end

    test "extra_models per call overrides the list ([] = none); extra_models/0 lists the service" do
      rec =
        stub(fn conn, body ->
          case {conn.port, conn.request_path} do
            {8200, _} ->
              main_compare(conn)

            {8210, "/v1/models"} ->
              Req.Test.json(conn, %{
                "data" => [
                  %{"id" => "omniasr-llm-1b", "streamed" => false},
                  %{"id" => "omniasr-llm-7b", "streamed" => true}
                ]
              })

            {8210, "/v1/audio/compare"} ->
              assert field(body, "models") == "omniasr-llm-7b"

              Req.Test.json(conn, %{
                "results" => [
                  %{"model" => "omniasr-llm-7b", "text" => "ඉ", "processing_s" => 9.5}
                ]
              })
          end
        end)

      with_extra(compare_extra_url: "http://127.0.0.1:8210")

      assert {:ok,
              [%{id: "omniasr-llm-1b", streamed: false}, %{id: "omniasr-llm-7b", streamed: true}]} =
               Http.extra_models()

      assert Http.extra_default_models() == ["omniasr-llm-1b"]

      assert {:ok, c} = Http.compare("x", extra_models: ["omniasr-llm-7b"])
      assert [_, %{model: "omniasr-llm-7b", text: "ඉ", elapsed_ms: 9500}] = c.results

      Agent.update(rec, fn _ -> [] end)
      assert {:ok, %{results: [_]}} = Http.compare("x", extra_models: [])
      assert [{"/v1/audio/compare", _, _}] = Agent.get(rec, & &1)
    end

    test "extra_models/0 is off without the extra URL" do
      with_extra(compare_extra_url: nil)
      assert {:error, :off} = Http.extra_models()
      assert Http.extra_default_models() == []
    end

    test "a non-loopback extra URL is never asked; a failed main compare skips it" do
      rec = stub(fn conn, _ -> main_compare(conn) end)
      with_extra(compare_extra_url: "http://10.0.0.5:8210")
      assert {:ok, %{results: [_]}} = Http.compare("x")
      assert [_] = Agent.get(rec, & &1)

      rec = stub(fn conn, _ -> conn |> Plug.Conn.put_status(503) |> Req.Test.json(%{}) end)
      with_extra(compare_extra_url: "http://127.0.0.1:8210")
      assert {:error, :unavailable} = Http.compare("x")
      assert [_] = Agent.get(rec, & &1)
    end
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
