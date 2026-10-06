defmodule RisiMe.Workers.BlobCleanup do
  @moduledoc """
  Blob housekeeping (contract v1.9 §12.6, v1.11 §14.8):

    * hourly: the expiry sweep (rows with `expires_at <= now` in batches, then their files) and
      temp files older than an hour;
    * weekly (`%{"pass" => "orphans"}`): files without a live row.
  """
  use Oban.Worker, queue: :maintenance, max_attempts: 3, unique: [period: 1800]

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"pass" => "orphans"}}), do: {:ok, RisiMe.Blobs.orphans()}
  def perform(%Oban.Job{}), do: {:ok, RisiMe.Blobs.cleanup()}
end
