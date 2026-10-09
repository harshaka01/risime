defmodule RisiMe.Agent.Commitment do
  @moduledoc """
  A commitment Risi detected in an Official conversation (§24.11, decision 066). `proposed`
  until the owner or a counterpart confirms it (✓); nothing is tracked before that, and a
  proposal nobody confirms is deleted after 48 h. Declined, cancelled and expired commitments
  are deleted, not kept. States kept: `proposed`, `confirmed`, `edited`, `done`.

  `text` and `due_text` are chat-derived, so they are **sealed at rest** (AES-256-GCM under
  `RISI_DATA_KEY`, AAD `risi_commitments:<id>:<column>`, `RisiMe.Agent.Seal`) in `text_sealed`
  and `due_text_sealed`; the plaintext columns are always NULL (a CHECK constraint). `text` and
  `due_text` here are virtual: `seal/1` before an insert, `open/1` after a read. Without the key
  (or with a wrong one) both return `:error` and the feature using them is unavailable.
  """
  use Ecto.Schema

  alias RisiMe.Agent.Seal

  @primary_key {:id, :binary_id, autogenerate: false}
  @timestamps_opts [type: :utc_datetime_usec]

  schema "risi_commitments" do
    field :conversation_id, :string
    field :chat_id, :string
    field :state, :string
    field :text, :string, virtual: true
    field :text_sealed, :binary
    field :owner_id, :binary_id
    field :counterpart_ids, {:array, :binary_id}, default: []
    field :due, :utc_datetime_usec
    field :due_kind, :string
    field :due_text, :string, virtual: true
    field :due_text_sealed, :binary
    field :source_message_ids, {:array, :string}, default: []
    field :confidence, :float
    field :call_ref, :binary_id
    field :card_message_id, :string
    field :by, :binary_id
    field :schedule_v, :integer, default: 0
    field :proposed_at, :utc_datetime_usec
    field :confirmed_at, :utc_datetime_usec
    # v1.27 §27.9: a ledger item (commitment_id = item_id) has an `item_state`; a v1.24 card
    # (also one from a summary's owner fallback) has none.
    field :summary_id, :binary_id
    field :all_day, :boolean, default: false
    field :source, :string
    field :item_state, :string
    field :overdue_sent_at, :utc_datetime_usec
    field :nudged_at, :utc_datetime_usec
    # Item 10 (2026-10-09): no usable due ("sometime", "soon", none): one clarification
    # question instead of a calendar offer (`RisiMe.Agent.Offers`); cleared when a due is set.
    field :needs_clarification, :boolean, default: false
    timestamps()
  end

  @open ~w(confirmed edited)
  @table "risi_commitments"

  @doc "The tracked, not yet done states (`open` in §24.11)."
  def open_states, do: @open

  def open?(%__MODULE__{state: s}), do: s in @open

  @doc "True for a v1.27 ledger item (owner-confirmed in the Risi chat, §27.5)."
  def ledger?(%__MODULE__{item_state: s}), do: s != nil

  @doc "Seals `text` and `due_text` into their columns: `{:ok, c}` or `:error` (no key)."
  def seal(%__MODULE__{id: id, text: text} = c) when is_binary(text) do
    with {:ok, kw} <- sealed_changes(id, text, c.due_text), do: {:ok, struct!(c, kw)}
  end

  @doc "The changes for a new `text`/`due_text` (an edit), sealed: `{:ok, keyword}` or `:error`."
  def sealed_changes(id, text, due_text) do
    with {:ok, t} <- Seal.seal_text(@table, id, "text", text),
         {:ok, d} <- Seal.seal_text(@table, id, "due_text", due_text) do
      {:ok, [text: text, text_sealed: t, due_text: due_text, due_text_sealed: d]}
    end
  end

  @doc "Fills `text`/`due_text` from the sealed columns: `{:ok, c}` or `:error` (no/wrong key)."
  def open(%__MODULE__{id: id} = c) do
    with {:ok, t} when is_binary(t) <- Seal.open_text(@table, id, "text", c.text_sealed),
         {:ok, d} <- Seal.open_text(@table, id, "due_text", c.due_text_sealed) do
      {:ok, %{c | text: t, due_text: d}}
    else
      _ -> :error
    end
  end

  @doc "`open/1` over a list: `{:ok, list}` or `:error` when any row can't be opened."
  def open_all(list), do: Seal.open_each(list, &open/1)
end

defmodule RisiMe.Agent.Fact do
  @moduledoc """
  A derived fact about one user (§24.12): only derived lines, never raw chat text. Stage 1
  writes facts only for confirmed commitments (nothing is learned without ✓).

  `text` is sealed at rest like a commitment's (`text_sealed`, AAD `risi_facts:<id>:text`); the
  plaintext column is always NULL. `seal/1` before an insert, `open/1` after a read.
  """
  use Ecto.Schema

  alias RisiMe.Agent.Seal

  @primary_key {:id, :binary_id, autogenerate: false}
  @timestamps_opts [type: :utc_datetime_usec, updated_at: false]

  schema "risi_facts" do
    field :subject_user_id, :binary_id
    field :chat_id, :string
    field :conversation_id, :string
    field :kind, :string
    field :text, :string, virtual: true
    field :text_sealed, :binary
    field :source_message_ids, {:array, :string}, default: []
    field :commitment_id, :binary_id
    timestamps()
  end

  @doc "Seals `text` into `text_sealed`: `{:ok, f}` or `:error` (no key)."
  def seal(%__MODULE__{id: id, text: text} = f) when is_binary(text) do
    with {:ok, s} <- Seal.seal_text("risi_facts", id, "text", text),
         do: {:ok, %{f | text_sealed: s}}
  end

  @doc "Fills `text` from `text_sealed`: `{:ok, f}` or `:error` (no/wrong key)."
  def open(%__MODULE__{id: id} = f) do
    case Seal.open_text("risi_facts", id, "text", f.text_sealed) do
      {:ok, t} when is_binary(t) -> {:ok, %{f | text: t}}
      _ -> :error
    end
  end

  @doc "`open/1` over a list: `{:ok, list}` or `:error` when any row can't be opened."
  def open_all(list), do: Seal.open_each(list, &open/1)
end
