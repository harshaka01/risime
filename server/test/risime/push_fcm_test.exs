defmodule RisiMe.PushFCMTest do
  @moduledoc "FCM HTTP v1 sender against Req.Test stubs, with a generated (fake) service account."
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  alias RisiMe.Push.FCM

  @push_token "fcm-device-token-never-logged"
  @access "ya29.access-token-never-logged"

  setup do
    jwk = JOSE.JWK.generate_key({:rsa, 2048})
    {_, pem} = JOSE.JWK.to_pem(jwk)

    path =
      Path.join(System.tmp_dir!(), "risime-test-fcm-#{System.unique_integer([:positive])}.json")

    File.write!(
      path,
      Jason.encode!(%{
        "type" => "service_account",
        "project_id" => "risime-test",
        "private_key_id" => "kid-1",
        "private_key" => pem,
        "client_email" => "push@risime-test.iam.gserviceaccount.com",
        "token_uri" => "https://oauth2.googleapis.com/token"
      })
    )

    Application.put_env(:risime, :fcm_service_account_file, path)
    GenServer.cast(FCM, :invalidate)
    :sys.get_state(FCM)

    {:ok, calls} = Agent.start_link(fn -> [] end)

    on_exit(fn ->
      File.rm(path)
      Application.delete_env(:risime, :fcm_service_account_file)
      GenServer.cast(FCM, :invalidate)
    end)

    %{jwk: jwk, calls: calls, pem: pem}
  end

  defp stub(calls, send_fun, token_body \\ %{"access_token" => @access, "expires_in" => 3599}) do
    Req.Test.stub(RisiMe.FCM, fn conn ->
      {:ok, raw, conn} = Plug.Conn.read_body(conn)
      auth = Plug.Conn.get_req_header(conn, "authorization")
      Agent.update(calls, &(&1 ++ [{conn.request_path, raw, auth}]))

      case conn.request_path do
        "/token" -> Req.Test.json(conn, token_body)
        "/v1/projects/risime-test/messages:send" -> send_fun.(conn)
      end
    end)

    Req.Test.allow(RisiMe.FCM, self(), Process.whereis(FCM))
  end

  defp ok(conn), do: Req.Test.json(conn, %{"name" => "projects/risime-test/messages/1"})

  defp error(conn, status, fcm_status, code \\ nil) do
    details =
      if code,
        do: [
          %{"@type" => "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode" => code}
        ],
        else: []

    conn
    |> Plug.Conn.put_status(status)
    |> Req.Test.json(%{
      "error" => %{"code" => status, "status" => fcm_status, "details" => details}
    })
  end

  test "exchanges a signed JWT for an access token, then sends exactly the data payload",
       %{calls: calls, jwk: jwk} do
    stub(calls, &ok/1)
    assert :ok = FCM.deliver(@push_token, RisiMe.Push.payload())

    [{"/token", form, []}, {"/v1/projects/risime-test/messages:send", body, auth}] =
      Agent.get(calls, & &1)

    # The JWT bearer grant, signed with the service account's key.
    form = URI.decode_query(form)
    assert form["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer"

    {true, %JOSE.JWT{fields: claims}, _} =
      JOSE.JWT.verify_strict(JOSE.JWK.to_public(jwk), ["RS256"], form["assertion"])

    assert claims["iss"] == "push@risime-test.iam.gserviceaccount.com"
    assert claims["scope"] == "https://www.googleapis.com/auth/firebase.messaging"
    assert claims["aud"] == "https://oauth2.googleapis.com/token"
    assert claims["exp"] - claims["iat"] == 3600

    assert auth == ["Bearer " <> @access]

    # No content: data is exactly the wake-up; no notification block.
    assert Jason.decode!(body) == %{
             "message" => %{
               "token" => @push_token,
               "data" => %{"type" => "inbox", "v" => "1"},
               "android" => %{"priority" => "high", "collapse_key" => "inbox", "ttl" => "3600s"}
             }
           }
  end

  test "the access token is cached, and refreshed shortly before expiry", %{calls: calls} do
    stub(calls, &ok/1)
    assert :ok = FCM.deliver(@push_token, RisiMe.Push.payload())
    assert :ok = FCM.deliver(@push_token, RisiMe.Push.payload())
    assert Enum.count(Agent.get(calls, & &1), &match?({"/token", _, _}, &1)) == 1

    # A token that expires within the 5 min margin is fetched again on next use.
    GenServer.cast(FCM, :invalidate)
    Agent.update(calls, fn _ -> [] end)
    stub(calls, &ok/1, %{"access_token" => @access, "expires_in" => 120})
    assert :ok = FCM.deliver(@push_token, RisiMe.Push.payload())
    assert :ok = FCM.deliver(@push_token, RisiMe.Push.payload())
    assert Enum.count(Agent.get(calls, & &1), &match?({"/token", _, _}, &1)) == 2
  end

  test "UNREGISTERED and INVALID_ARGUMENT mean unregistered; 5xx/429/401 are retryable; logs are redacted",
       %{calls: calls, pem: pem} do
    cases = [
      {fn c -> error(c, 404, "NOT_FOUND", "UNREGISTERED") end, {:error, :unregistered}},
      {fn c -> error(c, 400, "INVALID_ARGUMENT") end, {:error, :unregistered}},
      {fn c -> error(c, 503, "UNAVAILABLE") end, {:error, :retryable}},
      {fn c -> error(c, 429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED") end, {:error, :retryable}},
      {fn c -> error(c, 401, "UNAUTHENTICATED") end, {:error, :retryable}},
      {fn c -> error(c, 403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH") end, {:error, :failed}}
    ]

    log =
      capture_log(fn ->
        for {fun, expected} <- cases do
          stub(calls, fun)
          assert FCM.deliver(@push_token, RisiMe.Push.payload()) == expected
        end
      end)

    assert log =~ "UNREGISTERED"
    refute log =~ @push_token
    refute log =~ @access
    refute log =~ "PRIVATE KEY"
    refute log =~ String.slice(pem, 40, 40)
  end

  test "a missing or broken service account fails closed without logging its contents" do
    Application.put_env(:risime, :fcm_service_account_file, "/nonexistent/fcm.json")

    log =
      capture_log(fn -> assert {:error, _} = FCM.deliver(@push_token, RisiMe.Push.payload()) end)

    assert log =~ "FCM_SERVICE_ACCOUNT_FILE"
    refute log =~ "/nonexistent"
  end
end
