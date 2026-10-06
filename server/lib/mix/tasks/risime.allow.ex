defmodule Mix.Tasks.Risime.Allow do
  @shortdoc "Adds or updates an allowlist entry"
  @moduledoc """
  Adds or updates (by phone) a person who may log in.

      mix risime.allow --phone +94771234567 --email name@company.lk --name "Name" --company CodeGen
      mix risime.allow --rebind +94771234567   # clear the user's Keycloak binding (409 fix)
  """
  use Mix.Task

  @switches [phone: :string, email: :string, name: :string, company: :string, rebind: :string]

  @impl true
  def run(argv) do
    {opts, _, invalid} = OptionParser.parse(argv, strict: @switches)

    if phone = opts[:rebind], do: rebind(phone), else: allow(opts, invalid)
  end

  defp rebind(phone) do
    Mix.Task.run("app.start")

    case RisiMe.Accounts.rebind(phone) do
      {:ok, nil} -> Mix.shell().info("#{phone} had no Keycloak binding")
      {:ok, _sub} -> Mix.shell().info("cleared the Keycloak binding of #{phone}")
      {:error, :not_found} -> Mix.raise("no user with phone #{phone}")
    end
  end

  defp allow(opts, invalid) do
    missing = Enum.reject([:phone, :email, :name, :company], &opts[&1])

    if invalid != [] or missing != [] do
      Mix.raise("usage: mix risime.allow --phone +94… --email … --name \"…\" --company …")
    end

    Mix.Task.run("app.start")

    attrs = %{
      phone: opts[:phone],
      email: opts[:email],
      display_name: opts[:name],
      company: opts[:company]
    }

    case RisiMe.Accounts.allow(attrs) do
      {:ok, entry} ->
        Mix.shell().info(
          "allowed #{entry.phone} #{entry.email} \"#{entry.display_name}\" #{entry.company}"
        )

      {:error, changeset} ->
        errors =
          Ecto.Changeset.traverse_errors(changeset, fn {msg, _} -> msg end)
          |> Enum.map_join(", ", fn {k, v} -> "#{k} #{Enum.join(v, ", ")}" end)

        Mix.raise("not allowed: #{errors}")
    end
  end
end
