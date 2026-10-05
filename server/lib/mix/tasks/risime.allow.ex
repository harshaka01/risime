defmodule Mix.Tasks.Risime.Allow do
  @shortdoc "Adds or updates an allowlist entry"
  @moduledoc """
  Adds or updates (by phone) a person who may log in.

      mix risime.allow --phone +94771234567 --email name@company.lk --name "Name" --company CodeGen
  """
  use Mix.Task

  @switches [phone: :string, email: :string, name: :string, company: :string]

  @impl true
  def run(argv) do
    {opts, _, invalid} = OptionParser.parse(argv, strict: @switches)

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
