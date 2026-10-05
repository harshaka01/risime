defmodule RisiMeWeb.ChannelCase do
  @moduledoc "Test case for channel tests (Phoenix.ChannelTest + SQL sandbox)."
  use ExUnit.CaseTemplate

  using do
    quote do
      import Phoenix.ChannelTest
      import RisiMeWeb.ChannelCase

      @endpoint RisiMeWeb.Endpoint
    end
  end

  setup tags do
    RisiMe.DataCase.setup_sandbox(tags)
    :ok
  end
end
