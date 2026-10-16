defmodule RisiRegistry.AllowList do
  @moduledoc """
  Who may get past sign-in (architect 22:10). The list is data in the registry's own database,
  never hard-coded. The first entry comes from the environment (`ALLOW_LIST_SEED`) through
  `seed_from_config/0`; later entries are added by an admin with `add/2`.
  """
  import Ecto.Query
  alias RisiRegistry.{Audit, Repo}

  defmodule Account do
    @moduledoc false
    use Ecto.Schema

    schema "allowed_accounts" do
      field :email, :string
      field :added_by, :string
      timestamps(type: :utc_datetime_usec, updated_at: false)
    end
  end

  @doc "True when `email` is on the list (case-insensitive)."
  def allowed?(email) when is_binary(email) and email != "" do
    normalised = normalise(email)
    Repo.exists?(from a in Account, where: fragment("lower(?)", a.email) == ^normalised)
  end

  def allowed?(_), do: false

  @doc "Adds `email`, recording who added it. Idempotent."
  def add(email, added_by) when is_binary(email) and is_binary(added_by) do
    email = normalise(email)

    cond do
      not valid_email?(email) -> {:error, :invalid_email}
      allowed?(email) -> :ok
      true -> insert(email, added_by)
    end
  end

  defp insert(email, added_by) do
    %Account{email: email, added_by: added_by}
    |> Ecto.Changeset.change()
    |> Ecto.Changeset.unique_constraint(:email, name: :allowed_accounts_email_index)
    |> Repo.insert()
    |> case do
      {:ok, _} ->
        Audit.record("allow_list_added", %{actor: added_by, result_code: "ok"})
        :ok

      {:error, _} = err ->
        err
    end
  end

  def count, do: Repo.aggregate(Account, :count)

  @doc "Inserts the configured first entry, if any. Never logs the address."
  def seed_from_config do
    case Application.get_env(:risi_registry, :allow_list_seed) do
      email when is_binary(email) and email != "" -> add(email, "seed")
      _ -> :no_seed
    end
  end

  defp normalise(email), do: email |> String.trim() |> String.downcase()
  defp valid_email?(email), do: Regex.match?(~r/\A[^@\s]+@[^@\s]+\.[^@\s]+\z/, email)
end
