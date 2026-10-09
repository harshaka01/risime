defmodule RisiMe.Agent.Audience do
  @moduledoc """
  The audience rule of a turn (contract v1.25 §25.1, normative; server S12). Every output of a
  turn goes to exactly one conversation:

    * built **only from the conversation's own content** (its messages, a summary of it, a
      reminder for its members and its confirm card, the `capabilities` list): **to that
      conversation**;
    * built from **any personal source** (the asker's calendar, notes, RisiWork, other
      conversations found by `search_chats`), every **draft** and every **personal confirm**
      (`calendar_add`, a "remind me" reminder): **to the asker's Risi chat**; the conversation the
      request came from gets one line only, an `answer` with `"I've replied in your Risi chat."`,
      no sources, no steps and no content from the personal source;
    * in the Risi chat itself everything goes to the Risi chat.

  An asker without an active Risi chat is never offered a personal tool (`Tools.authorize/3`);
  should personal content reach `deliver/4` anyway it is never posted in the group (a line
  saying so instead).
  """
  require Logger

  alias RisiMe.Agent.Out

  @pointer "I've replied in your Risi chat."
  @no_risi_chat "I can only share that in your Risi chat. Update RisiMe to get one."

  @doc "The pointer line posted in the group for a personal answer."
  def pointer, do: @pointer

  @doc """
  Where an output goes: `{:here, conv}`, `{:risi_chat, risi_chat_id}` (with a pointer in
  `conv`), or `:nowhere` (personal content and no active Risi chat).
  """
  def target(ctx, personal?) do
    cond do
      ctx.in_risi_chat? -> {:here, ctx.conv}
      not personal? -> {:here, ctx.conv}
      rc = RisiMe.RisiChat.active_id(ctx.asker) -> {:risi_chat, rc}
      true -> :nowhere
    end
  end

  @doc """
  Posts a turn's output (`body`, the `risi` map) by the rule. Drafts and personal confirms pass
  `personal? = true`. Oban result.
  """
  def deliver(ctx, body, risi, personal?) do
    case target(ctx, personal?) do
      {:here, conv} ->
        post(conv, body, risi)

      {:risi_chat, rc} ->
        with :ok <- post(rc, body, risi) do
          post(ctx.conv, @pointer, pointer_risi(risi))
        end

      :nowhere ->
        post(ctx.conv, @no_risi_chat, pointer_risi(risi) |> Map.put("answer", @no_risi_chat))
    end
  end

  # The one-line answer in the group: no sources, no steps, nothing from the personal source.
  defp pointer_risi(risi) do
    %{
      "kind" => "answer",
      "request_id" => risi["request_id"],
      "answer" => @pointer,
      "refs" => [],
      "confidence" => risi["confidence"],
      "sources" => [],
      "next_steps" => [],
      "turn_ref" => risi["turn_ref"],
      "call_ref" => risi["call_ref"],
      # v1.27 §27.1: the pointer line is fixed server text, made by no model.
      "made_by" => RisiMe.Agent.MadeBy.rule(),
      "notify" => risi["notify"]
    }
  end

  defp post(conv, body, risi) do
    case Out.post(conv, body, risi) do
      {:ok, _} -> :ok
      {:error, :rate_limited} -> {:snooze, 10}
      {:error, :not_member} -> :ok
      {:error, :private_tab} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end
end
