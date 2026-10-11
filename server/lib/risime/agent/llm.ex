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
       temperature 0.2, top_p 0.8; an upstream 429, 5xx, timeout or transport error is retried
       with exponential backoff (1 s, 2 s, 4 s: at most 3 retries, never past the request's
       `:deadline_ms`), an output that fails the schema once at once (hotfix 2026-10-09). Every
       upstream failure logs `risi llm: provider=… status=429|5xx|timeout|transport attempt=n`
       (no content);
    3. when the local provider still fails, or (only with `RISI_FALLBACK=on`, default **off**)
       the answer's confidence is below `RISI_FALLBACK_THRESHOLD` (default 0.5), the other
       provider is tried if it is configured (`RisiMe.Agent.LLM.Fallback`, a stub that is not
       configured and makes no call: Needs Harsha);
    4. writes one **learning-log** entry (`RisiMe.Agent.LearningLog`) for the call, success or
       not: call_ref, task, alias, real model, provider, latency, tokens, cost (0 locally),
       confidence, status, the derived output, and the input **only** as source message ids plus
       the SHA-256 of the prompt, never its text.

  Returns `{:ok, %{call_ref, output, confidence}}` or `{:error, :rate_limited}` /
  `{:error, :model_unavailable}`. Nothing here logs a prompt or an output.

  A request with `invalid_output: :return` (the turn loop, fix 2026-10-09) is not retried blindly
  on an output that fails the schema: it gets `{:error, {:invalid_output, output | nil,
  call_ref}}` (the decoded JSON, if any) so the caller can correct the model once and then end
  deterministically. An invalid output is never `:model_unavailable` there.
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
          optional(:confidence) => (map -> float),
          optional(:history) => [map],
          optional(:timeout_ms) => pos_integer,
          optional(:deadline_ms) => integer,
          optional(:invalid_output) => :retry | :return
        }

  @doc "Runs one task through the cascade (see the module doc)."
  @spec complete(request) ::
          {:ok, %{call_ref: String.t(), output: map, confidence: float}}
          | {:error, :rate_limited | :model_unavailable}
          | {:error, {:invalid_output, map | nil, String.t()}}
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
    # §25.1: earlier steps of a turn (and a post-check retry) follow the request as
    # assistant/user pairs.
    messages =
      [
        %{"role" => "system", "content" => req.system},
        %{"role" => "user", "content" => req.user}
      ] ++ Map.get(req, :history, [])

    body = %{
      "model" => @alias,
      "messages" => messages,
      "temperature" => 0.2,
      "top_p" => 0.8,
      "max_tokens" => Map.get(req, :max_tokens, 800),
      "chat_template_kwargs" => %{"enable_thinking" => false},
      "response_format" => %{
        "type" => "json_schema",
        "json_schema" => %{
          "name" => req.schema_name,
          "schema" => RisiMe.Agent.Tools.wire_schema(req.schema),
          "strict" => true
        }
      }
    }

    started = System.monotonic_time(:millisecond)
    local = provider()
    opts = call_opts(req)
    result = attempt(local, body, req.schema, 1, opts)
    confidence_of = Map.get(req, :confidence, &default_confidence/1)
    fb = fallback_provider()

    {provider, result, fallback} =
      case result do
        {:ok, resp, output} ->
          conf = confidence_of.(output)

          if fallback_on?() and conf < threshold() do
            case attempt(fb, body, req.schema, 1, Keyword.put(opts, :retries, 0)) do
              {:ok, resp2, output2} -> {fb, {:ok, resp2, output2}, "used"}
              _ -> {local, {:ok, resp, output}, "unavailable"}
            end
          else
            {local, result, if(fallback_on?(), do: "not_needed", else: "off")}
          end

        error ->
          # The local model keeps failing: the other model, when one is configured.
          if configured?(fb) do
            case attempt(fb, body, req.schema, 1, opts) do
              {:ok, _, _} = ok -> {fb, ok, "used"}
              _ -> {local, error, "unavailable"}
            end
          else
            {local, error, if(fallback_on?(), do: "not_tried", else: "off")}
          end
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
            output: logged_output(req.task, output)
          })
        )

        {:ok, %{call_ref: call_id, output: output, confidence: conf}}

      {:error, :invalid_output, output} ->
        log(Map.merge(entry, %{model: nil, status: "invalid_output"}))

        if opts[:invalid] == :return,
          do: {:error, {:invalid_output, output, call_id}},
          else: {:error, :model_unavailable}

      {:error, reason} ->
        log(Map.merge(entry, %{model: nil, status: Atom.to_string(reason)}))
        {:error, :model_unavailable}
    end
  end

  # v1.26 §26.4/§26.6 (decision 068: steps are hashes only): a tool-loop action is logged as
  # its tool and the SHA-256 of its args, never the args (a scheduled message's text, a
  # reminder's text, an event title) or a final's answer.
  defp logged_output("risi_next_action", %{"tool" => tool} = out) do
    Jason.encode!(%{
      "tool" => tool,
      "args_sha256" => RisiMe.Agent.TurnSteps.args_hash(out["args"] || out["answer"] || %{})
    })
  end

  defp logged_output(_task, output), do: Jason.encode!(output)

  @upstream [:timeout, :transport, :http_5xx, :busy]

  defp call_opts(req) do
    [
      retries: Map.get(req, :retries, config()[:retries] || 3),
      backoff_ms: config()[:backoff_ms] || 1_000,
      deadline_ms: Map.get(req, :deadline_ms),
      invalid: Map.get(req, :invalid_output, :retry),
      chat: if(t = Map.get(req, :timeout_ms), do: [timeout: t], else: [])
    ]
  end

  defp configured?(mod) do
    Code.ensure_loaded?(mod) and function_exported?(mod, :configured?, 0) and mod.configured?()
  end

  # Calls `mod`, decodes and checks the JSON. Upstream failures (429, 5xx, timeout, transport)
  # are retried with exponential backoff (`backoff_ms` · 2^(n-1), at most `retries` times, never
  # past the deadline); an output failing the schema is retried once at once.
  defp attempt(mod, body, schema, n, opts) do
    chat_opts = clamp_timeout(opts[:chat], opts[:deadline_ms])

    result =
      case mod.chat(body, chat_opts) do
        {:ok, resp} ->
          case decode(resp.content) do
            {:ok, output} ->
              if Schema.validate(schema, output) == :ok,
                do: {:ok, resp, output},
                else: {:error, :invalid_output, output}

            _ ->
              {:error, :invalid_output, nil}
          end

        {:error, _} = e ->
          e
      end

    case result do
      # Retried once at once, unless the caller corrects the model itself.
      {:error, :invalid_output, _} ->
        log_failure(mod, :invalid_output, n)

        if n == 1 and opts[:invalid] != :return,
          do: attempt(mod, body, schema, n + 1, opts),
          else: result

      {:error, reason} when reason in @upstream ->
        log_failure(mod, reason, n)
        wait = opts[:backoff_ms] * Integer.pow(2, n - 1)

        if n <= opts[:retries] and time_left?(opts[:deadline_ms], wait) do
          if wait > 0, do: Process.sleep(wait)
          attempt(mod, body, schema, n + 1, opts)
        else
          result
        end

      other ->
        other
    end
  end

  defp clamp_timeout(chat_opts, nil), do: chat_opts

  defp clamp_timeout(chat_opts, deadline) do
    left = max(deadline - System.monotonic_time(:millisecond), 1)
    Keyword.update(chat_opts, :timeout, left, &min(&1, left))
  end

  # A retry is worth it only when it can still start (and get a little time) before the deadline.
  defp time_left?(nil, _wait), do: true

  defp time_left?(deadline, wait),
    do: System.monotonic_time(:millisecond) + wait + 500 < deadline

  defp log_failure(mod, reason, n) do
    require Logger

    status =
      case reason do
        :busy -> "429"
        :http_5xx -> "5xx"
        other -> Atom.to_string(other)
      end

    Logger.warning("risi llm: provider=#{mod.name()} status=#{status} attempt=#{n}")
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

  @doc """
  v1.29 §29.7: true when every call of a turn can only run on a RisiMe model (the local
  `risi_l1` provider, the fallback off and not configured): only then may Risi Calendar titles
  reach the model; a commercial model never sees them.
  """
  def own_only? do
    provider().name() == "risi_l1" and not fallback_on?() and not configured?(fallback_provider())
  rescue
    _ -> false
  end

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
