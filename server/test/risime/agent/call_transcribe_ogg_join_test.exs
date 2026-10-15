defmodule RisiMe.Agent.CallTranscribeOggJoinTest do
  @moduledoc "v1.50 §36.12: joining a speaker's Ogg/Opus chunks without their overlaps (pure)."
  use ExUnit.Case, async: true

  alias RisiMe.Agent.CallTranscribe.OggJoin

  @clip Path.expand("../../../../infra/whisper/testdata/en-jfk.ogg", __DIR__)

  defp audio_ms(ogg) do
    {:ok, [_head, _tags | data]} = OggJoin.packets(ogg)
    div(Enum.sum(Enum.map(data, &OggJoin.samples/1)), 48)
  end

  test "the CRC is Ogg's: it matches the clip's own first page" do
    ogg = File.read!(@clip)
    <<"OggS", _::binary-size(18), crc::little-32, _::binary>> = ogg
    <<_::binary-size(26), n, _::binary>> = ogg
    {:ok, [head | _]} = OggJoin.packets(ogg)
    page_len = 27 + n + byte_size(head)
    <<page::binary-size(page_len), _::binary>> = ogg
    <<pre::binary-size(22), _::32, post::binary>> = page
    assert OggJoin.crc(pre <> <<0::32>> <> post) == crc
  end

  test "joins streams into one parseable stream, leaving out each later chunk's overlap" do
    ogg = File.read!(@clip)
    ms = audio_ms(ogg)
    assert ms > 10_000

    joined = OggJoin.join([{ogg, 0}, {ogg, 500}, {ogg, 0}])
    assert {:ok, [head, <<"OpusTags", _::binary>> | _]} = OggJoin.packets(joined)
    assert <<"OpusHead", _::binary>> = head
    assert_in_delta audio_ms(joined), 3 * ms - 500, 40
    assert binary_part(joined, 0, 4) == "OggS"
  end

  test "one chunk is passed as it is; something unparseable falls back to the chained bytes" do
    ogg = File.read!(@clip)
    assert OggJoin.join([{ogg, 0}]) == ogg
    assert OggJoin.join([{"si:a", 0}, {"si:b", 500}]) == "si:asi:b"
  end

  test "Opus TOC durations" do
    # config 1 (SILK NB 20 ms) code 0; config 19 (CELT 20 ms) code 1 = 2 frames; code 3 = n frames
    assert OggJoin.samples(<<0b00001000>>) == 960
    assert OggJoin.samples(<<0b10011001>>) == 2 * 960
    assert OggJoin.samples(<<0b00001011, 3>>) == 3 * 960
    assert OggJoin.samples(<<>>) == 0
  end
end
