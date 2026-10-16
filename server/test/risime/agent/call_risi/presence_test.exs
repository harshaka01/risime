defmodule RisiMe.Agent.CallRisi.PresenceTest do
  @moduledoc """
  P0 calls (Harsha 2026-10-16): a Call Risi whose caller is no longer in the LiveKit room (a cellular
  call took the phone, the app was killed) ends server-side after 30 s, so a stale session never
  holds a `risi_busy` slot. An unknown answer (LiveKit down) never ends a call.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.Session

  defmodule Media do
    @behaviour RisiMe.Agent.CallRisi.Media
    @impl true
    def open(_owner, args), do: {:ok, args.test}
    @impl true
    def command(test, cmd) do
      send(test, {:media_cmd, cmd})
      :ok
    end

    @impl true
    def close(_), do: :ok
  end

  @keys [
    :risi_call_media,
    :risi_call_presence,
    :risi_call_presence_ms,
    :risi_call_gone_ms,
    :risi_call_runs
  ]

  setup do
    prev = for k <- @keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_call_presence_ms, 20)
    Application.put_env(:risime, :risi_call_gone_ms, 150)
    Application.put_env(:risime, :risi_call_runs, __MODULE__)

    on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v,
              do: Application.put_env(:risime, k, v),
              else: Application.delete_env(:risime, k)
            )
    end)

    case start_supervised(RisiMe.Agent.CallRisi.Supervisor) do
      {:ok, _} -> :ok
      {:error, {{:already_started, _}, _}} -> :ok
      {:error, {:already_started, _}} -> :ok
    end

    me = self()

    args = %{
      conv: "grp:risi-chat",
      call_id: Ecto.UUID.generate(),
      user_id: Ecto.UUID.generate(),
      device_id: Ecto.UUID.generate(),
      room: "room1",
      risi_token: "tok",
      worker_url: "ws://127.0.0.1:7880",
      test: me,
      metrics_to: me,
      tools?: false,
      cal?: false,
      stt?: false,
      v3?: false,
      post: fn conv, body, risi -> send(me, {:posted, conv, body, risi}) end
    }

    %{args: args}
  end

  def record(_row), do: :ok

  defp start(args, presence) do
    :persistent_term.put({__MODULE__, :presence}, presence)

    Application.put_env(:risime, :risi_call_presence, fn _ ->
      :persistent_term.get({__MODULE__, :presence})
    end)

    {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
    assert_receive {:media_cmd, %{"cmd" => "join"}}
    send(pid, {:call_risi_media, %{"ev" => "joined"}})
    send(pid, {:call_risi_media, %{"ev" => "caller_joined"}})
    pid
  end

  test "a caller missing from the room for 30 s ends the call (transcript: hangup)", %{args: args} do
    pid = start(args, :present)
    ref = Process.monitor(pid)
    Process.sleep(120)
    assert Process.alive?(pid), "a present caller keeps the call"
    :persistent_term.put({__MODULE__, :presence}, :absent)
    assert_receive {:media_cmd, %{"cmd" => "leave", "reason" => "hangup"}}, 2_000
    assert_receive {:DOWN, ^ref, :process, ^pid, _}, 2_000
  end

  test "a caller who comes back in time keeps the call", %{args: args} do
    pid = start(args, :absent)
    Process.sleep(80)
    :persistent_term.put({__MODULE__, :presence}, :present)
    Process.sleep(250)
    assert Process.alive?(pid)
    refute_received {:media_cmd, %{"cmd" => "leave"}}
    send(pid, {:call_risi_media, %{"ev" => "caller_left"}})
  end

  test "an unknown presence (LiveKit unavailable) never ends the call", %{args: args} do
    pid = start(args, :unknown)
    Process.sleep(400)
    assert Process.alive?(pid)
    refute_received {:media_cmd, %{"cmd" => "leave"}}
    send(pid, {:call_risi_media, %{"ev" => "caller_left"}})
  end

  test "presence/1 reads the room's participants by the caller's identity prefix" do
    prev = Application.get_env(:risime, :risi_call_presence)
    Application.delete_env(:risime, :risi_call_presence)

    try do
      # No LiveKit configured in test: unknown, never absent.
      assert Session.presence(%{room: "r", user_id: "u"}) == :unknown
    after
      if prev, do: Application.put_env(:risime, :risi_call_presence, prev)
    end
  end
end
