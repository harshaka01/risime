defmodule RisiMe.Agent.CallTranscribe.OggJoin do
  @moduledoc """
  Contract v1.50 §36.12 (proposal 2026-10-15-live-call-latency): a fast session's Sinhala and
  Tamil chunks of one speaker are transcribed together. This joins their Ogg/Opus streams (§36.4
  `ogg_opus`, RFC 7845) into **one** stream in memory: the first chunk's `OpusHead`, then every
  chunk's audio packets, leaving out each chunk's first `drop_ms` (the part that repeats the
  previous chunk, the phone's overlap), re-paged with fresh granule positions and CRCs.

  A chunk that can't be parsed makes `join/1` fall back to the chained streams as they are (the
  speech service decodes chained Ogg too; only the overlap is then not removed). Pure; memory
  only; never logged.
  """
  import Bitwise

  @per_page 50

  @crc (for i <- 0..255 do
          Enum.reduce(1..8, bsl(i, 24), fn _, r ->
            if band(r, 0x80000000) != 0,
              do: band(bxor(bsl(r, 1), 0x04C11DB7), 0xFFFFFFFF),
              else: band(bsl(r, 1), 0xFFFFFFFF)
          end)
        end)
       |> List.to_tuple()

  @doc "One Ogg/Opus stream of `[{ogg, drop_ms}]`, in order."
  def join([{ogg, _}]), do: ogg

  def join(parts) when is_list(parts) and parts != [] do
    parsed = Enum.map(parts, fn {ogg, drop} -> {packets(ogg), drop} end)

    if Enum.all?(parsed, fn {p, _} -> match?({:ok, [_, _ | _]}, p) end) do
      [{{:ok, [head | _]}, _} | _] = parsed

      audio =
        Enum.flat_map(parsed, fn {{:ok, [_head, _tags | data]}, drop} -> drop_ms(data, drop) end)

      mux(head, audio)
    else
      parts |> Enum.map(&elem(&1, 0)) |> IO.iodata_to_binary()
    end
  end

  @doc "The packets of one logical Ogg stream (`{:ok, [binary]}`), or `:error`."
  def packets(ogg) when is_binary(ogg), do: pages(ogg, [], <<>>)

  defp pages(<<>>, acc, <<>>), do: {:ok, Enum.reverse(acc)}
  defp pages(<<>>, _acc, _partial), do: :error

  defp pages(
         <<"OggS", 0, _type, _granule::64, _serial::32, _seq::32, _crc::32, n, rest::binary>>,
         acc,
         partial
       )
       when byte_size(rest) >= n do
    <<lacing::binary-size(n), body::binary>> = rest
    sizes = :binary.bin_to_list(lacing)
    total = Enum.sum(sizes)

    if byte_size(body) < total do
      :error
    else
      <<data::binary-size(total), more::binary>> = body
      {acc, partial} = segments(sizes, data, acc, partial)
      pages(more, acc, partial)
    end
  end

  defp pages(_bad, _acc, _partial), do: :error

  defp segments([], _data, acc, partial), do: {acc, partial}

  defp segments([s | sizes], data, acc, partial) do
    <<seg::binary-size(s), data::binary>> = data
    partial = partial <> seg

    if s < 255,
      do: segments(sizes, data, [partial | acc], <<>>),
      else: segments(sizes, data, acc, partial)
  end

  defp drop_ms(data, ms) when is_integer(ms) and ms > 0 do
    want = ms * 48

    {_, kept} =
      Enum.reduce(data, {0, []}, fn p, {dropped, kept} ->
        if dropped < want, do: {dropped + samples(p), kept}, else: {dropped, [p | kept]}
      end)

    Enum.reverse(kept)
  end

  defp drop_ms(data, _), do: data

  @doc "Samples at 48 kHz in one Opus packet (RFC 6716 §3.1 TOC); 0 for a bad packet."
  def samples(<<toc, rest::binary>>) do
    config = bsr(toc, 3)

    half_ms =
      cond do
        config < 12 -> elem({20, 40, 80, 120}, rem(config, 4))
        config < 16 -> elem({20, 40}, rem(config, 2))
        true -> elem({5, 10, 20, 40}, rem(config, 4))
      end

    frames =
      case {band(toc, 3), rest} do
        {0, _} -> 1
        {c, _} when c in [1, 2] -> 2
        {3, <<n, _::binary>>} -> band(n, 0x3F)
        _ -> 0
      end

    frames * half_ms * 24
  end

  def samples(_), do: 0

  defp mux(head, audio) do
    serial = :erlang.phash2(head, 0xFFFFFFFF)
    pre_skip = pre_skip(head)
    tags = <<"OpusTags", 6::little-32, "RisiMe", 0::little-32>>
    first = [page([head], 0x02, 0, serial, 0), page([tags], 0, 0, serial, 1)]
    groups = Enum.chunk_every(audio, @per_page)
    last = length(groups) - 1

    {pages, _} =
      groups
      |> Enum.with_index()
      |> Enum.map_reduce(pre_skip, fn {g, i}, granule ->
        granule = granule + Enum.sum(Enum.map(g, &samples/1))
        {page(g, if(i == last, do: 0x04, else: 0), granule, serial, i + 2), granule}
      end)

    IO.iodata_to_binary(first ++ pages)
  end

  defp pre_skip(<<"OpusHead", _v, _ch, skip::little-16, _::binary>>), do: skip
  defp pre_skip(_), do: 312

  defp page(packets, type, granule, serial, seq) do
    lacing =
      Enum.flat_map(packets, fn p ->
        n = byte_size(p)
        List.duplicate(255, div(n, 255)) ++ [rem(n, 255)]
      end)

    body = IO.iodata_to_binary(packets)

    hdr =
      <<"OggS", 0, type, granule::little-64, serial::little-32, seq::little-32, 0::32,
        length(lacing)>> <> :binary.list_to_bin(lacing)

    raw = hdr <> body
    <<pre::binary-size(22), _::32, post::binary>> = raw
    pre <> <<crc(raw)::little-32>> <> post
  end

  @doc "The Ogg CRC-32 (poly 0x04C11DB7, no reflection, init 0)."
  def crc(data) do
    for <<b <- data>>, reduce: 0 do
      c -> bxor(band(bsl(c, 8), 0xFFFFFFFF), elem(@crc, band(bxor(bsr(c, 24), b), 0xFF)))
    end
  end
end
