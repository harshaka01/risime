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
             "checks" => %{
               "postgres" => "ok",
               "cassandra" => "ok",
               "sms" => "log",
               "blob_storage" => "ok",
               "risi" => "off",
               "risi_skills" => "off"
             }
           }
  end

  test "GET /health is 503 when a store is down", %{conn: conn} do
    Application.put_env(:risime, :message_store, DownStore)
    on_exit(fn -> Application.delete_env(:risime, :message_store) end)

    log =
      capture_log(fn ->
        body = conn |> get("/health") |> json_response(503)
        assert body["status"] == "error"

        assert Map.drop(body["checks"], ["blob_storage", "risi", "risi_skills"]) ==
                 %{"postgres" => "ok", "cassandra" => "error", "sms" => "log"}
      end)

    assert log =~ "health: cassandra failed"
  end

  test "GET /health says blob_storage low under the warning level (still 200)", %{conn: conn} do
    prev = Application.get_env(:risime, :blob_disk)
    Application.put_env(:risime, :blob_disk, {60 * 1024 ** 3, 900 * 1024 ** 3})
    on_exit(fn -> Application.put_env(:risime, :blob_disk, prev) end)

    assert %{"checks" => %{"blob_storage" => "low"}} =
             conn |> get("/health") |> json_response(200)
  end

  test "the Cassandra store answers its health check" do
    assert :ok = RisiMe.Messaging.Store.Cassandra.health()
  end

  describe "checks.sms" do
    setup do
      on_exit(fn ->
        Application.put_env(:risime, :sms_mode, :test)
        System.put_env("NOTIFYLK_SENDER_ID", "RisiMeTest")
        :persistent_term.erase({RisiMe.Accounts.SmsStatus, :status})
      end)

      Application.put_env(:risime, :sms_mode, :notifylk)
      :ok
    end

    defp poll_with(response) do
      Req.Test.stub(RisiMe.NotifyLk, fn conn -> Req.Test.json(conn, response) end)
      Req.Test.allow(RisiMe.NotifyLk, self(), Process.whereis(RisiMe.Accounts.SmsStatus))
      capture_log(fn -> RisiMe.Accounts.SmsStatus.poll_now() end)
    end

    test "is informational, from the background poll, and never shows the balance", %{conn: conn} do
      poll_with(%{"status" => "success", "data" => %{"active" => true, "acc_balance" => 98_765}})
      body = conn |> get("/health") |> json_response(200)
      assert body["checks"]["sms"] == "ok"
      refute Jason.encode!(body) =~ "98765"

      poll_with(%{"status" => "success", "data" => %{"active" => true, "acc_balance" => 12}})
      assert (conn |> get("/health") |> json_response(200))["checks"]["sms"] == "low_balance"

      poll_with(%{"status" => "success", "data" => %{"active" => false, "acc_balance" => 500}})
      assert (conn |> get("/health") |> json_response(200))["checks"]["sms"] == "inactive"

      poll_with(%{"status" => "error", "message" => "bad key"})
      # SMS trouble never makes /health a 503.
      assert (conn |> get("/health") |> json_response(200))["checks"]["sms"] == "error"

      System.put_env("NOTIFYLK_SENDER_ID", "NotifyDEMO")

      assert (conn |> get("/health") |> json_response(200))["checks"]["sms"] ==
               "demo_sender_blocked"
    end
  end
end
