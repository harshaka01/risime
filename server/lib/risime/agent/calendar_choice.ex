defmodule RisiMe.Agent.CalendarChoice do
  @moduledoc """
  The calendar the asker's phone last reported (P0 2026-10-09, proposal
  `2026-10-09-risi-action-loop.md`): `{"name", "account"}` from the Calendar skill's `PATCH`
  (`calendar`) or a `calendar_add` result (`calendar`). It is the action card's `calendar`
  hint (null until the phone reported one: the phone then asks on first use). Sealed with
  `RISI_DATA_KEY` (it may name an account), table `risi_calendar_choices`.
  """
  alias RisiMe.Agent.Seal
  alias RisiMe.Repo

  @table "risi_calendar_choices"

  @doc "True for a well-formed choice: `name` 1–100 characters, `account` null or ≤ 200."
  def valid?(%{"name" => n} = c) when is_binary(n) do
    Map.keys(c) -- ["name", "account"] == [] and String.length(n) in 1..100 and
      (c["account"] == nil or (is_binary(c["account"]) and String.length(c["account"]) <= 200))
  end

  def valid?(_), do: false

  @doc "The user's choice (`%{\"name\", \"account\"}`) or nil."
  def get(user) do
    with %{rows: [[sealed]]} <-
           Repo.query!("SELECT choice FROM #{@table} WHERE user_id = $1", [dump(user)]),
         {:ok, key} <- Seal.data(),
         {:ok, %{} = c} <- Seal.open(key, aad(user), sealed) do
      Map.take(c, ["name", "account"])
    else
      _ -> nil
    end
  rescue
    _ -> nil
  end

  @doc "Remembers a choice (nil forgets it). `:ok` (a malformed one, or no key, is ignored)."
  def put(user, nil) do
    Repo.query!("DELETE FROM #{@table} WHERE user_id = $1", [dump(user)])
    :ok
  end

  def put(user, choice) do
    with true <- valid?(choice),
         {:ok, key} <- Seal.data() do
      c = %{"name" => choice["name"], "account" => choice["account"]}

      Repo.query!(
        "INSERT INTO #{@table} (user_id, choice, updated_at) VALUES ($1, $2, $3) " <>
          "ON CONFLICT (user_id) DO UPDATE SET choice = EXCLUDED.choice, updated_at = EXCLUDED.updated_at",
        [dump(user), Seal.seal(key, aad(user), c), DateTime.utc_now()]
      )
    end

    :ok
  end

  defp aad(user), do: "#{@table}:#{user}:choice"
  defp dump(user), do: Ecto.UUID.dump!(user)
end
