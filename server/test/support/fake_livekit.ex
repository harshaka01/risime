defmodule RisiMe.FakeLiveKit do
  @moduledoc """
  An in-memory `RisiMe.Calls.LiveKit.API` for tests (v1.19 §20.2): rooms with metadata and
  participants, a call log, and switches for "unreachable". Start it with `setup/1` (it also
  configures LiveKit and makes the removal hook run inline).
  """
  @behaviour RisiMe.Calls.LiveKit.API

  use Agent

  @config [
    url: "wss://risime.example/livekit",
    api_url: "http://127.0.0.1:1",
    api_key: "APItestkey",
    api_secret: "test-secret-0123456789abcdef0123456789abcdef"
  ]

  def config, do: @config

  @doc "ExUnit setup: starts the fake and points the server at it; restores everything on exit."
  def setup(_ctx \\ %{}) do
    {:ok, _} = ExUnit.Callbacks.start_supervised({__MODULE__, []})

    prev =
      for k <- [:livekit, :livekit_api, :livekit_sync], do: {k, Application.get_env(:risime, k)}

    Application.put_env(:risime, :livekit, @config)
    Application.put_env(:risime, :livekit_api, __MODULE__)
    Application.put_env(:risime, :livekit_sync, true)

    ExUnit.Callbacks.on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v == nil,
              do: Application.delete_env(:risime, k),
              else: Application.put_env(:risime, k, v)
            )
    end)

    %{livekit: Map.new(@config)}
  end

  def start_link(_), do: Agent.start_link(fn -> initial() end, name: __MODULE__)

  defp initial, do: %{rooms: %{}, participants: %{}, log: [], down: false}

  @doc "Every API call so far, oldest first, as `{function, args}`."
  def calls, do: Agent.get(__MODULE__, &Enum.reverse(&1.log))

  def clear_calls, do: Agent.update(__MODULE__, &%{&1 | log: []})

  @doc "Makes every call fail (`{:error, :unavailable}`) or work again."
  def down(down?), do: Agent.update(__MODULE__, &%{&1 | down: down?})

  @doc "Puts a participant into a room (as a client connecting with a token would)."
  def join(room, identity) do
    Agent.update(__MODULE__, fn s ->
      %{s | participants: Map.update(s.participants, room, [identity], &(&1 ++ [identity]))}
    end)
  end

  def participants(room), do: Agent.get(__MODULE__, &Map.get(&1.participants, room, []))
  def room(name), do: Agent.get(__MODULE__, &Map.get(&1.rooms, name))

  @doc "Drops a room (it emptied and closed)."
  def close(name) do
    Agent.update(__MODULE__, fn s ->
      %{s | rooms: Map.delete(s.rooms, name), participants: Map.delete(s.participants, name)}
    end)
  end

  defp logged(fun, args, f) do
    Agent.get_and_update(__MODULE__, fn s ->
      s = %{s | log: [{fun, args} | s.log]}
      if s.down, do: {{:error, :unavailable}, s}, else: f.(s)
    end)
  end

  defp view(s, r),
    do: Map.put(r, :num_participants, length(Map.get(s.participants, r.name, [])))

  @impl true
  def create_room(_config, name, opts) do
    logged(:create_room, [name, opts], fn s ->
      r =
        Map.get(s.rooms, name) ||
          %{
            name: name,
            metadata: opts.metadata,
            max_participants: opts.max_participants,
            empty_timeout: opts.empty_timeout,
            departure_timeout: opts.departure_timeout
          }

      {{:ok, view(s, r)}, %{s | rooms: Map.put(s.rooms, name, r)}}
    end)
  end

  @impl true
  def list_rooms(_config, names) do
    logged(:list_rooms, [names], fn s ->
      rooms =
        for {n, r} <- s.rooms, names == nil or n in names, do: view(s, r)

      {{:ok, rooms}, s}
    end)
  end

  @impl true
  def list_participants(_config, room) do
    logged(:list_participants, [room], fn s ->
      {{:ok, for(i <- Map.get(s.participants, room, []), do: %{identity: i})}, s}
    end)
  end

  @impl true
  def remove_participant(_config, room, identity) do
    logged(:remove_participant, [room, identity], fn s ->
      ps = Map.get(s.participants, room, []) -- [identity]
      {:ok, %{s | participants: Map.put(s.participants, room, ps)}}
    end)
  end
end
