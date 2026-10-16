defmodule RisiRegistry.Cards.JCS do
  @moduledoc """
  RFC 8785 JSON Canonicalization Scheme (JCS).

  Input is decoded JSON (maps with string keys, lists, strings, numbers, booleans, nil).
  Output is the canonical UTF-8 text:

    * object members sorted by their keys' UTF-16 code units;
    * no whitespace;
    * strings escaped as ECMAScript `JSON.stringify` does (only `"`, `\\`, and control
      characters; everything else is literal UTF-8);
    * numbers serialised as ECMAScript `Number.prototype.toString` (shortest round-trip digits).
  """

  @max_safe_integer 9_007_199_254_740_991

  @spec encode(term()) :: {:ok, binary()} | {:error, term()}
  def encode(value) do
    {:ok, value |> do_encode() |> IO.iodata_to_binary()}
  catch
    {:jcs_error, reason} -> {:error, reason}
  end

  @spec encode!(term()) :: binary()
  def encode!(value) do
    case encode(value) do
      {:ok, bin} -> bin
      {:error, reason} -> raise ArgumentError, "cannot canonicalise: #{inspect(reason)}"
    end
  end

  defp do_encode(nil), do: "null"
  defp do_encode(true), do: "true"
  defp do_encode(false), do: "false"
  defp do_encode(s) when is_binary(s), do: encode_string(s)

  defp do_encode(i) when is_integer(i) and abs(i) <= @max_safe_integer,
    do: Integer.to_string(i)

  # ECMAScript numbers are IEEE doubles: a larger integer is serialised as the double it becomes.
  defp do_encode(i) when is_integer(i), do: encode_float(i * 1.0)
  defp do_encode(f) when is_float(f), do: encode_float(f)

  defp do_encode(list) when is_list(list),
    do: ["[", list |> Enum.map(&do_encode/1) |> join(), "]"]

  defp do_encode(map) when is_map(map) and not is_struct(map) do
    members =
      map
      |> Enum.map(fn {k, v} -> {key_string(k), v} end)
      |> Enum.sort_by(fn {k, _} -> :unicode.characters_to_binary(k, :utf8, :utf16) end)
      |> Enum.map(fn {k, v} -> [encode_string(k), ":", do_encode(v)] end)

    ["{", join(members), "}"]
  end

  defp do_encode(other), do: throw({:jcs_error, {:unsupported, other}})

  defp key_string(k) when is_binary(k), do: k
  defp key_string(k) when is_atom(k) and k not in [nil, true, false], do: Atom.to_string(k)
  defp key_string(k), do: throw({:jcs_error, {:bad_key, k}})

  defp join([]), do: []
  defp join([h | t]), do: [h | Enum.map(t, &[",", &1])]

  defp encode_string(s) do
    unless String.valid?(s), do: throw({:jcs_error, :invalid_utf8})
    [?", escape(s, []), ?"]
  end

  defp escape(<<>>, acc), do: Enum.reverse(acc)
  defp escape(<<?", rest::binary>>, acc), do: escape(rest, [~S(\") | acc])
  defp escape(<<?\\, rest::binary>>, acc), do: escape(rest, [~S(\\) | acc])
  defp escape(<<?\b, rest::binary>>, acc), do: escape(rest, [~S(\b) | acc])
  defp escape(<<?\t, rest::binary>>, acc), do: escape(rest, [~S(\t) | acc])
  defp escape(<<?\n, rest::binary>>, acc), do: escape(rest, [~S(\n) | acc])
  defp escape(<<?\f, rest::binary>>, acc), do: escape(rest, [~S(\f) | acc])
  defp escape(<<?\r, rest::binary>>, acc), do: escape(rest, [~S(\r) | acc])

  defp escape(<<c, rest::binary>>, acc) when c < 0x20 do
    hex = c |> Integer.to_string(16) |> String.downcase() |> String.pad_leading(4, "0")
    escape(rest, ["\\u" <> hex | acc])
  end

  defp escape(<<c::utf8, rest::binary>>, acc), do: escape(rest, [<<c::utf8>> | acc])

  # ECMAScript Number::toString for finite doubles (ECMA-262 7.1.12.1).
  defp encode_float(f) when f == 0, do: "0"
  defp encode_float(f) when f < 0, do: ["-", encode_float(-f)]

  defp encode_float(f) do
    {digits, n} = shortest_digits(f)
    k = byte_size(digits)

    cond do
      k <= n and n <= 21 ->
        [digits, String.duplicate("0", n - k)]

      0 < n and n <= 21 ->
        [binary_part(digits, 0, n), ".", binary_part(digits, n, k - n)]

      -6 < n and n <= 0 ->
        ["0.", String.duplicate("0", -n), digits]

      true ->
        e = n - 1
        sign = if e < 0, do: "-", else: "+"
        exp = ["e", sign, Integer.to_string(abs(e))]

        if k == 1,
          do: [digits, exp],
          else: [binary_part(digits, 0, 1), ".", binary_part(digits, 1, k - 1), exp]
    end
  end

  # Returns {digits, n}: the shortest round-trip decimal digits (no leading or trailing zeros)
  # and n, such that the value is 0.digits * 10^n.
  defp shortest_digits(f) do
    text = :erlang.float_to_binary(f, [:short])

    {mantissa, exp} =
      case String.split(text, ["e", "E"]) do
        [m, e] -> {m, String.to_integer(e)}
        [m] -> {m, 0}
      end

    {int_part, frac_part} =
      case String.split(mantissa, ".") do
        [i, fr] -> {i, fr}
        [i] -> {i, ""}
      end

    all = int_part <> frac_part
    point = byte_size(int_part) + exp
    stripped = String.trim_leading(all, "0")
    point = point - (byte_size(all) - byte_size(stripped))
    {String.trim_trailing(stripped, "0"), point}
  end
end
