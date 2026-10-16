defmodule RisiRegistry.Registry.OwnerKey do
  @moduledoc """
  A public signing key an owner publishes at `https://<domain>/.well-known/risi-keys.json`.
  Several keys may be listed at once, each with `kid` and `expires` (review change 2), so an
  owner rotates by adding the new key, re-signing the card, then dropping the old key.
  """
  use Ecto.Schema

  schema "owner_keys" do
    field :domain, :string
    field :kid, :string
    field :alg, :string
    field :jwk, :map
    field :expires_at, :utc_datetime_usec
    field :retired_at, :utc_datetime_usec
    timestamps(type: :utc_datetime_usec)
  end

  @doc "True when the key may verify a signature at `now`."
  def usable?(%__MODULE__{retired_at: nil, expires_at: nil}, _now), do: true

  def usable?(%__MODULE__{retired_at: nil, expires_at: exp}, now),
    do: DateTime.compare(exp, now) == :gt

  def usable?(_key, _now), do: false
end
