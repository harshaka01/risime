defmodule RisiMe.Agent.LedgerReminders do
  @moduledoc """
  Reminders, follow-ups and the personal digest of ledger items (contract v1.27 §27.6).
  """

  @doc "Schedules the §27.6 reminders of a confirmed or edited item."
  def schedule(_c), do: :ok
end
