defmodule RisiRegistryWeb.ConnCase do
  @moduledoc "HTTP tests against the endpoint, with the database sandbox and stubs reset."
  use ExUnit.CaseTemplate

  using do
    quote do
      @endpoint RisiRegistryWeb.Endpoint
      import Plug.Conn
      import Phoenix.ConnTest
      import RisiRegistry.Fixtures
      alias RisiRegistry.Repo
    end
  end

  setup tags do
    pid = Ecto.Adapters.SQL.Sandbox.start_owner!(RisiRegistry.Repo, shared: not tags[:async])
    on_exit(fn -> Ecto.Adapters.SQL.Sandbox.stop_owner(pid) end)
    RisiRegistry.HTTPStub.reset()
    RisiRegistry.Cards.Cache.clear()
    RisiRegistry.OIDC.Discovery.clear()
    {:ok, conn: Phoenix.ConnTest.build_conn()}
  end
end
