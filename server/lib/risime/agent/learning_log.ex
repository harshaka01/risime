defmodule RisiMe.Agent.LearningLog do
  @moduledoc """
  Risi's learning log (CLAUDE.md, §24.12, decision 066), behind a behaviour like
  `RisiMe.Messaging.Store`: every model call is recorded, success or not.

  An entry holds the call's `call_ref` (= `call_id`, a TimeUUID), task, model alias and real
  model, provider, fallback outcome, latency, tokens, cost, confidence, status and the derived
  output (the validated JSON, sealed at rest under `RISI_DATA_KEY`). **The input is only `source_message_ids` plus `prompt_sha256`;
  the raw text of a prompt is never written.** Rows live 90 days (TTL). Feedback (§24.11, private
  REST) is one row per call and user; a repeat replaces it.

  The Cassandra implementation is `RisiMe.Agent.LearningLog.Cassandra` (`priv/cql/007_…`).
  """

  @type entry :: %{
          required(:call_id) => String.t(),
          required(:conversation_id) => String.t(),
          required(:chat_id) => String.t(),
          required(:task) => String.t(),
          required(:model_alias) => String.t(),
          required(:provider) => String.t(),
          required(:status) => String.t(),
          optional(atom) => term
        }

  @callback record(entry) :: :ok
  @callback get(call_ref :: String.t()) :: {:ok, map} | :not_found
  @callback list_by_chat(chat_id :: String.t(), month :: String.t(), limit :: pos_integer) :: [
              map
            ]
  @callback list_by_day(day :: Date.t(), bucket :: non_neg_integer) :: [map]
  @callback put_feedback(
              call_ref :: String.t(),
              user_id :: String.t(),
              rating :: String.t(),
              reason :: String.t() | nil
            ) :: :ok
  @callback list_feedback(call_ref :: String.t()) :: [map]

  @buckets 8
  @ttl_s 90 * 86_400

  def impl,
    do: Application.get_env(:risime, :risi_learning_log, RisiMe.Agent.LearningLog.Cassandra)

  @doc "The number of `bucket`s per day partition."
  def buckets, do: @buckets

  @doc "The rows' lifetime in seconds (90 days)."
  def ttl_s, do: @ttl_s

  @doc "The day partition of a call: the UTC date of its TimeUUID."
  def day(call_id), do: call_id |> RisiMe.TimeUUID.to_datetime() |> DateTime.to_date()

  @doc "The bucket of a call (stable, from its id)."
  def bucket(call_id), do: :erlang.phash2(call_id, @buckets)

  @doc "The month partition of the by-chat table (`YYYY-MM`)."
  def month(call_id), do: call_id |> day() |> Calendar.strftime("%Y-%m")

  def record(entry), do: impl().record(entry)
  def get(call_ref), do: impl().get(call_ref)
  def list_by_chat(chat_id, month, limit \\ 100), do: impl().list_by_chat(chat_id, month, limit)
  def list_by_day(day, bucket), do: impl().list_by_day(day, bucket)

  @doc "Feedback on a call (`up`/`down`, an optional reason of at most 500 characters)."
  def put_feedback(call_ref, user_id, rating, reason) when rating in ["up", "down"],
    do: impl().put_feedback(call_ref, user_id, rating, reason && String.slice(reason, 0, 500))

  def list_feedback(call_ref), do: impl().list_feedback(call_ref)
end
