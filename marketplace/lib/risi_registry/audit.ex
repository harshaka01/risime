defmodule RisiRegistry.Audit do
  @moduledoc """
  The audit log (agent-connector.md sections 11 and 12).

  **No content and no figures.** Rows carry only who, when, which agent and skill, a result
  code, the time taken and a request id. Fields are whitelisted: anything else passed in (a
  question, an answer, a card body) is dropped before it reaches the database, and the table
  has no column that could hold it. `reason` is allowed only for actions a person takes
  (delist, suspend, approve), where it records why.
  """
  import Ecto.Query
  alias RisiRegistry.Repo

  defmodule Event do
    @moduledoc false
    use Ecto.Schema

    schema "audit_events" do
      field :at, :utc_datetime_usec
      field :action, :string
      field :actor, :string
      field :agent_id, :string
      field :skill, :string
      field :company_id, :string
      field :result_code, :string
      field :latency_ms, :integer
      field :request_id, :string
      field :reason, :string
    end
  end

  @call_fields ~w(actor agent_id skill company_id result_code latency_ms request_id)a
  @person_actions ~w(status_change card_approved key_rotated sandbox_suspended allow_list_added)
  @system_actions ~w(call card_unverified sandbox_run sign_in)
  @max_reason 200

  @doc """
  Writes one call line: who, agent, skill, company, result code, latency, request id.
  Any other key in `attrs` (for example `:answer` or `:question`) is ignored.
  """
  def call_line(attrs), do: write("call", Map.take(Map.new(attrs), @call_fields))

  @doc "Writes an event for `action`. Only whitelisted fields survive."
  def record(action, attrs) when action in @person_actions or action in @system_actions do
    attrs = Map.new(attrs)
    fields = Map.take(attrs, @call_fields)

    fields =
      if action in @person_actions and is_binary(attrs[:reason]),
        do: Map.put(fields, :reason, String.slice(attrs[:reason], 0, @max_reason)),
        else: fields

    write(action, fields)
  end

  defp write(action, fields) do
    fields =
      fields
      |> Map.update(:latency_ms, nil, &normalise_latency/1)
      |> Map.new(fn
        {:latency_ms, v} -> {:latency_ms, v}
        {k, v} when is_binary(v) or is_nil(v) -> {k, v}
        {k, v} -> {k, to_string(v)}
      end)

    %Event{}
    |> Ecto.Changeset.change(Map.merge(fields, %{action: action, at: DateTime.utc_now()}))
    |> Repo.insert!()
  end

  defp normalise_latency(nil), do: nil
  defp normalise_latency(ms) when is_integer(ms), do: ms
  defp normalise_latency(ms) when is_float(ms), do: round(ms)

  @doc "The most recent events, newest first (optionally for one agent)."
  def recent(opts \\ []) do
    limit = Keyword.get(opts, :limit, 50)

    Event
    |> then(fn q ->
      case Keyword.get(opts, :agent_id) do
        nil -> q
        id -> where(q, [e], e.agent_id == ^id)
      end
    end)
    |> then(fn q ->
      case Keyword.get(opts, :action) do
        nil -> q
        a -> where(q, [e], e.action == ^a)
      end
    end)
    |> order_by([e], desc: e.at, desc: e.id)
    |> limit(^limit)
    |> Repo.all()
  end
end
