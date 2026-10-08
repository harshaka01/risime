defmodule RisiMe.Agent.LLM.Fallback do
  @moduledoc """
  The commercial fallback provider of the model cascade (§24.12, decision 066): a **stub**. It
  makes no external call and always answers `{:error, :not_configured}`.

  The router consults it only when `RISI_FALLBACK=on` (default off) and the local answer's
  confidence is below `RISI_FALLBACK_THRESHOLD`. A real provider needs Harsha's choice and a
  zero-retention agreement first; until then chat text never leaves spark2.
  """
  @behaviour RisiMe.Agent.LLM

  @impl true
  def name, do: "fallback"

  @impl true
  def chat(_body, _opts \\ []), do: {:error, :not_configured}
end
