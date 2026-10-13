defmodule RisiMe.Admin.Seed do
  @moduledoc """
  The boot-time one-time admin seed (decision 089): a temporary task that runs
  `RisiMe.Admin.seed/1` with `RISIME_ADMIN_SEED_EMAIL` once, after the Repo is up. It never
  fails the boot (errors are logged without the email). Off in tests
  (`config :risime, :admin_seed_on_boot, false`).
  """
  require Logger

  def child_spec(_),
    do: %{id: __MODULE__, start: {Task, :start_link, [&run/0]}, restart: :temporary}

  def run do
    if Application.get_env(:risime, :admin_seed_on_boot, true) do
      case RisiMe.Admin.seed(RisiMe.Admin.seed_email()) do
        {:ok, _id} -> Logger.warning("admin console: first admin seeded (audit-logged)")
        :noop -> :ok
      end
    end

    :ok
  rescue
    e -> Logger.error("admin console: seed failed: #{inspect(e.__struct__)}")
  end
end
