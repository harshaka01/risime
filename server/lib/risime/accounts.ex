defmodule RisiMe.Accounts do
  @moduledoc """
  Allowlist, users, email OTP challenges and bearer tokens.
  """
  import Ecto.Query

  alias RisiMe.Repo
  alias RisiMe.Accounts.{AllowlistEntry, User}

  ## Allowlist

  @doc "Inserts or updates an allowlist entry, keyed by phone."
  def allow(attrs) do
    attrs = Map.new(attrs)

    entry =
      case attrs[:phone] && Repo.get_by(AllowlistEntry, phone: attrs[:phone]) do
        nil -> %AllowlistEntry{}
        entry -> entry
      end

    entry
    |> AllowlistEntry.changeset(attrs)
    |> Repo.insert_or_update()
  end

  def list_allowlist do
    Repo.all(from a in AllowlistEntry, order_by: [asc: fragment("lower(?)", a.display_name)])
  end

  def get_allowlist_entry(phone), do: Repo.get_by(AllowlistEntry, phone: phone)

  ## Users

  def get_user(id) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> Repo.get(User, id)
      :error -> nil
    end
  end
end
