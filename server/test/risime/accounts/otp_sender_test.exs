defmodule RisiMe.Accounts.OtpSenderTest do
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  require Logger

  alias RisiMe.Accounts.OtpSender
  alias RisiMe.Accounts.OtpSender.{DevLog, NotifyLk, Test}

  @msg %{subject: nil, body: "Your RisiMe verification code is 123456.", code: "123456"}

  setup do
    on_exit(fn ->
      Application.put_env(:risime, :sms_mode, :test)
      Application.put_env(:risime, :otp_dev_log, false)
      System.put_env("NOTIFYLK_SENDER_ID", "RisiMeTest")
      System.delete_env("NOTIFYLK_ALLOW_DEMO_OTP")
    end)
  end

  test "mask/1" do
    assert OtpSender.mask("+94771234522") == "+9477•••••22"
    assert OtpSender.mask("+94770000001") == "+9477•••••01"
  end

  describe "SMS sender selection and the NotifyDEMO guard" do
    test "test and log modes" do
      assert {:ok, Test} = OtpSender.sms_sender()
      Application.put_env(:risime, :sms_mode, :log)
      assert {:error, :no_sms_sender} = OtpSender.sms_sender()
      Application.put_env(:risime, :otp_dev_log, true)
      assert {:ok, DevLog} = OtpSender.sms_sender()
    end

    test "notifylk: an approved sender sends; NotifyDEMO is blocked in any case" do
      Application.put_env(:risime, :sms_mode, :notifylk)
      assert {:ok, NotifyLk} = OtpSender.sms_sender()

      for demo <- ["NotifyDEMO", "notifydemo", " NOTIFYDEMO "] do
        System.put_env("NOTIFYLK_SENDER_ID", demo)
        assert NotifyLk.demo_blocked?()
        assert {:error, :demo_sender_blocked} = OtpSender.sms_sender()
      end

      # DevLog only with OTP_DEV_LOG, never a fake "sent".
      Application.put_env(:risime, :otp_dev_log, true)
      assert {:ok, DevLog} = OtpSender.sms_sender()

      Application.put_env(:risime, :otp_dev_log, false)
      System.put_env("NOTIFYLK_ALLOW_DEMO_OTP", "true")
      refute NotifyLk.demo_blocked?()
      assert {:ok, NotifyLk} = OtpSender.sms_sender()
    end
  end

  test "DevLog logs the destination and code only with OTP_DEV_LOG" do
    Logger.configure(level: :info)
    on_exit(fn -> Logger.configure(level: :warning) end)
    assert capture_log(fn -> DevLog.deliver("+9477•••••22", @msg) end) == ""
    Application.put_env(:risime, :otp_dev_log, true)
    log = capture_log(fn -> DevLog.deliver("+9477•••••22", @msg) end)
    assert log =~ "[DEV OTP] +9477•••••22: 123456"
  end

  test "deliver/4 adds the dev log line beside the real sender" do
    Logger.configure(level: :info)
    on_exit(fn -> Logger.configure(level: :warning) end)
    Application.put_env(:risime, :otp_dev_log, true)

    log =
      capture_log(fn ->
        assert :ok = OtpSender.deliver(:sms, "+94771234522", @msg, "+9477•••••22")
      end)

    assert log =~ "[DEV OTP] +9477•••••22: 123456"
    assert_received {:otp, :sms, "+94771234522", @msg}
  end

  describe "Notify.lk (Req.Test, fake credentials)" do
    setup do
      {:ok, calls} = Agent.start_link(fn -> [] end)
      %{calls: calls}
    end

    defp stub(calls, fun) do
      Req.Test.stub(RisiMe.NotifyLk, fn conn ->
        {:ok, raw, conn} = Plug.Conn.read_body(conn)
        Agent.update(calls, &[{conn.method, conn.request_path, conn.query_string, raw} | &1])
        fun.(conn)
      end)
    end

    test "POSTs a form body (nothing in the URL) and accepts status success", %{calls: c} do
      stub(c, &Req.Test.json(&1, %{"status" => "success", "data" => "Sent"}))
      assert :ok = NotifyLk.deliver("+94771234522", @msg)

      [{"POST", "/api/v1/send", "", raw}] = Agent.get(c, & &1)
      form = URI.decode_query(raw)
      assert form["to"] == "94771234522"
      assert form["message"] == @msg.body
      assert form["sender_id"] == "RisiMeTest"
      assert form["user_id"] == "fake-user"
      assert Map.has_key?(form, "api_key")
    end

    test "errors are logged without the key and never retried", %{calls: c} do
      stub(c, &Req.Test.json(&1, %{"status" => "error", "message" => "Insufficient balance"}))

      log =
        capture_log(fn ->
          assert {:error, :sms_failed} = NotifyLk.deliver("+94771234522", @msg)
        end)

      assert log =~ "Insufficient balance"
      refute log =~ "fake-key-never-shown"

      stub(c, &Plug.Conn.send_resp(&1, 500, "boom"))
      Agent.update(c, fn _ -> [] end)

      log =
        capture_log(fn ->
          assert {:error, :sms_failed} = NotifyLk.deliver("+94771234522", @msg)
        end)

      assert log =~ "HTTP 500"
      refute log =~ "fake-key-never-shown"
      assert length(Agent.get(c, & &1)) == 1

      stub(c, &Req.Test.transport_error(&1, :econnrefused))

      log =
        capture_log(fn ->
          assert {:error, :sms_failed} = NotifyLk.deliver("+94771234522", @msg)
        end)

      assert log =~ "econnrefused"
      refute log =~ "fake-key-never-shown"
    end

    test "only +94 numbers", %{calls: c} do
      stub(c, &Req.Test.json(&1, %{"status" => "success"}))
      assert {:error, :unsupported_number} = NotifyLk.deliver("+447700900123", @msg)
      assert Agent.get(c, & &1) == []
    end

    test "status: POST form, falling back to the documented GET", %{calls: c} do
      stub(c, fn conn ->
        case conn.method do
          "POST" ->
            Plug.Conn.send_resp(conn, 405, "")

          "GET" ->
            Req.Test.json(conn, %{
              "status" => "success",
              "data" => %{"active" => true, "acc_balance" => "1234.50"}
            })
        end
      end)

      assert {:ok, %{active: true, balance: 1234.5}} = NotifyLk.status()

      assert [{"GET", "/api/v1/status", _, _}, {"POST", "/api/v1/status", "", _}] =
               Agent.get(c, & &1)
    end
  end

  test "boot checks: NotifyDEMO warning; PHONE_VERIFICATION=required without a sender is an error" do
    Application.put_env(:risime, :sms_mode, :notifylk)
    System.put_env("NOTIFYLK_SENDER_ID", "NotifyDEMO")
    Application.put_env(:risime, :phone_verification, :required)
    on_exit(fn -> Application.put_env(:risime, :phone_verification, :off) end)

    log = capture_log(fn -> RisiMe.Accounts.SmsStatus.boot_checks() end)
    assert log =~ "SMS OTP disabled until an approved sender ID is set"
    assert log =~ "PHONE_VERIFICATION=required but no SMS can be sent"
    refute log =~ "fake-key-never-shown"

    System.put_env("NOTIFYLK_SENDER_ID", "RisiMe")
    log = capture_log(fn -> RisiMe.Accounts.SmsStatus.boot_checks() end)
    refute log =~ "PHONE_VERIFICATION=required"
  end
end
