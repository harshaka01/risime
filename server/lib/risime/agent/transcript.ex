defmodule RisiMe.Agent.Transcript do
  @moduledoc """
  Risi's raw buffer of Official plaintext (§24.12, decision 066): `risi_buffer` behind
  `RisiMe.Messaging.Store`, 24-h TTL.

  * Bodies are sealed here, before the store sees them: AES-256-GCM under `RISI_DATA_KEY`, a
    random 96-bit nonce, `sealed = nonce ‖ ciphertext ‖ tag`, and
    `AAD = "risi-buf-v1" ‖ u16be(byte_size(conversation_id)) ‖ conversation_id ‖ message_id`, so
    a row moved to another conversation or message fails to open.
  * `put/2` refuses anything but an Official conversation (`RisiMe.Agent.official?/1`).
  * `purge/1` (Official off, Risi removed) and `delete/2` (a §15 delete for everyone) remove rows.
  """
  require Logger

  alias RisiMe.Messaging.Store

  @aad "risi-buf-v1"

  @doc """
  Buffers one message: `%{message_id, sender_id, sender_device, plaintext}`. `:ok`, or
  `{:error, :private_tab}` for a non-Official conversation (nothing written).
  """
  def put(conv, %{message_id: id, sender_id: sender, plaintext: pt} = m) when is_binary(pt) do
    if RisiMe.Agent.official?(conv) do
      {:ok, key} = RisiMe.Agent.data_key()

      Store.impl().put_agent_message(conv, %{
        message_id: id,
        sender_id: sender,
        sender_device: m[:sender_device],
        body: seal(key, conv, id, pt)
      })
    else
      Logger.warning("Risi buffer refused a non-Official conversation #{conv}")
      {:error, :private_tab}
    end
  end

  @doc """
  The buffered messages of a conversation after `since` (nil = the whole window), oldest first:
  `[%{message_id, sender_id, sender_device, plaintext}]`. Rows that fail to open (sealed under
  another `RISI_DATA_KEY`, P0 2026-10-08) are skipped and deleted.
  """
  def list(conv, since \\ nil, limit \\ 500) do
    {:ok, key} = RisiMe.Agent.data_key()

    {rows, bad} =
      Store.impl().list_agent_messages(conv, since, limit)
      |> Enum.reduce({[], []}, fn row, {ok, bad} ->
        case open(key, conv, row.message_id, row.body) do
          {:ok, pt} -> {[row |> Map.delete(:body) |> Map.put(:plaintext, pt) | ok], bad}
          :error -> {ok, [row.message_id | bad]}
        end
      end)

    if bad != [], do: drop_unreadable(conv, bad)
    Enum.reverse(rows)
  end

  # Best effort: the 24-h TTL removes them anyway.
  defp drop_unreadable(conv, ids) do
    delete(conv, ids)
  rescue
    e -> Logger.warning("Risi buffer: unreadable rows not dropped: #{inspect(e.__struct__)}")
  end

  @doc "Deletes every buffered message of the conversation."
  def purge(conv), do: Store.impl().purge_agent_conversation(conv, DateTime.utc_now())

  @doc "Deletes buffered messages by id (§15 delete for everyone)."
  def delete(conv, ids), do: Store.impl().delete_agent_messages(conv, ids)

  @doc false
  def seal(key, conv, message_id, pt) do
    nonce = :crypto.strong_rand_bytes(12)

    {ct, tag} =
      :crypto.crypto_one_time_aead(:aes_256_gcm, key, nonce, pt, aad(conv, message_id), true)

    nonce <> ct <> tag
  end

  @doc false
  def open(key, conv, message_id, <<nonce::binary-size(12), rest::binary>>)
      when byte_size(rest) >= 16 do
    ct_len = byte_size(rest) - 16
    <<ct::binary-size(ct_len), tag::binary-size(16)>> = rest

    case :crypto.crypto_one_time_aead(
           :aes_256_gcm,
           key,
           nonce,
           ct,
           aad(conv, message_id),
           tag,
           false
         ) do
      :error ->
        Logger.warning("Risi buffer row failed to open in #{conv}")
        :error

      pt ->
        {:ok, pt}
    end
  end

  def open(_key, _conv, _message_id, _sealed), do: :error

  defp aad(conv, message_id),
    do: @aad <> <<byte_size(conv)::16>> <> conv <> message_id
end
