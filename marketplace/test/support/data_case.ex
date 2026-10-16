defmodule RisiRegistry.DataCase do
  @moduledoc "Database tests (SQL sandbox) with the HTTP stub and caches reset."
  use ExUnit.CaseTemplate

  using do
    quote do
      alias RisiRegistry.Repo
      import RisiRegistry.DataCase
      import RisiRegistry.Fixtures
    end
  end

  setup tags do
    pid = Ecto.Adapters.SQL.Sandbox.start_owner!(RisiRegistry.Repo, shared: not tags[:async])
    on_exit(fn -> Ecto.Adapters.SQL.Sandbox.stop_owner(pid) end)
    RisiRegistry.HTTPStub.reset()
    RisiRegistry.Cards.Cache.clear()
    RisiRegistry.OIDC.Discovery.clear()
    :ok
  end
end
