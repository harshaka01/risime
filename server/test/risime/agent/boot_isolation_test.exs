defmodule RisiMe.Agent.BootIsolationTest do
  @moduledoc """
  P0 2026-10-08: with `RISI=on` and a replaced `RISI_MLS_KEK`, `Agent.Mls.init/1` failed with
  `:tampered` and the whole application exited at boot (a restart loop). Risi must never fail
  the boot or stop the server: the application's Risi children (`RisiMe.Agent.children/1`) are
  started here under a stand-in for the application supervisor next to a sibling that must
  survive, and `/health` must stay `ok` with `checks.risi` naming the reason.

  The tests that open real sealed rows need the NIF (`scripts/build-mls-nif`) and skip without.
  """
  use RisiMeWeb.ConnCase, async: false

  import Ecto.Query
  import ExUnit.CaptureIO
  import ExUnit.CaptureLog
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.Agent
  alias RisiMe.Agent.{KeyCheck, Status}
  alias RisiMe.Agent.Mls.Nif
  alias RisiMe.{Release, Repo, Risi}

  @nif Nif.loaded?()
  @mismatch "unavailable: kek_mismatch (RISI_MLS_KEK does not match the sealed store)"

  setup :with_attestation_key

  setup do
    env = [
      risi: true,
      risi_agent_check: true,
      risi_user_id: Ecto.UUID.generate(),
      risi_device_id: Ecto.UUID.generate(),
      risi_mls_kek: key(),
      risi_data_key: key(),
      risi_hold_ms: 300,
      risi_retry_ms: 50
    ]

    old = for {k, _} <- env, do: {k, Application.fetch_env(:risime, k)}
    for {k, v} <- env, do: Application.put_env(:risime, k, v)
    status = Status.get()

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end

      :persistent_term.put({Nif, :loaded}, @nif)
      Status.put(status)
      Status.clear_failure()
    end)

    :ok
  end

  defp key, do: Base.encode64(:crypto.strong_rand_bytes(32))

  defp eventually(fun, tries \\ 400) do
    cond do
      fun.() -> :ok
      tries == 0 -> flunk("condition never held")
      true -> Process.sleep(25) && eventually(fun, tries - 1)
    end
  end

  # The application's Risi children under a one_for_one supervisor like RisiMe.Supervisor, after
  # a sibling (the endpoint's stand-in). Returns once the starter has finished its work.
  defp boot! do
    n = System.unique_integer([:positive])
    sup = :"risi_tree_sup_#{n}"
    starter = :"risi_starter_#{n}"
    sibling = Supervisor.child_spec({Task, fn -> Process.sleep(:infinity) end}, id: :sibling)

    app =
      start_supervised!(%{
        id: :fake_app,
        type: :supervisor,
        start:
          {Supervisor, :start_link,
           [[sibling | Agent.children(sup: sup, name: starter)], [strategy: :one_for_one]]}
      })

    _ = :sys.get_state(starter)

    [{:sibling, sib, _, _}] =
      Enum.filter(Supervisor.which_children(app), &(elem(&1, 0) == :sibling))

    %{app: app, sibling: sib, starter: starter}
  end

  defp shutdown! do
    quiesce!()
    :ok = stop_supervised(:fake_app)
    eventually(fn -> not Agent.running?() end)
  end

  # Suspends the tree's workers between callbacks (callers before Agent.Mls), so stopping or
  # killing them never cuts a query on the shared sandbox connection.
  defp quiesce! do
    convs =
      case Process.whereis(RisiMe.Agent.ConversationSup) do
        nil -> []
        sup -> for {_, pid, _, _} <- DynamicSupervisor.which_children(sup), is_pid(pid), do: pid
      end

    named =
      for name <- [RisiMe.Agent.KeyPackages, RisiMe.Agent.Mls],
          pid = Process.whereis(name),
          do: pid

    inbox = List.wrap(Process.whereis(RisiMe.Agent.Inbox))
    Enum.each(inbox ++ convs ++ named, &:sys.suspend/1)
  end

  defp health(conn), do: conn |> get("/health") |> json_response(200)

  defp error_lines(log),
    do: log |> String.split("\n") |> Enum.count(&(&1 =~ "Risi agent unavailable"))

  defp kek_bytes, do: elem(Agent.kek(), 1)

  defp kv_count,
    do:
      Repo.one(
        from r in "risi_mls_kv",
          where: r.device_id == type(^Risi.device_id(), :binary_id),
          select: count()
      )

  defp checks, do: Repo.all(from c in "risi_key_checks", select: {c.name, c.mac}) |> Enum.sort()

  describe "without the NIF's sealed rows" do
    test "missing keys: the server boots, Risi is unavailable, one error, /health ok", %{
      conn: conn
    } do
      data_key = Application.get_env(:risime, :risi_data_key)
      Application.delete_env(:risime, :risi_mls_kek)

      log =
        capture_log(fn ->
          ctx = boot!()
          assert Process.alive?(ctx.app) and Process.alive?(ctx.sibling)
        end)

      assert Agent.health() =~ "unavailable: "
      assert Agent.health() =~ "missing_key RISI_MLS_KEK (base64, 32 bytes)"
      refute Agent.running?()
      refute Risi.available?()
      assert error_lines(log) == 1
      refute log =~ data_key

      body = health(conn)
      assert body["status"] == "ok"
      assert body["checks"]["risi"] == Agent.health()
    end

    test "missing NIF: unavailable with the reason, /health ok", %{conn: conn} do
      :persistent_term.put({Nif, :loaded}, false)

      log = capture_log(fn -> boot!() end)

      assert Agent.health() == "unavailable: nif_not_loaded (scripts/build-mls-nif)"
      assert error_lines(log) == 1
      assert %{"status" => "ok", "checks" => %{"risi" => risi}} = health(conn)
      assert risi == "unavailable: nif_not_loaded (scripts/build-mls-nif)"
    end

    test "RISI off: checks.risi is off and nothing is logged as an error", %{conn: conn} do
      Application.put_env(:risime, :risi, false)
      log = capture_log(fn -> boot!() end)
      assert Status.get() == :off
      assert error_lines(log) == 0
      assert %{"status" => "ok", "checks" => %{"risi" => "off"}} = health(conn)
    end

    @tag skip: if(@nif, do: "needs a NIF that is not loaded", else: false)
    test "an open that raises inside Agent.Mls.init is caught: unavailable, never a crash", %{
      conn: conn
    } do
      # Claims the NIF is loaded although it isn't: every NIF call raises in Agent.Mls.init.
      :persistent_term.put({Nif, :loaded}, true)

      log =
        capture_log(fn ->
          ctx = boot!()
          assert Process.alive?(ctx.app) and Process.alive?(ctx.sibling)
          assert Process.alive?(Process.whereis(ctx.starter))
        end)

      assert Agent.health() == "unavailable: exception (ErlangError)"
      assert error_lines(log) == 1
      assert %{"status" => "ok"} = health(conn)
    end

    test "preflight FAIL without keys; never prints key material" do
      data_key = Application.get_env(:risime, :risi_data_key)
      Application.delete_env(:risime, :risi_mls_kek)

      out =
        capture_io(fn ->
          assert {:error, {:not_startable, problems}} = Release.risi_preflight(halt: false)
          assert :missing_mls_kek in problems
        end)

      assert out =~ "RISI PREFLIGHT FAIL "
      assert out =~ "missing_key RISI_MLS_KEK"
      refute out =~ data_key
    end
  end

  describe "with the NIF" do
    @describetag :mls_nif
    unless @nif, do: @describetag(skip: "NIF not built: run scripts/build-mls-nif")

    # Risi seals its rows under the first KEK, then stops.
    defp seal! do
      boot!()
      eventually(fn -> Agent.running?() and Risi.available?() end)
      assert kv_count() > 0
      assert KeyCheck.compare(KeyCheck.mls_name(Risi.device_id()), kek_bytes()) == :ok
      shutdown!()
      Application.get_env(:risime, :risi_mls_kek)
    end

    test "boot with a wrong KEK: the app starts, /health ok, risi kek_mismatch, no crash; " <>
           "preflight FAIL, then OK with the right KEK (read-only)",
         %{conn: conn} do
      kek1 = seal!()
      kek2 = key()
      Application.put_env(:risime, :risi_mls_kek, kek2)

      log =
        capture_log(fn ->
          ctx = boot!()
          assert Process.alive?(ctx.app) and Process.alive?(ctx.sibling)
        end)

      assert Agent.health() == @mismatch
      refute Agent.running?()
      assert error_lines(log) == 1
      refute log =~ kek1
      refute log =~ kek2
      assert %{"status" => "ok", "checks" => %{"risi" => @mismatch}} = health(conn)

      {rows, keychecks} = {kv_count(), checks()}

      out = capture_io(fn -> {:error, :kek_mismatch} = Release.risi_preflight(halt: false) end)

      assert out =~
               "RISI PREFLIGHT FAIL kek_mismatch (RISI_MLS_KEK does not match the sealed store)"

      Application.put_env(:risime, :risi_mls_kek, kek1)
      out = capture_io(fn -> :ok = Release.risi_preflight(halt: false) end)
      assert out =~ "RISI PREFLIGHT OK"
      assert out =~ "risi_mls_kv: #{rows} row(s) open; key check ok"
      refute out =~ kek1

      # Read-only: no row and no key check changed.
      assert {kv_count(), checks()} == {rows, keychecks}

      # The right KEK again: Risi comes back.
      shutdown!()
      boot!()
      eventually(fn -> Agent.health() == "ok" end)
      shutdown!()
    end

    test "rows sealed before the key check existed: a :tampered open is reported as " <>
           "kek_mismatch; the right KEK writes the check",
         %{conn: conn} do
      kek1 = seal!()
      Repo.delete_all("risi_key_checks")
      Application.put_env(:risime, :risi_mls_kek, key())

      log = capture_log(fn -> boot!() end)
      assert Agent.health() == @mismatch
      assert error_lines(log) == 1
      assert %{"checks" => %{"risi" => @mismatch}} = health(conn)
      assert checks() |> Enum.filter(&String.starts_with?(elem(&1, 0), "mls_kek:")) == []

      Application.put_env(:risime, :risi_mls_kek, kek1)
      shutdown!()
      boot!()
      eventually(fn -> Agent.health() == "ok" end)
      assert KeyCheck.compare(KeyCheck.mls_name(Risi.device_id()), kek_bytes()) == :ok
      shutdown!()
    end

    test "a tree crash loop after start stops only the tree; the server stays up", %{conn: conn} do
      ctx = boot!()
      eventually(fn -> Agent.running?() end)
      tree = Process.whereis(RisiMe.Agent.Supervisor)

      # Every restart of Agent.Mls now fails (another KEK): the tree hits its restart limit.
      Application.put_env(:risime, :risi_mls_kek, key())

      log =
        capture_log(fn ->
          quiesce!()
          Process.exit(Process.whereis(RisiMe.Agent.Mls), :kill)
          eventually(fn -> not Process.alive?(tree) end)
          _ = :sys.get_state(ctx.starter)
        end)

      assert Agent.health() ==
               "unavailable: crashed after start: " <>
                 String.trim_leading(@mismatch, "unavailable: ")

      assert Process.alive?(ctx.app) and Process.alive?(ctx.sibling)
      assert Process.alive?(Process.whereis(ctx.starter))
      assert error_lines(log) == 1
      refute Risi.available?()
      assert %{"status" => "ok"} = health(conn)
    end

    test "a fresh store: preflight OK writes nothing, the first boot records the key checks" do
      out = capture_io(fn -> :ok = Release.risi_preflight(halt: false) end)
      assert out =~ "RISI PREFLIGHT OK"
      assert out =~ "risi_mls_kv: 0 row(s) open; key check absent"
      assert kv_count() == 0
      assert checks() == []
      refute Risi.agent?(Risi.user_id())

      boot!()
      eventually(fn -> Agent.health() == "ok" end)
      assert length(checks()) == 2
      shutdown!()
    end

    test "RISI_DATA_KEY changed: a warning, the new key recorded, Risi still runs" do
      {:ok, old} = Base.decode64(key())
      KeyCheck.record(KeyCheck.data_name(), old)

      log =
        capture_log(fn ->
          boot!()
          eventually(fn -> Agent.health() == "ok" end)
        end)

      assert log =~ "RISI_DATA_KEY differs"
      assert error_lines(log) == 0
      {:ok, new} = Agent.data_key()
      assert KeyCheck.compare(KeyCheck.data_name(), new) == :ok

      out = capture_io(fn -> :ok = Release.risi_preflight(halt: false) end)
      refute out =~ "RISI_DATA_KEY differs"
      shutdown!()
    end
  end
end
