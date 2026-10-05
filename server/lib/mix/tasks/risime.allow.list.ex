defmodule Mix.Tasks.Risime.Allow.List do
  @shortdoc "Lists the allowlist"
  @moduledoc "Lists everyone on the allowlist: `mix risime.allow.list`."
  use Mix.Task

  @impl true
  def run(_argv) do
    Mix.Task.run("app.start")

    case RisiMe.Accounts.list_allowlist() do
      [] ->
        Mix.shell().info("allowlist is empty")

      entries ->
        for e <- entries do
          Mix.shell().info(Enum.join([e.phone, e.email, e.display_name, e.company], "\t"))
        end
    end
  end
end
