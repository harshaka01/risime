defmodule Mix.Tasks.Risime.Friends do
  @shortdoc "Friendship admin: --pair <phoneA> <phoneB>, or --migrate (v1.6 backfill)"
  @moduledoc """
      mix risime.friends --pair +94771234567 +94777654321   # make two users friends
      mix risime.friends --migrate                          # RisiMe.Release.migrate_friendships/0

  `--migrate` also runs as part of `RisiMe.Release.migrate()` on every deploy (idempotent).
  """
  use Mix.Task

  @impl true
  def run(["--pair", phone_a, phone_b]) do
    Mix.Task.run("app.start")

    with %{id: a} <- RisiMe.Repo.get_by(RisiMe.Accounts.User, phone: phone_a),
         %{id: b} <- RisiMe.Repo.get_by(RisiMe.Accounts.User, phone: phone_b),
         true <- a != b do
      :ok = RisiMe.Social.make_friends!(a, b)
      Mix.shell().info("#{phone_a} and #{phone_b} are friends")
    else
      _ -> Mix.raise("both phones must belong to two different users")
    end
  end

  def run(["--migrate"]) do
    Mix.Task.run("app.config")
    {:ok, n} = RisiMe.Release.migrate_friendships()
    Mix.shell().info("friendships created: #{n}")
  end

  def run(_), do: Mix.raise("usage: mix risime.friends --pair <phoneA> <phoneB> | --migrate")
end
