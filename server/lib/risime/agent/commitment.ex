defmodule RisiMe.Agent.Commitment do
  @moduledoc """
  A commitment Risi detected in an Official conversation (§24.11, decision 066). `proposed`
  until the owner or a counterpart confirms it (✓); nothing is tracked before that, and a
  proposal nobody confirms is deleted after 48 h. Declined, cancelled and expired commitments
  are deleted, not kept. States kept: `proposed`, `confirmed`, `edited`, `done`.
  """
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: false}
  @timestamps_opts [type: :utc_datetime_usec]

  schema "risi_commitments" do
    field :conversation_id, :string
    field :chat_id, :string
    field :state, :string
    field :text, :string
    field :owner_id, :binary_id
    field :counterpart_ids, {:array, :binary_id}, default: []
    field :due, :utc_datetime_usec
    field :due_kind, :string
    field :due_text, :string
    field :source_message_ids, {:array, :string}, default: []
    field :confidence, :float
    field :call_ref, :binary_id
    field :card_message_id, :string
    field :by, :binary_id
    field :schedule_v, :integer, default: 0
    field :proposed_at, :utc_datetime_usec
    field :confirmed_at, :utc_datetime_usec
    timestamps()
  end

  @open ~w(confirmed edited)

  @doc "The tracked, not yet done states (`open` in §24.11)."
  def open_states, do: @open

  def open?(%__MODULE__{state: s}), do: s in @open
end

defmodule RisiMe.Agent.Fact do
  @moduledoc """
  A derived fact about one user (§24.12): only derived lines, never raw chat text. Stage 1
  writes facts only for confirmed commitments (nothing is learned without ✓).
  """
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: false}
  @timestamps_opts [type: :utc_datetime_usec, updated_at: false]

  schema "risi_facts" do
    field :subject_user_id, :binary_id
    field :chat_id, :string
    field :conversation_id, :string
    field :kind, :string
    field :text, :string
    field :source_message_ids, {:array, :string}, default: []
    field :commitment_id, :binary_id
    timestamps()
  end
end
