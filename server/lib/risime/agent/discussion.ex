defmodule RisiMe.Agent.Discussion do
  @moduledoc """
  One discussion summary (contract v1.27 §27.9 `risi_discussions`): where and when, who took
  part, who received a copy, and the derived key points and one-line summary.

  `key_points` and `summary` are chat-derived, so they are **sealed at rest** under
  `RISI_DATA_KEY` (AAD `risi_discussions:<summary_id>:<column>`, `RisiMe.Agent.Seal`); there is
  no plaintext column. `made_by` holds model names only.
  """
  use Ecto.Schema

  alias RisiMe.Agent.Seal

  @primary_key {:summary_id, :binary_id, autogenerate: false}
  @table "risi_discussions"

  schema "risi_discussions" do
    field :conversation_id, :string
    field :chat_id, :string
    field :source, :string
    field :call_id, :binary_id
    field :media, :string
    field :duration_s, :integer
    field :started_at, :utc_datetime_usec
    field :ended_at, :utc_datetime_usec
    field :participants, {:array, :binary_id}, default: []
    field :recipients, {:array, :binary_id}, default: []
    field :key_points, {:array, :string}, virtual: true
    field :key_points_sealed, :binary
    field :summary, :string, virtual: true
    field :summary_sealed, :binary
    field :items_count, :integer, default: 0
    field :call_ref, :binary_id
    field :made_by, :map
    field :created_at, :utc_datetime_usec
  end

  @doc "Seals `key_points` and `summary`: `{:ok, d}` or `:error` (no key)."
  def seal(%__MODULE__{summary_id: id} = d) do
    with {:ok, key} <- Seal.data() do
      {:ok,
       %{
         d
         | key_points_sealed: Seal.seal(key, Seal.aad(@table, id, "key_points"), d.key_points),
           summary_sealed: Seal.seal(key, Seal.aad(@table, id, "summary"), d.summary)
       }}
    end
  end

  @doc "Fills `key_points` and `summary`: `{:ok, d}` or `:error` (no/wrong key)."
  def open(%__MODULE__{summary_id: id} = d) do
    with {:ok, key} <- Seal.data(),
         {:ok, kp} when is_list(kp) <-
           Seal.open(key, Seal.aad(@table, id, "key_points"), d.key_points_sealed),
         {:ok, s} when is_binary(s) <-
           Seal.open(key, Seal.aad(@table, id, "summary"), d.summary_sealed) do
      {:ok, %{d | key_points: kp, summary: s}}
    else
      _ -> :error
    end
  end
end
