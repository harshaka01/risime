defmodule RisiMe.Agent.CompactJson do
  @moduledoc """
  A JSON-schema object as a compact EBNF grammar for vLLM's guided decoding
  (`structured_outputs.grammar`, xgrammar).

  Item 11 (call latency, 17 Oct): with `response_format: json_schema` the model writes its
  decision pretty-printed (a newline and indentation before every field), about 65 tokens for a
  ~35-token decision, at ~20 ms a token. This grammar allows no whitespace, so the same decision
  costs ~35 tokens (−0.6 s a turn), with the prompt unchanged. (A pretty-printed first field and
  compact rest was tried: 4 gate failures against 1 for all-compact.) The fields come in the
  order `RisiMe.Agent.Tools.wire_schema/1` sends them (`tool` first, then by name), so the model
  sees the same order as before.

  Only flat objects: each property is an `enum` of strings, or a `string`, `number` or
  `boolean`. The first property must be required. `nil` for anything else (the caller then keeps
  the JSON schema).
  """

  @basic ~S"""
  basic_escape ::= (([\"\\/bfnrt]) | ("u" [A-Fa-f0-9] [A-Fa-f0-9] [A-Fa-f0-9] [A-Fa-f0-9]))
  basic_string_sub ::= (("\"") | ([^\0-\x1f\"\\\r\n] basic_string_sub) | ("\\" basic_escape basic_string_sub))
  basic_string ::= (("\"" basic_string_sub))
  basic_boolean ::= (("true") | ("false"))
  basic_number ::= ((basic_sign basic_int basic_frac basic_exp))
  basic_sign ::= ("" | ("-"))
  basic_int ::= (("0") | ([1-9] [0-9]*))
  basic_digits ::= (([0-9] basic_digits) | ([0-9]))
  basic_frac ::= ("" | ("." basic_digits))
  basic_exp ::= ("" | ([eE] basic_exp_sign basic_digits))
  basic_exp_sign ::= ("" | ([+\-]))
  """

  @first ~w(tool)

  @doc "The grammar text for `schema`, or nil when the schema isn't a flat object."
  def grammar(%{"type" => "object", "properties" => props} = schema) when map_size(props) > 0 do
    required = Map.get(schema, "required", [])
    keys = Enum.filter(@first, &Map.has_key?(props, &1))
    keys = keys ++ Enum.sort(Map.keys(props) -- keys)
    fields = Enum.with_index(keys, fn key, i -> {key, value(props[key], i), i} end)
    n = length(keys)

    if Enum.any?(fields, &(elem(&1, 1) == nil)) or hd(keys) not in required do
      nil
    else
      rules =
        for {key, v, i} <- fields do
          kv = ~s("#{lit(key)}" ":" #{v})

          cond do
            i == 0 -> ~s<root ::= (("{" #{kv} root_part_1 "}"))>
            key in required -> ~s<root_part_#{i} ::= (("," #{kv} root_part_#{i + 1}))>
            true -> ~s<root_part_#{i} ::= ((root_part_#{i + 1}) | ("," #{kv} root_part_#{i + 1}))>
          end
        end

      enums =
        for {key, _, i} <- fields, is_list(props[key]["enum"]) do
          alts = Enum.map_join(props[key]["enum"], " | ", &~s<("#{lit(&1)}")>)
          ~s<root_enum_#{i} ::= (#{alts})>
        end

      Enum.join(
        [String.trim_trailing(@basic) | rules ++ enums ++ [~s<root_part_#{n} ::= ("")>]],
        "\n"
      )
    end
  end

  def grammar(_schema), do: nil

  defp value(%{"enum" => [_ | _] = list}, i),
    do: if(Enum.all?(list, &is_binary/1), do: "root_enum_#{i}")

  defp value(%{"type" => "string"}, _), do: "basic_string"
  defp value(%{"type" => "number"}, _), do: "basic_number"
  defp value(%{"type" => "boolean"}, _), do: "basic_boolean"
  defp value(_, _), do: nil

  # A JSON string (with its quotes) as the inside of an EBNF string literal.
  defp lit(s),
    do: s |> Jason.encode!() |> String.replace("\\", "\\\\") |> String.replace("\"", "\\\"")
end
