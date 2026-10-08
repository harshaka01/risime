defmodule RisiMe.Agent.LLM do
  @moduledoc """
  Risi's model router (§24.12 cascade, decisions 061 and 066).

  `complete/1` takes one task (system prompt, user prompt, a JSON schema, the source message
  ids) and:

    1. takes a slot of the **global Risi queue** (§24.13: 16 in flight; the 17th gets
       `{:error, :rate_limited}` at once);
    2. calls the **local provider** `risi_l1` (`RisiMe.Agent.LLM.Local`: vLLM on loopback, model
       alias `risi-l1`) with JSON-schema guided decoding (`response_format: json_schema`),
       thinking off (`chat_template_kwargs.enable_thinking: false`, as decision 061 serves it),
       temperature 0.2, top_p 0.8; one retry on a timeout, a transport error, a 5xx or an output
       that fails the schema;
    3. only when `RISI_FALLBACK=on` (default **off**) and the answer's confidence is below
       `RISI_FALLBACK_THRESHOLD` (default 0.5), consults the fallback provider
       (`RisiMe.Agent.LLM.Fallback`, a stub that makes no call: Needs Harsha);
    4. writes one **learning-log** entry (`RisiMe.Agent.LearningLog`) for the call, success or
       not: call_ref, task, alias, real model, provider, latency, tokens, cost (0 locally),
       confidence, status, the derived output, and the input **only** as source message ids plus
       the SHA-256 of the prompt, never its text.

  Returns `{:ok, %{call_ref, output, confidence}}` or `{:error, :rate_limited}` /
  `{:error, :model_unavailable}`. Nothing here logs a prompt or an output.
  """

  alias RisiMe.Agent.LearningLog
  alias RisiMe.Agent.LLM.Schema

  @doc "One chat completion. `{:ok, %{content, model, prompt_tokens, completion_tokens}}`."
  @callback chat(body :: map, opts :: keyword) ::
              {:ok,
               %{
                 content: String.t(),
                 model: String.t(),
                 prompt_tokens: integer | nil,
                 completion_tokens: integer | nil
               }}
              | {:error, atom}

  @doc "The provider name written to the learning log."
  @callback name() :: String.t()

  @alias "risi-l1"
  @max_in_flight 16

  @type request :: %{
          required(:task) => String.t(),
          required(:conversation_id) => String.t(),
          required(:chat_id) => String.t(),
          required(:system) => String.t(),
          required(:user) => String.t(),
          required(:schema_name) => String.t(),
          required(:schema) => map,
          required(:source_message_ids) => [String.t()],
          optional(:max_tokens) => pos_integer,
          optional(:confidence) => (map -> float)
        }

  @doc "Runs one task through the cascade (see the module doc)."
  @spec complete(request) ::
          {:ok, %{call_ref: String.t(), output: map, confidence: float}}
          | {:error, :rate_limited | :model_unavailable}
  def complete(req) do
    case acquire() do
      :ok ->
        try do
          run(req)
        after
          release()
        end

      :full ->
        {:error, :rate_limited}
    end
  end

  defp run(req) do
    messages = [
      %{"role" => "system", "content" => req.system},
      %{"role" => "user", "content" => req.user}
    ]

    body = %{
      "model" => @alias,
      "messages" => messages,
      "temperature" => 0.2,
      "top_p" => 0.8,
      "max_tokens" => Map.get(req, :max_tokens, 800),
      "chat_template_kwargs" => %{"enable_thinking" => false},
      "response_format" => %{
        "type" => "json_schema",
        "json_schema" => %{"name" => req.schema_name, "schema" => req.schema, "strict" => true}
      }
    }

    started = System.monotonic_time(:millisecond)
    local = provider()
    result = attempt(local, body, req.schema, 2)
    confidence_of = Map.get(req, :confidence, &default_confidence/1)

    {provider, result, fallback} =
      case result do
        {:ok, resp, output} ->
          conf = confidence_of.(output)

          if fallback_on?() and conf < threshold() do
            fb = fallback_provider()

            case attempt(fb, body, req.schema, 1) do
              {:ok, resp2, output2} -> {fb, {:ok, resp2, output2}, "used"}
              {:error, _} -> {local, {:ok, resp, output}, "unavailable"}
            end
          else
            {local, result, if(fallback_on?(), do: "not_needed", else: "off")}
          end

        error ->
          {local, error, if(fallback_on?(), do: "not_tried", else: "off")}
      end

    latency = System.monotonic_time(:millisecond) - started
    call_id = RisiMe.TimeUUID.generate()

    entry = %{
      call_id: call_id,
      conversation_id: req.conversation_id,
      chat_id: req.chat_id,
      task: req.task,
      model_alias: @alias,
      provider: provider.name(),
      fallback: fallback,
      latency_ms: latency,
      cost: 0.0,
      source_message_ids: req.source_message_ids,
      prompt_sha256: prompt_hash(messages)
    }

    case result do
      {:ok, resp, output} ->
        conf = confidence_of.(output)

        log(
          Map.merge(entry, %{
            model: resp.model,
            prompt_tokens: resp.prompt_tokens,
            completion_tokens: resp.completion_tokens,
            confidence: conf,
            status: "ok",
            output: Jason.encode!(output)
          })
        )

        {:ok, %{call_ref: call_id, output: output, confidence: conf}}

      {:error, reason} ->
        log(Map.merge(entry, %{model: nil, status: Atom.to_string(reason)}))
        {:error, :model_unavailable}
    end
  end

  # Calls `mod`, decodes and checks the JSON; retries `tries - 1` times on a retryable failure.
  defp attempt(mod, body, schema, tries) do
    result =
      case mod.chat(body, []) do
        {:ok, resp} ->
          with {:ok, output} <- decode(resp.content),
               :ok <- Schema.validate(schema, output) do
            {:ok, resp, output}
          else
            _ -> {:error, :invalid_output}
          end

        {:error, _} = e ->
          e
      end

    case result do
      {:error, reason}
      when tries > 1 and reason in [:timeout, :transport, :http_5xx, :busy, :invalid_output] ->
        attempt(mod, body, schema, tries - 1)

      other ->
        other
    end
  end

  defp decode(content) do
    content
    |> String.trim()
    |> strip_fences()
    |> Jason.decode()
    |> case do
      {:ok, %{} = map} -> {:ok, map}
      _ -> {:error, :invalid_output}
    end
  end

  defp strip_fences("```" <> rest) do
    rest
    |> String.split("\n", parts: 2)
    |> List.last()
    |> String.trim_trailing()
    |> String.trim_trailing("```")
  end

  defp strip_fences(s), do: s

  defp default_confidence(%{"confidence" => c}) when is_number(c), do: c / 1
  defp default_confidence(_), do: 1.0

  @doc "Hex SHA-256 of the exact request messages (the only trace of the prompt that is kept)."
  def prompt_hash(messages),
    do: :crypto.hash(:sha256, Jason.encode!(messages)) |> Base.encode16(case: :lower)

  defp log(entry) do
    LearningLog.record(entry)
  rescue
    e ->
      require Logger
      Logger.warning("Risi learning log write failed: #{inspect(e.__struct__)}")
  end

  ## Config

  defp config, do: Application.get_env(:risime, :risi_llm, [])
  defp provider, do: config()[:provider] || RisiMe.Agent.LLM.Local
  defp fallback_provider, do: config()[:fallback_provider] || RisiMe.Agent.LLM.Fallback

  @doc "True while `RISI_FALLBACK=on` (default off; Needs Harsha: provider + zero retention)."
  def fallback_on?, do: config()[:fallback] == true

  defp threshold, do: config()[:fallback_threshold] || 0.5

  ## The global Risi queue (§24.13): 16 in flight on this node.

  @doc "Calls in flight now."
  def in_flight, do: :atomics.get(gate(), 1)

  @doc false
  def max_in_flight, do: config()[:max_in_flight] || @max_in_flight

  defp acquire do
    ref = gate()

    if :atomics.add_get(ref, 1, 1) > max_in_flight() do
      :atomics.sub(ref, 1, 1)
      :full
    else
      :ok
    end
  end

  defp release, do: :atomics.sub(gate(), 1, 1)

  @doc false
  def init_gate do
    unless :persistent_term.get({__MODULE__, :gate}, nil),
      do: :persistent_term.put({__MODULE__, :gate}, :atomics.new(1, signed: true))

    :ok
  end

  defp gate do
    case :persistent_term.get({__MODULE__, :gate}, nil) do
      nil ->
        init_gate()
        :persistent_term.get({__MODULE__, :gate})

      ref ->
        ref
    end
  end
end
