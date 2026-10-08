defmodule RisiMe.Agent.Send do
  @moduledoc """
  Sending as Risi (§24.11 "Risi → chat"): an ordinary §10.3 `type: "text"` envelope with the
  human-readable `body` and an optional structured `risi` object, encrypted by Risi's MLS client
  and sent through the normal `msg:send` path as Risi's device, so it is stored, delivered and
  pushed like any member message.

  Only into a conversation Risi may act in now (`RisiMe.Agent.may_act?/1`: Official, the chat
  on, Risi active); anything else is `{:error, :private_tab}` or `{:error, :not_member}`.
  """
  alias RisiMe.Agent.{Conversation, ConversationSup}

  @doc "`{:ok, %{message_id, conversation_id, server_ts}}` or `{:error, reason}`."
  def text(conv, body, risi \\ nil, timeout \\ 60_000) when is_binary(body) do
    envelope = %{"v" => 1, "type" => "text", "body" => body}
    envelope = if is_map(risi), do: Map.put(envelope, "risi", risi), else: envelope

    cond do
      not RisiMe.Agent.official?(conv) ->
        {:error, :private_tab}

      not RisiMe.Agent.may_act?(conv) ->
        {:error, :not_member}

      true ->
        with {:ok, pid} <- ConversationSup.ensure(conv),
             do: Conversation.send_envelope(pid, envelope, timeout)
    end
  end
end
