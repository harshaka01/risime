defmodule RisiMe.Messaging.Store do
  @moduledoc """
  Storage boundary for messages and per-user inbox events. `RisiMe.Messaging` is the only
  caller; `RisiMe.Messaging.Store.Cassandra` is the only implementation that touches Cassandra.

  Ids are lowercase UUID strings. `message_id` and `event_id` are TimeUUIDs.
  """

  @type uuid :: String.t()
  @type sent :: %{message_id: uuid, conversation_id: String.t(), server_ts: DateTime.t()}
  @type message :: %{
          message_id: uuid,
          sender_id: uuid,
          recipient_id: uuid | nil,
          client_msg_id: uuid,
          conversation_id: String.t(),
          status: String.t(),
          kind: String.t() | nil,
          recipients: [uuid] | nil
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

  @doc "Events after `since` (exclusive, or from the start when nil), oldest first."
  @callback list_events(user_id :: uuid, since :: uuid | nil, limit :: pos_integer) :: [event]

  @doc "Records a member's delivered and/or read time of a group message (v1.9 §12.7)."
  @callback put_group_receipt(
              message_id :: uuid,
              user_id :: uuid,
              delivered_at :: DateTime.t() | nil,
              read_at :: DateTime.t() | nil
            ) :: :ok

  @doc "Every stored receipt of a group message."
  @callback list_group_receipts(message_id :: uuid) :: [receipt]

  @doc "Cheap liveness check of the backing store (used by `GET /health`)."
  @callback health() :: :ok | {:error, term}

  def impl, do: Application.get_env(:risime, :message_store, RisiMe.Messaging.Store.Cassandra)
end
