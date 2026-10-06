defmodule RisiMe.Workers.ChatClear do
  @moduledoc """
  `chat:clear` (contract v1.12 §15.9): removes from the caller's own inbox partition the
  `message`, `reaction`, `status`, `group_receipt` and `delete` events of one conversation at or
  before `upto` (by TimeUUID time), streaming pages of 500 and deleting each page in one
  single-partition unlogged batch. MLS and group lifecycle events always stay. Unique on
  `(user, conversation_id, upto)`. Args are ids only.
  """
  use Oban.Worker,
    queue: :messaging,
    max_attempts: 5,
    unique: [keys: [:user_id, :conversation_id, :upto], period: 86_400]

  require Logger

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"user_id" => u, "conversation_id" => conv, "upto" => upto}}) do
    n = RisiMe.Messaging.Deletes.run_clear(u, conv, upto)
    Logger.info("chat:clear removed #{n} event(s)")
    :ok
  end
end
