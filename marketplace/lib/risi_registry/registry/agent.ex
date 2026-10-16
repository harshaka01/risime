defmodule RisiRegistry.Registry.Agent do
  @moduledoc """
  The registration record (agent-connector.md section 2).

  `status` is the switch people flip (proposed, active, suspended, delisted). `verification` is
  what the card check found: a card whose content changed since approval drops to `unverified`
  and no call is made until a person approves it again.
  """
  use Ecto.Schema
  import Ecto.Changeset

  @statuses ~w(proposed active suspended delisted)
  @tiers ~w(official verified listed)
  @data_classes ~w(none own_figures contacts messages_passed_in_call)

  def statuses, do: @statuses
  def tiers, do: @tiers
  def data_classes, do: @data_classes

  @derive {Jason.Encoder,
           only: [
             :agent_id,
             :name,
             :description,
             :owner,
             :domain,
             :card_url,
             :card_hash,
             :key_id,
             :tier,
             :skills,
             :scopes,
             :data_classes,
             :limits,
             :status,
             :verification,
             :consequential,
             :tests
           ]}
  schema "agents" do
    field :agent_id, :string
    field :name, :string
    field :description, :string, default: ""
    field :owner, :map, default: %{}
    field :domain, :string
    field :card_url, :string
    field :card_hash, :string
    field :key_id, :string
    field :tier, :string
    field :skills, {:array, :map}, default: []
    field :scopes, {:array, :map}, default: []
    field :data_classes, {:array, :string}, default: []
    field :limits, :map, default: %{}
    field :status, :string, default: "proposed"
    field :verification, :string, default: "unverified"
    field :consequential, :boolean, default: false
    field :tests, :map
    timestamps(type: :utc_datetime_usec)
  end

  @required ~w(agent_id name domain card_url tier)a
  @optional ~w(description owner card_hash key_id skills scopes data_classes limits consequential tests)a

  @doc "Changeset for a new registration. Status always starts at `proposed`."
  def register_changeset(agent \\ %__MODULE__{}, attrs) do
    agent
    |> cast(attrs, @required ++ @optional)
    |> validate_required(@required)
    |> put_change(:status, "proposed")
    |> put_change(:verification, "unverified")
    |> validate_common()
  end

  def validate_common(changeset) do
    changeset
    |> validate_inclusion(:tier, @tiers)
    |> validate_inclusion(:status, @statuses)
    |> validate_subset(:data_classes, @data_classes,
      message: "has an unknown data class (private_chats is never allowed)"
    )
    |> validate_format(:agent_id, ~r{\A[a-z0-9.-]+/[a-z0-9][a-z0-9-]*\z})
    |> validate_agent_domain()
    |> validate_format(:card_url, ~r{\Ahttps://})
    |> validate_format(:card_hash, ~r/\A[0-9a-f]{64}\z/)
    |> unique_constraint(:agent_id)
    |> check_constraint(:data_classes, name: :no_private_chats)
  end

  # agent_id is `<owner-domain>/<slug>`, and the owner domain must be the record's domain.
  defp validate_agent_domain(changeset) do
    with id when is_binary(id) <- get_field(changeset, :agent_id),
         domain when is_binary(domain) <- get_field(changeset, :domain),
         [prefix, _slug] <- String.split(id, "/", parts: 2),
         false <- prefix == domain do
      add_error(changeset, :agent_id, "must start with the owner domain")
    else
      _ -> changeset
    end
  end
end
