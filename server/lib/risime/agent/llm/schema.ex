defmodule RisiMe.Agent.LLM.Schema do
  @moduledoc """
  A small JSON-schema checker for model output: the subset Risi's schemas use (`type` incl. a
  list of types, `properties`, `required`, `additionalProperties: false`, `items`, `maxItems`,
  `enum`, `anyOf`, `maxLength`, `pattern`, `minimum`, `maximum`). Guided decoding should already
  guarantee the shape; this is the server's own check before anything derived is used.
  """

  @doc "`:ok` or `{:error, path}` naming the first offending path."
  def validate(schema, value), do: check(schema, value, "$")

  defp check(%{"anyOf" => alts}, v, path) do
    if Enum.any?(alts, &(check(&1, v, path) == :ok)), do: :ok, else: {:error, path}
  end

  defp check(%{"type" => types} = s, v, path) when is_list(types) do
    if Enum.any?(types, &(check(Map.put(s, "type", &1), v, path) == :ok)),
      do: :ok,
      else: {:error, path}
  end

  defp check(%{"enum" => enum}, v, path),
    do: if(v in enum, do: :ok, else: {:error, path})

  defp check(%{"type" => "object"} = s, v, path) when is_map(v) do
    props = s["properties"] || %{}

    cond do
      not Enum.all?(s["required"] || [], &Map.has_key?(v, &1)) ->
        {:error, path}

      s["additionalProperties"] == false and not Enum.all?(Map.keys(v), &Map.has_key?(props, &1)) ->
        {:error, path}

      true ->
        Enum.find_value(v, :ok, fn {k, val} ->
          case props[k] do
            nil ->
              nil

            ps ->
              case check(ps, val, path <> "." <> k) do
                :ok -> nil
                e -> e
              end
          end
        end)
    end
  end

  defp check(%{"type" => "array"} = s, v, path) when is_list(v) do
    if is_integer(s["maxItems"]) and length(v) > s["maxItems"] do
      {:error, path}
    else
      items = s["items"] || %{}

      v
      |> Enum.with_index()
      |> Enum.find_value(:ok, fn {x, i} ->
        case check(items, x, "#{path}[#{i}]") do
          :ok -> nil
          e -> e
        end
      end)
    end
  end

  defp check(%{"type" => "string"} = s, v, path) when is_binary(v) do
    cond do
      is_integer(s["maxLength"]) and String.length(v) > s["maxLength"] ->
        {:error, path}

      is_binary(s["pattern"]) and not Regex.match?(Regex.compile!(s["pattern"]), v) ->
        {:error, path}

      true ->
        :ok
    end
  end

  defp check(%{"type" => "number"} = s, v, path) when is_number(v) do
    cond do
      is_number(s["minimum"]) and v < s["minimum"] -> {:error, path}
      is_number(s["maximum"]) and v > s["maximum"] -> {:error, path}
      true -> :ok
    end
  end

  defp check(%{"type" => "integer"} = s, v, path) when is_integer(v),
    do: check(Map.put(s, "type", "number"), v, path)

  defp check(%{"type" => "boolean"}, v, _path) when is_boolean(v), do: :ok
  defp check(%{"type" => "null"}, nil, _path), do: :ok
  defp check(%{"type" => _}, _v, path), do: {:error, path}
  defp check(_schema, _v, _path), do: :ok
end
