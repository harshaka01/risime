defmodule RisiMe.History.Request do
  @moduledoc "A history request (contract v1.15 §17.11 `history_requests`)."
  use Ecto.Schema

  @primary_key {:request_id, :binary_id, autogenerate: false}
  schema "history_requests" do
    field :requester_user, :binary_id
    field :requester_device, :binary_id
    field :conversation_id, :string
    field :generation, :integer
    field :range_from, :utc_datetime_usec
    field :range_to, :utc_datetime_usec
    field :gap_count, :integer
    field :sources, :string
    field :state, :string
    field :phase, :string
    field :provider_user, :binary_id
    field :provider_device, :binary_id
    field :accepted_at, :utc_datetime_usec
    field :last_part_at, :utc_datetime_usec
    field :parts, :integer
    field :parts_delivered, {:array, :integer}, default: []
    field :parts_acked, {:array, :integer}, default: []
    field :request_ciphertext, :binary
    field :request_epoch, :integer
    field :created_at, :utc_datetime_usec
    field :expires_at, :utc_datetime_usec
    field :closed_at, :utc_datetime_usec
  end
end

defmodule RisiMe.History.Candidate do
  @moduledoc """
  A candidate provider device of a request (§17.11 `history_candidates`, normalised).

  `wait`: nil once named; `"join"` for a dormant own device (named when its inbox joins);
  `"refresh"` while the request ciphertext is too old for it (named after `history:refresh`).
  `answer`: nil (open), `accept`, `decline`, `unable` (`no_data`), or the server's own
  `elsewhere` (another device accepted), `expired` (the member window or the delivery deadline
  ran out) and `dropped` (un-named: no longer eligible).
  """
  use Ecto.Schema

  @primary_key false
  schema "history_candidates" do
    field :request_id, :binary_id, primary_key: true
    field :device_id, :binary_id, primary_key: true
    field :user_id, :binary_id
    field :phase, :string
    field :wait, :string
    field :named_at, :utc_datetime_usec
    field :named_until, :utc_datetime_usec
    field :answer, :string
    field :answered_at, :utc_datetime_usec
  end
end
