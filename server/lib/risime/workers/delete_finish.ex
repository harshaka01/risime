defmodule RisiMe.Workers.DeleteFinish do
  @moduledoc """
  Finishes a `msg:delete` `scope: "everyone"` (contract v1.12 §15.8 step 6, server R2/R5).
  Enqueued right after the `sent_dedupe` claim and scheduled about 15 s later (after the ack
  race window), so the delete completes even if the client never retries: it re-runs steps 3–5
  if the `delete` event's index row is missing, then removes plaintext reaction refs, the
  `group_receipts` partition, the receipts cache entry and the blobs. Idempotent.

  Args are the delete plan (`RisiMe.Messaging.Deletes.run/2`): ids, the event's routing data and
  the opaque MLS ciphertext of the control (never plaintext content).
  """
  use Oban.Worker,
    queue: :messaging,
    max_attempts: 10,
    unique: [keys: [:message_id], period: 86_400]

  @impl Oban.Worker
  def perform(%Oban.Job{args: plan}), do: RisiMe.Messaging.Deletes.run(plan)
end
