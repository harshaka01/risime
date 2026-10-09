defmodule RisiMe.Agent.Seal do
  @moduledoc """
  AES-256-GCM sealing for Risi's Postgres rows (contract v1.25 §25.5, v1.26 §26.4): a random
  96-bit nonce, `sealed = nonce ‖ ciphertext ‖ tag`, and an AAD that binds the row (its table
  and id), so a sealed value can't be moved to another row.

  Keys:

    * `RISI_DATA_KEY` (`data/0`): what only lives as long as Risi's own work on a request
      (pending writes, reminder texts), next to the 24-h buffer it seals already, and every
      chat-derived text Risi keeps (commitment and fact texts, the learning log's output);
    * `RISI_MEMORY_KEY` (`memory/0`): the skills activity log (§26.4), optional at boot. Without
      it the skills are unavailable (`RisiMe.Agent.Skills.health/0`), never a crash.
  """

  @doc "The data key (`RISI_DATA_KEY`), `{:ok, key}` or `:error`."
  def data, do: RisiMe.Agent.data_key()

  @doc "The memory key (`RISI_MEMORY_KEY`, base64 of 32 bytes), `{:ok, key}` or `:error`."
  def memory do
    with b64 when is_binary(b64) <- Application.get_env(:risime, :risi_memory_key),
         {:ok, <<_::binary-size(32)>> = k} <- Base.decode64(String.trim(b64)) do
      {:ok, k}
    else
      _ -> :error
    end
  end

  @doc "Seals `term` (JSON-encoded) under `key` for the row named by `aad`."
  def seal(key, aad, term) do
    pt = Jason.encode!(term)
    nonce = :crypto.strong_rand_bytes(12)
    {ct, tag} = :crypto.crypto_one_time_aead(:aes_256_gcm, key, nonce, pt, aad, true)
    nonce <> ct <> tag
  end

  @doc "Opens a sealed value: `{:ok, term}` or `:error` (wrong key, wrong row, tampered)."
  def open(key, aad, <<nonce::binary-size(12), rest::binary>>) when byte_size(rest) >= 16 do
    ct_len = byte_size(rest) - 16
    <<ct::binary-size(ct_len), tag::binary-size(16)>> = rest

    case :crypto.crypto_one_time_aead(:aes_256_gcm, key, nonce, ct, aad, tag, false) do
      :error -> :error
      pt -> Jason.decode(pt)
    end
  end

  def open(_key, _aad, _sealed), do: :error

  @doc """
  The AAD of one sealed column of one row: `"<table>:<row id>:<column>"` (Risi-derived text at
  rest: risi_commitments, risi_facts, the learning log's output and feedback reason).
  """
  def aad(table, id, column), do: "#{table}:#{id}:#{column}"

  @doc "Seals a text column under the data key; nil stays nil. `{:ok, sealed | nil}` or `:error`."
  def seal_text(_table, _id, _column, nil), do: {:ok, nil}

  def seal_text(table, id, column, text) when is_binary(text) do
    with {:ok, key} <- data(), do: {:ok, seal(key, aad(table, id, column), text)}
  end

  @doc "Opens a text column sealed by `seal_text/4`; nil stays nil. `{:ok, text | nil}` or `:error`."
  def open_text(_table, _id, _column, nil), do: {:ok, nil}

  def open_text(table, id, column, sealed) do
    with {:ok, key} <- data(),
         {:ok, text} when is_binary(text) <- open(key, aad(table, id, column), sealed) do
      {:ok, text}
    else
      _ -> :error
    end
  end

  @doc "Applies `fun` (`{:ok, y}` or anything else) to each: `{:ok, ys}` or `:error` at the first failure."
  def open_each(list, fun) do
    Enum.reduce_while(list, {:ok, []}, fn x, {:ok, acc} ->
      case fun.(x) do
        {:ok, y} -> {:cont, {:ok, [y | acc]}}
        _ -> {:halt, :error}
      end
    end)
    |> case do
      {:ok, acc} -> {:ok, Enum.reverse(acc)}
      :error -> :error
    end
  end
end
