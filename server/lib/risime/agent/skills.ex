defmodule RisiMe.Agent.Skills do
  @moduledoc """
  Risi skills (contract v1.26 §26; server S14): the permission layer every tool runs under.
  Until S14 lands nobody is gated: every write asks (§25), and no device is a `risi_skills`
  device.
  """

  @doc "`:ask` or `:allowed` for a write proposal (§26.3)."
  def write_mode(ctx, _tool, _card), do: if(ctx[:allowed?] == true, do: :allowed, else: :ask)

  @doc "The skill's gates when a confirmed write runs (§26.5): `:ok` or `{:error, reason}`."
  def confirm_gate(_write), do: :ok

  @doc "What a confirm of a void card gets (§26.5 `skill_needed`)."
  def void_reply(_write), do: :ok

  @doc "True if `device_id` is a `risi_skills` device of the user (§26.9)."
  def device?(_user_id, _device_id), do: false

  @doc """
  A client write's `ok` for a gated asker: the activity entry and `skill_done` (§26.5). nil when
  the asker isn't gated (the caller posts the §25.4 `answer`).
  """
  def client_done(_write, _args, _device, _result), do: nil
end
