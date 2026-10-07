defmodule RisiMe.Messaging.Store do
  @moduledoc """
  Storage boundary for messages and per-user inbox events. `RisiMe.Messaging` is the only
  caller; `RisiMe.Messaging.Store.Cassandra` is the only implementation that touches Cassandra.

  Ids are lowercase UUID strings. `message_id` and `event_id` are TimeUUIDs.
  """

  @type uuid :: String.t()
  @typedoc "`kind` is nil for a `msg:send` claim and `\"delete\"` for a `msg:delete` one (v1.12)."
  @type sent :: %{
          required(:message_id) => uuid,
          required(:conversation_id) => String.t(),
          required(:server_ts) => DateTime.t(),
          optional(:kind) => String.t() | nil
        }
  @typedoc """
  A `message_index` row. `kind`: nil (a message), `"reaction"` or `"delete"` (v1.12).
  Reads also return `deleted_at`/`deleted_by` (the v1.12 index tombstone; set = deleted) and
  `ttl`, the row's remaining TTL in seconds.
  """
  @type message :: %{
          required(:message_id) => uuid,
          required(:sender_id) => uuid,
          required(:recipient_id) => uuid | nil,
          required(:client_msg_id) => uuid,
          required(:conversation_id) => String.t(),
          required(:status) => String.t(),
          optional(:kind) => String.t() | nil,
          optional(:recipients) => [uuid] | nil,
          optional(:deleted_at) => DateTime.t() | nil,
          optional(:deleted_by) => uuid | nil,
          optional(:ttl) => non_neg_integer | nil
        }
  @type event :: %{event_id: uuid, kind: String.t(), data: map}
  @type receipt :: %{
          user_id: uuid,
          delivered_at: DateTime.t() | nil,
          read_at: DateTime.t() | nil
        }

  @doc "The reply recorded for (sender, client_msg_id), if any."
  @callback get_sent(sender_id :: uuid, client_msg_id :: uuid) :: {:ok, sent} | :not_found

  @doc "Records `sent` for (sender, client_msg_id) unless already present (24 h window)."
  @callback claim_send(sender_id :: uuid, client_msg_id :: uuid, sent) :: :ok | {:exists, sent}

  @doc """
  Indexes a message. A DM has `recipient_id`; a group message (v1.9) has `recipient_id: nil` and
  `recipients`, the members at send time (the sender excluded).
  """
  @callback put_message(message) :: :ok
  @callback get_message(message_id :: uuid) :: {:ok, message} | :not_found

  @doc "Sets the status only if it is currently `expected`."
  @callback compare_and_set_status(message_id :: uuid, expected :: String.t(), new :: String.t()) ::
              :ok | {:conflict, current :: String.t()}

  @callback append_event(user_id :: uuid, event) :: :ok

  @doc """
  v1.15 §17.11: `append_event/2` with options; `ttl: seconds` writes the row `USING TTL` (the
  history event kinds live 48 h).
  """
  @callback append_event(user_id :: uuid, event, opts :: keyword) :: :ok
  @optional_callbacks append_event: 3

  @doc "Appends the same event to several inboxes in one request (v1.10 §13.1 sender copies)."
  @callback append_event_to_all(user_ids :: [uuid], event) :: :ok

  @doc """
  Events after `since` (exclusive, or from the start when nil), oldest first. With
  `include_calls?` (v1.13 §16.3, a `calls` socket) the user's `call_signal` rows are read with
  the same bound and limit and merged in by `event_id` (TimeUUID order); at most `limit` events.
  """
  @callback list_events(
              user_id :: uuid,
              since :: uuid | nil,
              limit :: pos_integer,
              include_calls? :: boolean
            ) :: [event]

  @doc """
  v1.13 §16.3: appends a `call_signal` event to the short-lived call-signal store of each user
  (never `inbox_events`), with the same `event_id`. A ring (`data["ring"] == true`) lives 60 s,
  any other signal 120 s.
  """
  @callback append_call_signal(user_ids :: [uuid], event) :: :ok

  @doc "Records a member's delivered and/or read time of a group message (v1.9 §12.7)."
  @callback put_group_receipt(
              message_id :: uuid,
              user_id :: uuid,
              delivered_at :: DateTime.t() | nil,
              read_at :: DateTime.t() | nil
            ) :: :ok

  @doc "Every stored receipt of a group message."
  @callback list_group_receipts(message_id :: uuid) :: [receipt]

  ## v1.12 deletes (§15.8)

  @doc "Q3d: the index tombstone, written with the row's remaining TTL (seconds)."
  @callback tombstone_message(
              message_id :: uuid,
              deleted_by :: uuid,
              deleted_at :: DateTime.t(),
              ttl :: pos_integer
            ) :: :ok

  @doc "One inbox event's kind and conversation (the column, else the payload's), by point read."
  @callback get_event(user_id :: uuid, event_id :: uuid) ::
              {:ok, %{event_id: uuid, kind: String.t(), conversation_id: String.t() | nil}}
              | :not_found

  @doc "Q1d: deletes events of one inbox partition (one unlogged batch)."
  @callback delete_events(user_id :: uuid, event_ids :: [uuid]) :: :ok

  @doc "Q8: records that `event_id` in `user_id`'s inbox (plaintext reaction `ref_id`) references `message_id`."
  @callback put_message_ref(message_id :: uuid, user_id :: uuid, event_id :: uuid, ref_id :: uuid) ::
              :ok

  @doc "Q7: the refs of a message, optionally of one inbox partition."
  @callback list_message_refs(message_id :: uuid, user_id :: uuid | nil) :: [
              %{user_id: uuid, event_id: uuid, ref_id: uuid | nil}
            ]

  @doc "Deletes a message's refs partition (or one user's rows of it)."
  @callback delete_message_refs(message_id :: uuid, user_id :: uuid | nil) :: :ok

  @doc "Q5d: deletes a group message's receipts partition."
  @callback delete_group_receipts(message_id :: uuid) :: :ok

  @doc """
  `chat:clear` (§15.9): deletes from `user_id`'s partition every event of `kinds` whose
  conversation is `conversation_id` and whose TimeUUID time is at or before `upto`'s, paged,
  plus that user's refs rows of the deleted messages. Returns the number of events deleted.
  """
  @callback clear_conversation(
              user_id :: uuid,
              conversation_id :: String.t(),
              upto :: uuid,
              kinds :: [String.t()]
            ) :: {:ok, non_neg_integer}

  @typedoc "What `backfill_sender_copies/2` found and did (counts only, never content)."
  @type backfill_counts :: %{
          scanned: non_neg_integer,
          candidates: non_neg_integer,
          skipped_missing_user: non_neg_integer,
          skipped_ttl: non_neg_integer,
          existing: non_neg_integer,
          copied: non_neg_integer,
          dry_run: boolean
        }

  @doc """
  v1.10 §13.5 one-off backfill: copies each plaintext DM `message` event from the recipient's
  inbox into the sender's, with the same `event_id` and payload, the source's remaining TTL and
  original write time, when `keep?.(sender_id, recipient_id)` is true. Rows with under 60 s
  left and copies that already exist are skipped, so it is idempotent. With `dry_run: true`
  nothing is written and `copied` counts what would be. Implementation options (e.g. `:conn`)
  pass through `opts`.
  """
  @callback backfill_sender_copies(
              keep? :: (sender_id :: uuid, recipient_id :: uuid -> boolean),
              opts :: keyword
            ) :: {:ok, backfill_counts}

  @doc "Cheap liveness check of the backing store (used by `GET /health`)."
  @callback health() :: :ok | {:error, term}

  def impl, do: Application.get_env(:risime, :message_store, RisiMe.Messaging.Store.Cassandra)
end
