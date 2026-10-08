defmodule RisiMe.Repo.Migrations.BlobsAvatar do
  use Ecto.Migration

  # Contract v1.17 §18.3 (server S2): `avatar` blobs belong to no conversation, so
  # `blobs.conversation_id` becomes nullable (null only for `avatar`). The existing
  # `(owner, purpose)` index finds a user's current avatar. Additive.
  def change do
    alter table(:blobs) do
      modify :conversation_id, :string, null: true, from: {:string, null: false}
    end
  end
end
