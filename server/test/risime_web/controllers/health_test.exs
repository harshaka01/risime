defmodule RisiMeWeb.HealthTest do
  use RisiMeWeb.ConnCase, async: false

  import ExUnit.CaptureLog

  defmodule DownStore do
    def health, do: {:error, :down}
  end

  test "GET /health is 200 with both stores up, no auth needed", %{conn: conn} do
    body = conn |> get("/health") |> json_response(200)

    assert body == %{
             "status" => "ok",
             "version" => RisiMe.Application.version(),
             "checks" => %{"postgres" => "ok", "cassandra" => "ok"}
           }
  end

  test "GET /health is 503 when a store is down", %{conn: conn} do
    Application.put_env(:risime, :message_store, DownStore)
    on_exit(fn -> Application.delete_env(:risime, :message_store) end)

    log =
      capture_log(fn ->
        body = conn |> get("/health") |> json_response(503)
        assert body["status"] == "error"
        assert body["checks"] == %{"postgres" => "ok", "cassandra" => "error"}
      end)

    assert log =~ "health: cassandra failed"
  end

  test "the Cassandra store answers its health check" do
    assert :ok = RisiMe.Messaging.Store.Cassandra.health()
  end
end
