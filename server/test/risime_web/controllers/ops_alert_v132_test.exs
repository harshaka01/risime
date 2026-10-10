defmodule RisiMeWeb.OpsAlertV132Test do
  @moduledoc "v1.32 §32: POST /internal/ops-alert."
  use RisiMe.DataCase, async: false

  @moduletag capture_log: true

  import Phoenix.ConnTest
  import RisiMe.RisiHelpers, only: [own_risi_chat!: 1, restore_on_exit: 1]
  import RisiMe.CalendarHelpers, only: [posts: 0]

  @endpoint RisiMeWeb.Endpoint
  @token "test-ops-token-0123456789"
  @good %{"state" => "restarted", "check" => "local", "detail" => "timed out 3 times"}
  @dir Path.expand("../../../../contract/v1/examples", __DIR__)

  setup do
    restore_on_exit([:ops_alert, :risi_test_pid, :risi])
    RisiMe.MLSHelpers.with_attestation_key(%{})
    RisiMe.TabsHelpers.risi_on!()
    Application.put_env(:risime, :risi_test_pid, self())
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    config!(phones: [harsha.user.phone])
    %{user: harsha.user}
  end

  defp config!(opts) do
    base = [token: @token, phones: [], bucket_key: make_ref()]
    cur = Application.get_env(:risime, :ops_alert, [])
    Application.put_env(:risime, :ops_alert, Keyword.merge(base, Keyword.merge(cur, opts)))
  end

  defp call(body, headers \\ [{"authorization", "Bearer " <> @token}], ip \\ {127, 0, 0, 1}) do
    conn = build_conn() |> Map.put(:remote_ip, ip)
    conn = Enum.reduce(headers, conn, fn {k, v}, c -> Plug.Conn.put_req_header(c, k, v) end)

    conn
    |> Plug.Conn.put_req_header("content-type", "application/json")
    |> post("/internal/ops-alert", body)
  end

  test "401 without token, with a wrong token, through a proxy header, or off loopback" do
    assert call(@good, []).status == 401
    assert call(@good, [{"authorization", "Bearer nope"}]).status == 401
    assert call(@good, [{"authorization", "Basic " <> @token}]).status == 401

    for h <- ~w(x-forwarded-for forwarded x-real-ip) do
      assert call(@good, [{"authorization", "Bearer " <> @token}, {h, "1.2.3.4"}]).status == 401
    end

    assert call(@good, [{"authorization", "Bearer " <> @token}], {10, 0, 0, 5}).status == 401

    assert call(@good, [{"authorization", "Bearer " <> @token}], {0, 0, 0, 0, 0, 0, 0, 1}).status ==
             202
  end

  test "404 while OPS_ALERT_TOKEN is unset or empty" do
    config!(token: nil)
    assert call(@good).status == 404
    config!(token: "")
    assert call(@good).status == 404
  end

  test "422 on a bad state, check or detail" do
    assert call(%{@good | "state" => "boom"}).status == 422
    assert call(%{@good | "check" => "remote"}).status == 422
    assert call(%{@good | "detail" => 5}).status == 422
    assert call(%{"state" => "restarted"}).status == 422
  end

  test "202 sent: a rule-made ops_alert in the operator's Risi chat; detail truncated", ctx do
    rc = own_risi_chat!(ctx.user)
    conn = call(%{@good | "detail" => String.duplicate("x", 400)})
    assert json_response(conn, 202) == %{"sent" => 1, "held" => 0}

    assert [{^rc, body, risi}] = posts()
    assert body =~ "I restarted it and it is healthy again."
    assert risi["kind"] == "ops_alert" and risi["notify"] == [ctx.user.id]
    assert String.length(risi["detail"]) == 300
    assert risi["made_by"]["model"] == nil and risi["made_by"]["provider"] == "risime"
  end

  test "held when the operator has no active Risi chat, no user, or Risi is off", ctx do
    assert json_response(call(@good), 202) == %{"sent" => 0, "held" => 1}
    assert posts() == []

    own_risi_chat!(ctx.user)
    config!(phones: [ctx.user.phone, "+94700000000"])
    assert json_response(call(@good), 202) == %{"sent" => 1, "held" => 1}

    Application.put_env(:risime, :risi, false)
    assert json_response(call(@good), 202) == %{"sent" => 0, "held" => 2}
  end

  test "429 after 30 in an hour" do
    for _ <- 1..30, do: assert(call(@good).status == 202)
    conn = call(@good)
    assert json_response(conn, 429)["error"]["code"] == "rate_limited"
  end

  test "alert and resolved: any [a-z0-9_]{1,40} check, body texts, bad checks refused", ctx do
    own_risi_chat!(ctx.user)
    a = %{"state" => "alert", "check" => "health_down", "detail" => "down 2m"}
    assert json_response(call(a), 202) == %{"sent" => 1, "held" => 0}
    assert [{_, "Monitoring alert: health_down. down 2m", %{"state" => "alert"}}] = posts()

    r = %{"state" => "resolved", "check" => "p95_latency", "detail" => "ok"}
    assert json_response(call(r), 202) == %{"sent" => 1, "held" => 0}
    assert [{_, "Resolved: p95_latency. ok", %{"state" => "resolved"}}] = posts()

    for bad <- ["Health", "a-b", "", String.duplicate("a", 41), "local "] do
      assert call(%{a | "check" => bad}).status == 422
    end

    # watchdog states stay local|public
    assert call(%{@good | "check" => "health_down"}).status == 422
    assert RisiMe.OpsAlert.body("alert", "x_y", "d") == "Monitoring alert: x_y. d"
    assert RisiMe.OpsAlert.body("resolved", "x_y", "") == "Resolved: x_y."
  end

  test "envelope_risi_ops_alert.json: the built envelope has exactly its shape", ctx do
    ex = @dir |> Path.join("envelope_risi_ops_alert.json") |> File.read!() |> Jason.decode!()
    own_risi_chat!(ctx.user)

    detail = ex["risi"]["detail"]
    at = ~U[2026-10-10 06:30:41.000000Z]
    body = RisiMe.OpsAlert.body("restarted", detail)
    risi = RisiMe.OpsAlert.risi("restarted", "local", detail, ctx.user.id, at)
    built = %{"v" => 1, "type" => "text", "body" => body, "risi" => RisiMe.Agent.Out.risi(risi)}

    shape = fn
      m when is_map(m) -> m |> Map.keys() |> Enum.sort()
    end

    assert shape.(built) == shape.(ex)
    assert shape.(built["risi"]) == shape.(ex["risi"])
    assert shape.(built["risi"]["made_by"]) == shape.(ex["risi"]["made_by"])
    assert built["risi"]["made_by"]["model"] == nil
    assert built["risi"]["made_by"]["provider"] == "risime"
    assert built["risi"]["at"] == "2026-10-10T06:30:41.000Z"
    assert built["risi"]["call_ref"] == nil
    assert built["body"] =~ "RisiMe server was not answering on spark2. I restarted it"
    for k <- ~w(v kind state check detail), do: assert(built["risi"][k] == ex["risi"][k])
    assert is_binary(hd(ex["risi"]["notify"])) and is_binary(hd(built["risi"]["notify"]))
  end
end
