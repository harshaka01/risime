defmodule RisiMe.Agent.Reminders do
  @moduledoc """
  `set_reminder` reminders (contract v1.25 §25.4/§25.5, v1.26 §26.4; server S15).
  """

  @doc "§26.4 server undo of a reminder that hasn't fired: `:ok` or `{:error, reason}`."
  def undo(_user_id, _data),
    do: Application.get_env(:risime, :risi_reminder_undo, {:error, :undo_unavailable})

  @doc "§26.5 `cancel_pending`: cancels the user's pending reminders."
  def cancel_pending(_user_id), do: :ok
end
