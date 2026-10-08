defmodule RisiMe.Agent.Status do
  @moduledoc """
  Why Risi's tree is not running (P0 2026-10-08), for `RisiMe.Agent.health/0`: the status set by
  `RisiMe.Agent.Starter` (`:starting`, `:off`, `:running`, `{:not_startable, _}`,
  `:kek_mismatch`, `{:crashed, _}`, …) and the last failure recorded by `RisiMe.Agent.Mls` when
  its open fails. Reasons only, never key material. Changes are rare (boot, a failure), so
  `:persistent_term` holds them.
  """

  @status {__MODULE__, :status}
  @failure {__MODULE__, :failure}

  def put(status), do: :persistent_term.put(@status, status)
  def get, do: :persistent_term.get(@status, nil)

  def failure(reason), do: :persistent_term.put(@failure, reason)
  def last_failure, do: :persistent_term.get(@failure, nil)

  def clear_failure do
    _ = :persistent_term.erase(@failure)
    :ok
  end
end
