defmodule RisiMe.Agent.CallRisi.LLMStream do
  @moduledoc """
  A **streamed** chat completion on risi-l1 for Call Risi (decision 090): vLLM's OpenAI-compatible
  `stream: true` (server-sent events) on loopback only (`RisiMe.Agent.LLM.Local.check_url/1`), thinking
  off. `on_delta` is called with each piece of text as it arrives, in the caller's process.

  Takes a slot of the global Risi queue (`RisiMe.Agent.LLM`'s 16 in flight) like every model call and
  writes one learning-log row (task `voice_call_answer`): model, latency, time to the first token,
  tokens, the SHA-256 of the messages; **never** the prompt or the answer text.

  Tests replace it with `config :risime, :risi_call_llm, Module` (same `stream/3`).
  """

  alias RisiMe.Agent.{LearningLog, LLM}

  @callback stream(messages :: [map], opts :: keyword, on_delta :: (String.t() -> any)) ::
              {:ok, %{model: String.t(), first_token_ms: integer | nil, tokens: integer}}
              | {:error, atom}

  def impl, do: Application.get_env(:risime, :risi_call_llm, __MODULE__)

  @doc "Streams through the configured implementation (and logs the call)."
  def run(messages, opts, on_delta) do
    t0 = System.monotonic_time(:millisecond)
    result = impl().stream(messages, opts, on_delta)
    log(messages, opts, result, System.monotonic_time(:millisecond) - t0)
    result
  end

  @doc false
  def stream(messages, opts, on_delta) do
    with {:ok, base} <- base_url() do
      body = %{
        "model" => Keyword.get(opts, :model, "risi-l1"),
        "messages" => messages,
        "stream" => true,
        "temperature" => 0.4,
        "top_p" => 0.9,
        "max_tokens" => Keyword.get(opts, :max_tokens, 180),
        "chat_template_kwargs" => %{"enable_thinking" => false}
      }

      t0 = System.monotonic_time(:millisecond)
      key = {__MODULE__, make_ref()}
      Process.put(key, %{buf: "", first: nil, tokens: 0, model: nil})

      into = fn {:data, data}, acc ->
        st = Process.get(key)
        {events, buf} = split_events(st.buf <> data)

        st =
          Enum.reduce(events, %{st | buf: buf}, fn ev, st ->
            case decode(ev) do
              {:delta, text, model} when text != "" ->
                on_delta.(text)

                %{
                  st
                  | first: st.first || System.monotonic_time(:millisecond) - t0,
                    tokens: st.tokens + 1,
                    model: st.model || model
                }

              _ ->
                st
            end
          end)

        Process.put(key, st)
        {:cont, acc}
      end

      req =
        Req.new(
          [
            url: base <> "/chat/completions",
            json: body,
            receive_timeout: Keyword.get(opts, :timeout, 20_000),
            connect_options: [timeout: 3_000],
            retry: false,
            redirect: false,
            headers: auth_headers(),
            into: into
          ] ++ Keyword.get(llm_config(), :req_options, [])
        )

      result =
        case Req.post(req) do
          {:ok, %{status: 200}} ->
            st = Process.get(key)
            {:ok, %{model: st.model || "risi-l1", first_token_ms: st.first, tokens: st.tokens}}

          {:ok, %{status: 429}} ->
            {:error, :busy}

          {:ok, %{status: _}} ->
            {:error, :failed}

          {:error, %{reason: :timeout}} ->
            {:error, :timeout}

          {:error, _} ->
            {:error, :transport}
        end

      Process.delete(key)
      result
    end
  end

  @doc false
  def split_events(buf) do
    parts = String.split(buf, ~r/\r?\n\r?\n/)
    {Enum.drop(parts, -1), List.last(parts)}
  end

  @doc false
  def decode(event) do
    event
    |> String.split("\n")
    |> Enum.find_value(:skip, fn
      "data: [DONE]" ->
        :done

      "data: " <> json ->
        case Jason.decode(json) do
          {:ok, %{"choices" => [%{"delta" => d} | _]} = m} ->
            {:delta, d["content"] || "", m["model"]}

          _ ->
            nil
        end

      _ ->
        nil
    end)
  end

  defp base_url do
    LLM.Local.check_url(Keyword.get(llm_config(), :url) || "http://127.0.0.1:8100/v1")
  end

  defp llm_config, do: Application.get_env(:risime, :risi_llm, [])

  defp auth_headers do
    case llm_config()[:api_key] do
      k when is_binary(k) and k != "" -> [{"authorization", "Bearer " <> k}]
      _ -> []
    end
  end

  defp log(messages, opts, result, latency) do
    {status, model, meta} =
      case result do
        {:ok, r} -> {"ok", r.model, %{"first_token_ms" => r.first_token_ms, "chunks" => r.tokens}}
        {:error, e} -> {Atom.to_string(e), nil, %{}}
      end

    LearningLog.record(%{
      call_id: RisiMe.TimeUUID.generate(),
      conversation_id: opts[:conversation_id],
      chat_id: opts[:conversation_id],
      task: "voice_call_answer",
      model_alias: Keyword.get(opts, :model, "risi-l1"),
      model: model,
      provider: "risi_l1",
      fallback: "off",
      latency_ms: latency,
      cost: 0.0,
      source_message_ids: [],
      prompt_sha256: LLM.prompt_hash(messages),
      status: status,
      output: nil,
      # `log_meta`: counts only (e.g. PhoneCalendar.log_fields/1, §41.8: never a title).
      meta:
        meta
        |> Map.merge(Map.take(opts[:log_meta] || %{}, ~w(cal_ok cal_reason events_n titles_n)))
        |> Map.put("call_id", opts[:call_id])
    })
  rescue
    e ->
      require Logger
      Logger.warning("call risi: learning log write failed (#{inspect(e.__struct__)})")
  end
end
