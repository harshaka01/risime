defmodule RisiMe.Workers.BlobCleanup do
  @moduledoc "Hourly: deletes blobs past their 30-day TTL (contract v1.9 §12.6), rows and files."
  use Oban.Worker, queue: :maintenance, max_attempts: 3, unique: [period: 1800]

  @impl Oban.Worker
  def perform(%Oban.Job{}), do: {:ok, RisiMe.Blobs.cleanup()}
end
