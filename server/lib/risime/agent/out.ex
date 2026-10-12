defmodule RisiMe.Agent.Out do
  @moduledoc """
  Everything Risi posts goes through here (§24.11 "Risi → chat", §24.13):

    * the `risi` object always carries `v: 1`, `call_ref` (null unless a model call is behind the
      message), `notify` and (v1.27 §27.1) `made_by`: built from the learning-log row of
      `call_ref` (`RisiMe.Agent.MadeBy`; `model: null` for a rule message) unless the caller
      gives its own; `opts[:also_refs]` (earlier calls of the same turn) and `opts[:also]`
      (extra entries) fill its `also`;
    * **Risi's own send limit:** at most 15 posts per 10 s over all chats, below the per-user
      `msg_send` limit of 20 per 10 s that `RisiMe.Messaging.send/3` applies to Risi's user as
      to anyone; over it, `{:error, :rate_limited}` and the caller's job snoozes;
    * the sender is `RisiMe.Agent.Send` (Risi's MLS client; only where `may_act?/1`); tests set
      `config :risime, :risi_sender` to a module with the same `text/3`.
  """
  alias RisiMe.Agent.MadeBy
  alias RisiMe.RateLimiter

  @limit 15
  @window 10_000

  def sender, do: Application.get_env(:risime, :risi_sender, RisiMe.Agent.Send)

  @doc """
  True when Risi can post at all: `RISI=on` and its tree running (or a test sender).
  """
  def ready? do
    RisiMe.Risi.enabled?() and
      (sender() != RisiMe.Agent.Send or RisiMe.Agent.running?())
  end

  @doc "Posts `body` with the `risi` object. `{:ok, %{message_id, …}}` or `{:error, reason}`."
  def post(conv, body, risi, opts \\ []) when is_binary(body) and is_map(risi) do
    limit = Application.get_env(:risime, :risi_send_limit, @limit)

    with :ok <- RateLimiter.hit_if_allowed(:risi_send, :risi, limit, @window),
         {:ok, sent} <- sender().text(conv, body, risi(risi, opts)) do
      # v1.39 §36.8: a line of a speaker who opted out of memory is posted but not remembered.
      if Keyword.get(opts, :memory, true), do: remember(conv, sent, body, risi)
      {:ok, sent}
    end
  end

  @doc """
  v1.39 §36.5: deletes Risi's own posts `ids` in `conv` for everyone (§15 sender delete) and
  what Risi derived from them. `:ok` or `{:error, reason}`.
  """
  def delete(_conv, []), do: :ok

  def delete(conv, ids) when is_list(ids) do
    ids
    |> Enum.chunk_every(100)
    |> Enum.reduce_while(:ok, fn batch, :ok ->
      case sender().delete(conv, batch) do
        {:ok, _} ->
          RisiMe.Agent.Secretary.message_deleted(conv, batch)
          {:cont, :ok}

        {:error, _} = e ->
          {:halt, e}
      end
    end)
  end

  # P0 2026-10-09: in a Risi chat, Risi's own post joins the sealed 24-h buffer (as
  # `risi_post`, never a `text`), so the next turn sees both sides of the conversation.
  defp remember(conv, %{message_id: mid}, body, risi) when is_binary(mid) do
    # v1.36 §35.1: Risi's own posts in Official chats join its long-term memory.
    RisiMe.Agent.Memory.put_risi_post(conv, mid, body, risi)

    if RisiMe.Groups.Tabs.risi_chat?(conv) do
      env = %{"v" => 1, "type" => "risi_post", "kind" => risi["kind"], "body" => body}

      RisiMe.Agent.Transcript.put(conv, %{
        message_id: mid,
        sender_id: RisiMe.Risi.user_id(),
        sender_device: nil,
        plaintext: Jason.encode!(env)
      })
    end

    :ok
  rescue
    e ->
      require Logger
      Logger.warning("Risi post not buffered: #{inspect(e.__struct__)}")
      :ok
  end

  defp remember(_conv, _sent, _body, _risi), do: :ok

  @doc "The complete `risi` object of a post (defaults and `made_by` filled in)."
  def risi(risi, opts \\ []) do
    risi = Map.merge(%{"v" => 1, "call_ref" => nil, "notify" => []}, risi)

    Map.put_new_lazy(risi, "made_by", fn ->
      if MadeBy.rule_kind?(risi["kind"]),
        do: MadeBy.rule(),
        else: MadeBy.build(risi["call_ref"], opts[:also_refs] || [], opts[:also] || [])
    end)
  end
end
