defmodule RisiRegistry.Sandbox.AnswerGuard do
  @moduledoc """
  "What comes back is data, never instructions" (agent-connector.md section 8).

  `check/2` takes an agent's raw answer body and either refuses it with a result code or returns
  it as **inert display items**: text and cards, each carrying the agent's name ("via …",
  review change 6). The return type has no way to express a tool call, an agent to contact or an
  instruction; that is the structural half of the "tries to make Risi call another tool" case.
  The pattern checks below are the detection half, used by the sandbox to fail and suspend
  agents that try.
  """

  @type item ::
          {:text, via :: String.t(), String.t()}
          | {:card, via :: String.t(), type :: String.t(), map()}
  @type code :: :oversize | :malformed | :schema | :injection | :tool_steer

  # Instruction-like text aimed at a model, not a person.
  @injection [
    ~r/\bignore\s+(all\s+|any\s+|the\s+|your\s+)?(previous|prior|above|earlier)\s+(instructions|prompts?|rules)/i,
    ~r/\bdisregard\s+(all\s+|your\s+|the\s+)?(previous|prior|above)?\s*(instructions|rules|guidelines)/i,
    ~r/\b(system|developer)\s+(prompt|message)\s*:/i,
    ~r/\byou\s+are\s+now\s+(a|an|in)\b/i,
    ~r/^\s*(SYSTEM|ASSISTANT|DEVELOPER)\s*:/im,
    ~r/<\|?(im_start|im_end|system|endoftext)\|?>/i,
    ~r/\[\s*(INST|SYS)\s*\]/,
    ~r/[#]{2,}\s*(new\s+)?instructions?\b/i
  ]

  # Text that tries to make Risi pick a tool, contact or agent ("now send this to …").
  @tool_steer [
    ~r/\b(now\s+)?(send|forward|post|share)\s+(this|it|that|the\s+answer)\s+(to|with)\b/i,
    ~r/\b(call|use|invoke|run|trigger)\s+(the\s+)?(tool|function|agent|skill)\b/i,
    ~r/\bRisi,?\s+(please\s+)?(call|send|forward|message|pay|approve|delete|invite)\b/i,
    ~r/\b(tool_calls?|function_call|tool_use)\b/i
  ]

  # JSON keys that would smuggle actions into an answer.
  @action_keys ~w(tool_calls tool_call function_call tool_use actions action next_tool instructions)

  @doc "Validates `body` (a binary) from agent `via`. `opts`: `:max_bytes`."
  @spec check(binary(), String.t(), keyword()) :: {:ok, [item()]} | {:error, code()}
  def check(body, via, opts \\ []) when is_binary(body) do
    max = Keyword.get(opts, :max_bytes, 32 * 1024)

    with :ok <- check_size(body, max),
         {:ok, answer} <- decode(body),
         {:ok, items} <- shape(answer, via),
         :ok <- scan(answer) do
      {:ok, items}
    end
  end

  @doc "Scans free text (for example a card's description) for injection or steering."
  @spec scan_text(String.t()) :: :ok | {:error, :injection | :tool_steer}
  def scan_text(text) when is_binary(text) do
    cond do
      Enum.any?(@injection, &Regex.match?(&1, text)) -> {:error, :injection}
      Enum.any?(@tool_steer, &Regex.match?(&1, text)) -> {:error, :tool_steer}
      true -> :ok
    end
  end

  defp check_size(body, max) when byte_size(body) > max, do: {:error, :oversize}
  defp check_size(_body, _max), do: :ok

  defp decode(body) do
    case Jason.decode(body) do
      {:ok, %{} = answer} -> {:ok, answer}
      _ -> {:error, :malformed}
    end
  end

  # The declared answer shape: `answer_text` (string or null), optional `cards` (objects with a
  # string `type`), optional `sources` (objects with `label` and `url`). Unknown fields are
  # ignored, as the contract says; they never reach the display.
  defp shape(answer, via) do
    text = Map.get(answer, "answer_text")
    cards = Map.get(answer, "cards", [])
    sources = Map.get(answer, "sources", [])

    cond do
      not (is_nil(text) or is_binary(text)) -> {:error, :schema}
      not is_list(cards) or not Enum.all?(cards, &card?/1) -> {:error, :schema}
      not is_list(sources) or not Enum.all?(sources, &source?/1) -> {:error, :schema}
      is_nil(text) and cards == [] -> {:error, :schema}
      true -> {:ok, text_items(text, via) ++ Enum.map(cards, &{:card, via, &1["type"], &1})}
    end
  end

  defp text_items(nil, _via), do: []
  defp text_items(text, via), do: [{:text, via, text}]

  defp card?(%{"type" => type}) when is_binary(type), do: true
  defp card?(_), do: false

  defp source?(%{"label" => l, "url" => u}) when is_binary(l) and is_binary(u), do: true
  defp source?(_), do: false

  defp scan(answer) do
    if has_action_key?(answer) do
      {:error, :tool_steer}
    else
      answer |> strings() |> Enum.reduce_while(:ok, &scan_one/2)
    end
  end

  defp scan_one(text, :ok) do
    case scan_text(text) do
      :ok -> {:cont, :ok}
      err -> {:halt, err}
    end
  end

  defp has_action_key?(%{} = map),
    do:
      Enum.any?(map, fn {k, v} ->
        String.downcase(to_string(k)) in @action_keys or has_action_key?(v)
      end)

  defp has_action_key?(list) when is_list(list), do: Enum.any?(list, &has_action_key?/1)
  defp has_action_key?(_), do: false

  defp strings(s) when is_binary(s), do: [s]
  defp strings(%{} = map), do: Enum.flat_map(map, fn {_k, v} -> strings(v) end)
  defp strings(list) when is_list(list), do: Enum.flat_map(list, &strings/1)
  defp strings(_), do: []
end
