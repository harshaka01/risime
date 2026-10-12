defmodule Mix.Tasks.Risime.ActionItems.Preview do
  @shortdoc "Dev: the §38 action-items card a buffered Official call or voice note would get"
  @moduledoc """
  Runs the v1.42 §38 extraction on a transcript still in Risi's sealed 24-h buffer and prints
  the `action_items` card it would post. **Nothing is posted or stored.** Only for an Official
  conversation that `--member` is an active member of; prints the card, never the transcript.
  A thin wrapper around `RisiMe.Agent.ActionItems.Dev` (a release uses that module through
  `bin/risime rpc`).

      mix risime.action_items.preview --conversation grp:… --session <session_id> --member <user_id>
      mix risime.action_items.preview --conversation grp:… --note <message_id> --member <user_id>
  """
  use Mix.Task

  @requirements ["app.start"]

  @impl true
  def run(argv) do
    {opts, _} =
      OptionParser.parse!(argv,
        strict: [conversation: :string, session: :string, note: :string, member: :string]
      )

    conv = opts[:conversation] || Mix.raise("--conversation is required")
    member = opts[:member] || Mix.raise("--member is required")

    result =
      cond do
        opts[:session] -> RisiMe.Agent.ActionItems.Dev.print_call(conv, opts[:session], member)
        opts[:note] -> RisiMe.Agent.ActionItems.Dev.print_note(conv, opts[:note], member)
        true -> Mix.raise("--session or --note is required")
      end

    if result != :ok, do: exit({:shutdown, 1})
  end
end
