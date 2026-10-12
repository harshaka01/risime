defmodule RisiMe.Agent.Media do
  @moduledoc """
  Opens an `A256GCM-S64K` blob (contract §14.3) **in memory**, for Risi as an MLS member of an
  Official conversation (v1.41 §37.6: a voice note). Nothing is written anywhere: the input and
  the output are binaries, and callers drop both right after use.

  `open/3` follows §14.3 exactly: `Kp = HKDF-SHA256(salt = "", IKM = K, info =
  "risime-media-v1 A256GCM-S64K", L = 32)`; segments of 65 536 plaintext bytes, each sealed with
  AES-256-GCM under nonce `0x00×7 ‖ u32be(i) ‖ flag` (flag 1 on the last segment) and AAD
  `risime-media-v1`; the last ciphertext segment ≥ 17 bytes; the padded length must equal
  `Padmé(plain_size)` with zero padding. Any tag failure is `:integrity`, any layout error
  `:format`; no plaintext is returned before every segment verified. There is no encrypt API
  (§14.3: keys are generated inside the crypto core only).
  """

  @seg 65_536
  @cseg @seg + 16
  @aad "risime-media-v1"
  @info "risime-media-v1 A256GCM-S64K"

  @doc "`{:ok, plaintext}` or `{:error, :integrity | :format}`."
  def open(cipher, <<_::binary-size(32)>> = key, plain_size)
      when is_binary(cipher) and is_integer(plain_size) and plain_size >= 1 do
    size = byte_size(cipher)
    n = div(size + @cseg - 1, @cseg)
    last = size - (n - 1) * @cseg

    cond do
      size == 0 or last < 17 -> {:error, :format}
      padme(plain_size) + 16 * n != size -> {:error, :format}
      true -> open_segments(cipher, payload_key(key), n, 0, [])
    end
  end

  def open(_cipher, _key, _plain_size), do: {:error, :format}

  defp open_segments(_cipher, _kp, n, n, acc), do: {:ok, IO.iodata_to_binary(Enum.reverse(acc))}

  defp open_segments(cipher, kp, n, i, acc) do
    final? = i == n - 1
    len = if final?, do: byte_size(cipher) - i * @cseg, else: @cseg
    <<ct::binary-size(len - 16), tag::binary-size(16)>> = binary_part(cipher, i * @cseg, len)
    nonce = <<0::56, i::32, if(final?, do: 1, else: 0)::8>>

    case :crypto.crypto_one_time_aead(:aes_256_gcm, kp, nonce, ct, @aad, tag, false) do
      :error -> {:error, :integrity}
      pt -> open_segments(cipher, kp, n, i + 1, [pt | acc])
    end
  end

  @doc false
  def unpad(padded, plain_size) do
    <<plain::binary-size(plain_size), rest::binary>> = padded

    if rest == :binary.copy(<<0>>, byte_size(rest)),
      do: {:ok, plain},
      else: {:error, :format}
  end

  @doc "Opens and checks the padding: `{:ok, plaintext}` or `{:error, :integrity | :format}`."
  def decrypt(cipher, key, plain_size) do
    with {:ok, padded} <- open(cipher, key, plain_size), do: unpad(padded, plain_size)
  end

  @doc "HKDF-SHA256 with an empty salt, one 32-byte block (§14.3)."
  def payload_key(key) do
    prk = :crypto.mac(:hmac, :sha256, <<0::256>>, key)
    :crypto.mac(:hmac, :sha256, prk, @info <> <<1>>)
  end

  @doc "Padmé(L) (§14.3)."
  def padme(1), do: 1

  def padme(l) when l >= 2 do
    e = floor_log2(l)
    s = floor_log2(e) + 1
    mask = Bitwise.bsl(1, e - s) - 1
    Bitwise.band(l + mask, Bitwise.bnot(mask))
  end

  defp floor_log2(x), do: do_log2(x, -1)
  defp do_log2(0, acc), do: acc
  defp do_log2(x, acc), do: do_log2(Bitwise.bsr(x, 1), acc + 1)
end
