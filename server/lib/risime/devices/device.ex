defmodule RisiMe.Devices.Device do
  @moduledoc "An app install with its push token."
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id
  schema "devices" do
    belongs_to :user, RisiMe.Accounts.User
    field :device_id, :binary_id
    field :platform, :string
    field :push_token, :string
    field :app_version, :string
    field :user_token_id, :binary_id
    field :last_seen_at, :utc_datetime_usec
    field :mls_signature_key, :binary
    field :mls_attestation, :string
    field :mls_attested_at, :utc_datetime_usec

    timestamps(type: :utc_datetime_usec)
  end

  # The push token is a credential for waking this install: keep it out of inspect output.
  defimpl Inspect do
    def inspect(d, _opts),
      do: "#RisiMe.Devices.Device<device_id: #{d.device_id}, platform: #{d.platform}>"
  end
end
