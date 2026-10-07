defmodule RisiMe.MLS.Wire do
  @moduledoc """
  The cleartext header of an MLS application message (RFC 9420 §6, §6.3), and the v1.12
  `authenticated_data` binding of a `delete` control (contract §15.3).

  The server never decrypts. It reads only what MLS leaves in cleartext in a PrivateMessage:

      MLSMessage    = version(u16 = 1) wire_format(u16 = 2, mls_private_message) PrivateMessage
      PrivateMessage = group_id<V> epoch(u64) content_type(u8) authenticated_data<V>
                       encrypted_sender_data<V> ciphertext<V>

  `<V>` is the RFC 9420 §2.1.2 variable-length vector (a 1-, 2- or 4-byte length prefix).
  `authenticated_data` is covered by the sender's signature and the content AEAD, so it can be
  neither altered nor stripped.
  """

  @mls10 1
  @private_message 2
  @application 1

  @type header :: %{
          group_id: binary,
          epoch: non_neg_integer,
          content_type: non_neg_integer,
          authenticated_data: binary
        }

  @doc "Parses an `MLSMessage` carrying a PrivateMessage. `{:ok, header}` or `:error`."
  @spec private_message(binary) :: {:ok, header} | :error
  def private_message(<<@mls10::16, @private_message::16, rest::binary>>) do
    with {:ok, group_id, rest} <- vec(rest),
         <<epoch::64, content_type::8, rest::binary>> <- rest,
         {:ok, aad, rest} <- vec(rest),
         {:ok, _sender_data, rest} <- vec(rest),
         {:ok, _ciphertext, ""} <- vec(rest) do
      {:ok,
       %{group_id: group_id, epoch: epoch, content_type: content_type, authenticated_data: aad}}
    else
      _ -> :error
    end
  end

  def private_message(_), do: :error

  @doc "True for a parsed application message (content_type = application)."
  def application?(%{content_type: ct}), do: ct == @application

  defp vec(<<0::2, n::6, rest::binary>>), do: take(n, rest)
  defp vec(<<1::2, n::14, rest::binary>>), do: take(n, rest)
  defp vec(<<2::2, n::30, rest::binary>>), do: take(n, rest)
  defp vec(_), do: :error

  defp take(n, rest) do
    case rest do
      <<v::binary-size(n), rest::binary>> -> {:ok, v, rest}
      _ -> :error
    end
  end

  @doc """
  The canonical `authenticated_data` of a `delete` control (§15.3): `0x01 0x44` (`'D'`), then
  the distinct targets as 16-byte UUIDs sorted ascending by bytes.
  """
  @spec delete_aad([String.t()]) :: binary
  def delete_aad(targets) do
    ids = targets |> Enum.map(&Ecto.UUID.dump!/1) |> Enum.uniq() |> Enum.sort()
    IO.iodata_to_binary([1, ?D | ids])
  end

  @doc """
  §15.3 server check: `ciphertext` (base64) is an MLS application PrivateMessage whose
  `authenticated_data` is exactly `delete_aad(targets)`.
  """
  @spec delete_bound?(String.t(), [String.t()]) :: boolean
  def delete_bound?(ciphertext, targets) do
    with {:ok, bin} <- Base.decode64(ciphertext),
         {:ok, header} <- private_message(bin) do
      application?(header) and header.authenticated_data == delete_aad(targets)
    else
      _ -> false
    end
  end

  @doc """
  §15.3: a non-`delete` application message must carry an empty `authenticated_data`. True when
  `ciphertext` parses as a PrivateMessage with a non-empty one (anything unparseable passes: the
  server routes opaque bytes and receivers decide).
  """
  @spec nonempty_aad?(String.t()) :: boolean
  def nonempty_aad?(ciphertext) do
    with {:ok, bin} <- Base.decode64(ciphertext),
         {:ok, %{authenticated_data: aad}} <- private_message(bin) do
      aad != ""
    else
      _ -> false
    end
  end

  @doc """
  The canonical `authenticated_data` of a `history_request`/`history_share` envelope (v1.15
  §17.3): `0x01 0x48` (`'H'`) followed by `request_id` as 16 raw bytes (exactly 18 bytes).
  """
  @spec history_aad(String.t()) :: binary
  def history_aad(request_id), do: <<1, ?H>> <> Ecto.UUID.dump!(request_id)

  @doc "True if `aad` is exactly the `'H'` form for `request_id` (§17.3, one canonical parser)."
  @spec history_aad?(binary, String.t()) :: boolean
  def history_aad?(<<1, ?H, id::binary-size(16)>>, request_id) do
    case Ecto.UUID.dump(request_id) do
      {:ok, ^id} -> true
      _ -> false
    end
  end

  def history_aad?(_aad, _request_id), do: false

  @doc "Encodes a PrivateMessage `MLSMessage` with the given header fields (tests, tools)."
  @spec encode_private_message(binary, non_neg_integer, binary, binary, binary) :: binary
  def encode_private_message(group_id, epoch, aad, sender_data, ciphertext) do
    IO.iodata_to_binary([
      <<@mls10::16, @private_message::16>>,
      encode_vec(group_id),
      <<epoch::64, @application::8>>,
      encode_vec(aad),
      encode_vec(sender_data),
      encode_vec(ciphertext)
    ])
  end

  defp encode_vec(v) when byte_size(v) < 64, do: [<<0::2, byte_size(v)::6>>, v]
  defp encode_vec(v) when byte_size(v) < 16_384, do: [<<1::2, byte_size(v)::14>>, v]
  defp encode_vec(v), do: [<<2::2, byte_size(v)::30>>, v]
end
