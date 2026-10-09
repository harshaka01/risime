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

    with :ok <- RateLimiter.hit_if_allowed(:risi_send, :risi, limit, @window) do
      sender().text(conv, body, risi(risi, opts))
    end
  end

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
