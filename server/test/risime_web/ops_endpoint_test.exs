defmodule RisiMeWeb.OpsEndpointTest do
  @moduledoc "Decision 075: metrics and LiveDashboard only on the loopback ops listener."
  use RisiMe.DataCase, async: false

  import Phoenix.ConnTest

  test "not started in the test env by default, config binds 127.0.0.1" do
    refute Application.get_env(:risime, :ops_listener, false)
    refute Process.whereis(RisiMeWeb.OpsEndpoint)
    # The runtime.exs shape (what prod gets) is loopback only.
    src = File.read!(Path.expand("../../config/runtime.exs", __DIR__))
    assert src =~ "http: [ip: {127, 0, 0, 1}, port: String.to_integer(port)]"
  end

  describe "with the ops endpoint running" do
    @endpoint RisiMeWeb.OpsEndpoint

    setup do
      Application.put_env(
        :risime,
        RisiMeWeb.OpsEndpoint,
        Application.get_env(:risime, RisiMeWeb.OpsEndpoint, []) ++
          [secret_key_base: String.duplicate("k", 64), http: [ip: {127, 0, 0, 1}, port: 4023]]
      )

      start_supervised!(RisiMeWeb.OpsEndpoint)
      :ok
    end

    test "the listener config is loopback" do
      assert RisiMeWeb.OpsEndpoint.config(:http)[:ip] == {127, 0, 0, 1}
    end

    test "/metrics serves Prometheus text" do
      conn = get(build_conn(), "/metrics")
      assert conn.status == 200
      assert conn.resp_body =~ "risime_prom_ex_beam"
    end

    test "/dashboard renders LiveDashboard" do
      conn = get(build_conn(), "/dashboard")
      assert conn.status in [200, 302]
    end
  end

  test "the main endpoint has no /metrics or /dashboard" do
    for path <- ["/metrics", "/dashboard", "/dashboard/home"] do
      conn = Phoenix.ConnTest.dispatch(build_conn(), RisiMeWeb.Endpoint, :get, path)
      assert conn.status == 404
    end

    paths = Enum.map(RisiMeWeb.Router.__routes__(), & &1.path)
    refute Enum.any?(paths, &String.starts_with?(&1, "/metrics"))
    refute Enum.any?(paths, &String.starts_with?(&1, "/dashboard"))
  end

  test "the Risi turn and JWKS events show up as metrics" do
    :telemetry.execute([:risime, :risi, :turn, :stop], %{duration: 1_000_000}, %{outcome: :ok})
    :telemetry.execute([:risime, :jwks, :fetch], %{count: 1}, %{result: :timeout})
    Process.sleep(50)
    out = PromEx.get_metrics(RisiMe.PromEx)
    assert out =~ "risime_risi_turn_total"
    assert out =~ "risime_jwks_fetch_total"
  end
end
