defmodule RisiMe.Agent.CallTranscribe.Supervisor do
  @moduledoc """
  Contract v1.39 §36: the call-transcription processes, always started (they are idle unless a
  1:1 call rings or a session runs): the in-memory call records (`Calls`), one `Call` process per
  call with a session (`Registry` + `CallSup`), the transcription and post tasks (`TaskSup`),
  and at boot the recovery of sessions a restart left live (`RisiMe.Agent.CallTranscribe.recover/0`).
  """
  use Supervisor

  def start_link(opts), do: Supervisor.start_link(__MODULE__, opts, name: __MODULE__)

  @impl true
  def init(_opts) do
    recover =
      if Application.get_env(:risime, :env) == :test,
        do: [],
        else: [
          Supervisor.child_spec({Task, &RisiMe.Agent.CallTranscribe.recover/0},
            id: :call_transcribe_recover,
            restart: :temporary
          )
        ]

    children =
      [
        {Registry, keys: :unique, name: RisiMe.Agent.CallTranscribe.Registry},
        {DynamicSupervisor, name: RisiMe.Agent.CallTranscribe.CallSup, strategy: :one_for_one},
        {Task.Supervisor, name: RisiMe.Agent.CallTranscribe.TaskSup},
        RisiMe.Agent.CallTranscribe.Calls
      ] ++ recover

    Supervisor.init(children, strategy: :one_for_one)
  end
end
