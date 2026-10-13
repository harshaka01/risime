defmodule RisiMe.Agent.CallRisi.Media do
  @moduledoc """
  The media worker of a Call Risi session (decision 090): `infra/voice/risi_voice_worker.py` as an
  **Erlang Port** (`{:packet, 4}` JSON, see the worker's docstring), started with `python3 -I` from the
  voice venv. It is infrastructure: it joins the room, cleans audio, finds turns and speaks; every
  decision stays in `RisiMe.Agent.CallRisi.Session`. The Port dies with its session (stdin EOF).

  Events reach the owner as `{:call_risi_media, map}` (the session translates the Port's messages).
  Tests set `config :risime, :risi_call_media, Module` (same callbacks; it sends the events itself).
  """

  @callback open(owner :: pid, opts :: map) :: {:ok, term} | {:error, atom}
  @callback command(handle :: term, cmd :: map) :: :ok
  @callback close(handle :: term) :: :ok

  def impl, do: Application.get_env(:risime, :risi_call_media, __MODULE__.Port)

  defmodule Port do
    @moduledoc "The real worker: an Erlang Port (owned by the calling session process)."
    @behaviour RisiMe.Agent.CallRisi.Media

    @impl true
    def open(_owner, _opts) do
      cfg = Application.get_env(:risime, :risi_call_risi_config, [])
      python = Keyword.get(cfg, :python, "python3")
      worker = Keyword.get(cfg, :worker, "")

      if File.exists?(python) and File.exists?(worker) do
        port =
          Elixir.Port.open({:spawn_executable, python}, [
            :binary,
            {:packet, 4},
            :exit_status,
            args: ["-I", worker],
            env: [{~c"HF_HUB_OFFLINE", ~c"1"}, {~c"DO_NOT_TRACK", ~c"1"}]
          ])

        {:ok, port}
      else
        {:error, :worker_missing}
      end
    end

    @impl true
    def command(port, cmd) do
      Elixir.Port.command(port, Jason.encode!(cmd))
      :ok
    rescue
      ArgumentError -> :ok
    end

    @impl true
    def close(port) do
      Elixir.Port.close(port)
      :ok
    rescue
      ArgumentError -> :ok
    end
  end
end
