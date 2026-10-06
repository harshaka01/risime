defmodule RisiMe.Repo.Migrations.AddBlobsOwnerPurposeIndex do
  use Ecto.Migration

  # Contract v1.10 §13.4: the per-user `mls` quota sums live blobs by (owner, purpose).
  def change do
    create_if_not_exists index(:blobs, [:owner, :purpose])
  end
end
