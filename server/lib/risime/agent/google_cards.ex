defmodule RisiMe.Agent.GoogleCards do
  @moduledoc "The `google_reconnect` card (contract v1.31 §31.8). Filled in by the reconnect chunk."

  def reconnect(_link), do: :ok
end
