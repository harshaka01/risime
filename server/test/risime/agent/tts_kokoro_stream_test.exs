defmodule RisiMe.Agent.TTS.KokoroStreamTest do
  @moduledoc """
  The Kokoro client over real HTTP (Bandit on 127.0.0.1): the service streams PCM in several
  chunks, and every chunk is kept in order (16 Oct: the first chunk was dropped and a reply of
  more than two chunks crashed, so "Prepare the samples" failed for every voice).
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.TTS.Kokoro

  defmodule Chunks do
    import Plug.Conn
    def init(o), do: o

    def call(conn, _) do
      conn = send_chunked(conn, 200)

      Enum.reduce([<<1, 1>>, <<2, 2>>, <<3, 3>>], conn, fn c, conn ->
        {:ok, conn} = chunk(conn, c)
        Process.sleep(20)
        conn
      end)
    end
  end

  setup do
    {:ok, server} =
      Bandit.start_link(plug: Chunks, ip: {127, 0, 0, 1}, port: 0, startup_log: false)

    {:ok, {_ip, port}} = ThousandIsland.listener_info(server)
    prev = Application.get_env(:risime, :risi_call_risi_config)

    Application.put_env(:risime, :risi_call_risi_config, tts_url: "http://127.0.0.1:#{port}")

    on_exit(fn ->
      if prev,
        do: Application.put_env(:risime, :risi_call_risi_config, prev),
        else: Application.delete_env(:risime, :risi_call_risi_config)
    end)

    :ok
  end

  test "every streamed chunk is in the WAV, in order" do
    assert {:ok, %{audio: wav, content_type: "audio/wav"}} =
             Kokoro.synthesize("A longer sentence.", "kokoro:af_heart")

    <<_header::binary-size(44), pcm::binary>> = wav
    assert pcm == <<1, 1, 2, 2, 3, 3>>
  end
end
