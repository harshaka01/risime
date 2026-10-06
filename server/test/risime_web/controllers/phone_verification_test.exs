defmodule RisiMeWeb.PhoneVerificationTest do
  @moduledoc "Contract v1.4 §7.1: request + confirm, limits, Retry-After, guard. No real SMS."
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Accounts.{PhoneChallenge, User}
  alias RisiMe.Repo

  @message ~r/^Your RisiMe verification code is (\d{6})\. It expires in 5 minutes\. Do not share it\.$/

  setup do
    RisiMe.Auth.clear_cache()
    Application.put_env(:risime, :phone_verification, :required)

    on_exit(fn ->
      Application.put_env(:risime, :phone_verification, :off)
      Application.put_env(:risime, :sms_mode, :test)
      Application.put_env(:risime, :otp_dev_log, false)
      System.put_env("NOTIFYLK_SENDER_ID", "RisiMeTest")
    end)

    entry = allowlist_entry()
    token = access_token(entry.email)
    %{entry: entry, token: token}
  end

  defp authed(conn, token), do: put_req_header(conn, "authorization", "Bearer " <> token)

  defp request_code(conn, token),
    do: conn |> authed(token) |> post(~p"/api/v1/me/phone/verify/request", %{})

  defp confirm(conn, token, code),
    do: conn |> authed(token) |> post(~p"/api/v1/me/phone/verify/confirm", %{code: code})

  defp me(conn, token), do: conn |> authed(token) |> get(~p"/api/v1/me") |> json_response(200)

  defp receive_sms(phone) do
    receive do
      {:otp, :sms, ^phone, %{body: body}} ->
        [_, code] = Regex.run(@message, body)
        code
    after
      1000 -> flunk("no SMS")
    end
  end

  defp wrong(code),
    do:
      code
      |> String.to_integer()
      |> Kernel.+(1)
      |> rem(1_000_000)
      |> Integer.to_string()
      |> String.pad_leading(6, "0")

  test "request sends a code to the allowlisted phone; confirm verifies", %{
    conn: conn,
    entry: e,
    token: t
  } do
    assert %{"user" => %{"phone_verified" => false}} = me(conn, t)

    body = request_code(conn, t) |> json_response(200)

    assert body == %{
             "status" => "sent",
             "expires_in" => 300,
             "to" => RisiMe.Accounts.OtpSender.mask(e.phone)
           }

    assert body["to"] =~ ~r/^\+9477•••••\d\d$/
    code = receive_sms(e.phone)

    # Stored as an HMAC, never the code.
    challenge = Repo.one!(from c in PhoneChallenge, where: c.phone == ^e.phone)
    refute challenge.code_hash =~ code

    assert %{"user" => %{"phone_verified" => true, "phone" => phone}} =
             confirm(conn, t, code) |> json_response(200)

    assert phone == e.phone
    assert %{"user" => %{"phone_verified" => true}} = me(conn, t)

    assert %{"error" => %{"code" => "already_verified"}} =
             request_code(conn, t) |> json_response(409)

    assert %{"error" => %{"code" => "already_verified"}} =
             confirm(conn, t, code) |> json_response(409)
  end

  test "wrong codes: 401 with attempts_left, then 429 too_many_attempts with Retry-After",
       %{conn: conn, entry: e, token: t} do
    request_code(conn, t) |> json_response(200)
    code = receive_sms(e.phone)

    for left <- [4, 3, 2, 1, 0] do
      assert %{"error" => %{"code" => "invalid_code", "attempts_left" => ^left}} =
               confirm(conn, t, wrong(code)) |> json_response(401)
    end

    resp = confirm(conn, t, code)
    assert %{"error" => %{"code" => "too_many_attempts"}} = json_response(resp, 429)
    assert [retry] = get_resp_header(resp, "retry-after")
    assert String.to_integer(retry) > 0
  end

  test "no challenge, a non-string code: 401 without attempts_left", %{conn: conn, token: t} do
    assert %{"error" => error} = confirm(conn, t, "123456") |> json_response(401)
    assert error == %{"code" => "invalid_code", "message" => "The code is incorrect"}
  end

  test "an expired code is 410; a new request supersedes the old code", %{
    conn: conn,
    entry: e,
    token: t
  } do
    request_code(conn, t) |> json_response(200)
    old = receive_sms(e.phone)

    Repo.update_all(from(c in PhoneChallenge, where: c.phone == ^e.phone),
      set: [expires_at: DateTime.add(DateTime.utc_now(), -1, :second)]
    )

    assert %{"error" => %{"code" => "expired"}} = confirm(conn, t, old) |> json_response(410)

    request_code(conn, t) |> json_response(200)
    new = receive_sms(e.phone)
    if old != new, do: assert(confirm(conn, t, old) |> json_response(401))
    assert confirm(conn, t, new) |> json_response(200)
  end

  test "3 requests per user per 15 min, then 429 with Retry-After", %{conn: conn, token: t} do
    for _ <- 1..3, do: request_code(conn, t) |> json_response(200)
    resp = request_code(conn, t)
    assert %{"error" => %{"code" => "rate_limited"}} = json_response(resp, 429)
    assert [retry] = get_resp_header(resp, "retry-after")
    assert String.to_integer(retry) in 1..900
  end

  defp challenge!(user_id, phone, at \\ DateTime.utc_now()) do
    Repo.insert!(%PhoneChallenge{
      user_id: user_id,
      phone: phone,
      code_hash: :crypto.strong_rand_bytes(32),
      expires_at: DateTime.add(at, 300, :second),
      inserted_at: at
    })
  end

  test "5 SMS per phone per 24 h, counted in Postgres (survives restarts)", %{
    conn: conn,
    entry: e,
    token: t
  } do
    %{"user" => %{"id" => id}} = me(conn, t)
    two_hours_ago = DateTime.add(DateTime.utc_now(), -2, :hour)
    for _ <- 1..5, do: challenge!(id, e.phone, two_hours_ago)

    resp = request_code(conn, t)
    assert %{"error" => %{"code" => "rate_limited"}} = json_response(resp, 429)
    [retry] = get_resp_header(resp, "retry-after")
    assert_in_delta String.to_integer(retry), 22 * 3600, 60
  end

  test "30 SMS per server per hour", %{conn: conn, token: t} do
    %{user: other} = logged_in_user()
    for i <- 1..30, do: challenge!(other.id, "+9477#{String.pad_leading("#{i}", 7, "0")}")

    assert %{"error" => %{"code" => "rate_limited"}} = request_code(conn, t) |> json_response(429)
  end

  test "only +94 numbers are sent; others are 503 and nothing is counted", %{conn: conn} do
    entry = allowlist_entry(phone: "+447700900123")
    token = access_token(entry.email)

    assert %{"error" => %{"code" => "sms_unavailable"}} =
             request_code(conn, token) |> json_response(503)

    assert Repo.aggregate(from(c in PhoneChallenge, where: c.phone == ^entry.phone), :count) == 0
  end

  test "NotifyDEMO is blocked (503, no fake 'sent'); log mode needs OTP_DEV_LOG",
       %{conn: conn, token: t} do
    Application.put_env(:risime, :sms_mode, :notifylk)
    System.put_env("NOTIFYLK_SENDER_ID", "NotifyDEMO")

    assert %{"error" => %{"code" => "sms_unavailable"}} =
             request_code(conn, t) |> json_response(503)

    Application.put_env(:risime, :sms_mode, :log)
    assert request_code(conn, t) |> json_response(503)
  end

  test "a provider failure is 503, and the attempt still counts", %{
    conn: conn,
    entry: e,
    token: t
  } do
    Application.put_env(:risime, :sms_mode, :notifylk)

    Req.Test.stub(
      RisiMe.NotifyLk,
      &Req.Test.json(&1, %{"status" => "error", "message" => "nope"})
    )

    ExUnit.CaptureLog.capture_log(fn ->
      assert %{"error" => %{"code" => "sms_unavailable"}} =
               request_code(conn, t) |> json_response(503)
    end)

    assert Repo.aggregate(from(c in PhoneChallenge, where: c.phone == ^e.phone), :count) == 1
  end

  test "dev-login sessions count as verified (not stored)", %{conn: conn} do
    %{token: dev, user: user} = logged_in_user()
    assert %{"user" => %{"phone_verified" => true}} = me(conn, dev)
    assert request_code(conn, dev) |> json_response(409)
    assert Repo.get!(User, user.id).phone_verified_for == nil
  end

  test "a phone change resets verification", %{conn: conn, entry: e, token: t} do
    request_code(conn, t) |> json_response(200)
    assert confirm(conn, t, receive_sms(e.phone)) |> json_response(200)

    Repo.update_all(from(u in User, where: u.phone == ^e.phone), set: [phone: "+94770009998"])

    Repo.update_all(from(a in RisiMe.Accounts.AllowlistEntry, where: a.phone == ^e.phone),
      set: [phone: "+94770009998"]
    )

    RisiMe.Auth.clear_cache()
    assert %{"user" => %{"phone_verified" => false}} = me(conn, t)
  end

  test "with PHONE_VERIFICATION off, users read as verified", %{conn: conn, token: t} do
    Application.put_env(:risime, :phone_verification, :off)
    assert %{"user" => %{"phone_verified" => true}} = me(conn, t)
  end
end
