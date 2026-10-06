defmodule Mix.Tasks.Risime.User.Disable do
  @shortdoc "Disables a user (no more sign-in; open sockets close)"
  @moduledoc """
  Contract v1.6 §9.1: invited members can't be removed through the allowlist, so an admin
  disables them instead.

      mix risime.user.disable +94771234567
  """
  use Mix.Task

  @impl true
  def run([phone]) do
    Mix.Task.run("app.start")

    case RisiMe.Accounts.disable_user(phone) do
      :ok -> Mix.shell().info("disabled #{phone}")
      {:error, :not_found} -> Mix.raise("no user with phone #{phone}")
    end
  end

  def run(_), do: Mix.raise("usage: mix risime.user.disable +94…")
end
