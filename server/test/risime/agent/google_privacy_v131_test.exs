defmodule RisiMe.Agent.GooglePrivacyV131Test do
  @moduledoc """
  v1.31 §31.10: the canary. A `google_api` tool result whose ref is the canary `gzzcanary1`, and
  a canary token in a request header that must be ignored, never reach logs, `risi_turn_steps`,
  the learning log or `inbox_events`; `risi_gcal_links` holds ids, states and counts only.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import ExUnit.CaptureLog
  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [events: 2]

  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @canary "gzzcanary1"
  @token "ya29.gzzcanarytoken"

  setup do
    ctx = calendar_world!()
    gcal_on!()
    Logger.configure(level: :debug)
    on_exit(fn -> Logger.configure(level: :warning) end)
    Map.put(ctx, :hg, gcal_device!(ctx.harsha.user))
  end

  # The request with the canary token riding in headers the server must ignore.
  defp api_with_token(method, path, token, body, device) do
    conn =
      Phoenix.ConnTest.build_conn()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
      |> Plug.Conn.put_req_header("x-device-id", device)
      |> Plug.Conn.put_req_header("x-google-token", @token)
      |> Plug.Conn.put_req_header("x-goog-authorization", "Bearer " <> @token)

    conn = Phoenix.ConnTest.dispatch(conn, RisiMeWeb.Endpoint, method, path, body)
    {conn.status, if(conn.resp_body == "", do: nil, else: Jason.decode!(conn.resp_body))}
  end

  test "the canary ref and token reach no log, step, learning-log row or inbox event", ctx do
    log =
      capture_log([level: :debug], fn ->
        change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

        {200, _} =
          api_with_token(
            :patch,
            "/api/v1/risi/skills",
            ctx.harsha.token,
            %{"changes" => [change]},
            ctx.hd
          )

        put = %{
          "connect" => true,
          "state" => "connected",
          "read_calendars" => 1,
          "write_calendar" => true,
          "mirror" => true
        }

        {200, _} =
          api_with_token(:put, "/api/v1/risi/calendar/google", ctx.harsha.token, put, ctx.hg)

        fake_llm!(fn _name, body ->
          if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
            do: %{
              "tool" => "final",
              "answer" => "Nothing on.",
              "sources" => [],
              "next_steps" => []
            },
            else: %{
              "tool" => "risi_calendar_check",
              "args" => %{"from" => "2026-10-12T08:00:00Z", "to" => "2026-10-12T10:00:00Z"}
            }
        end)

        rid = Ecto.UUID.generate()

        envelope!(
          ctx.hrc,
          ctx.harsha.user,
          %{
            "v" => 1,
            "type" => "risi_request",
            "request_id" => rid,
            "action" => "ask",
            "text" => "Monday 2pm ok for the call?"
          },
          ctx.hd
        )

        [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
        task = Task.async(fn -> perform_job(Job, job.args) end)

        calls =
          Enum.find_value(1..150, fn _ ->
            case events(ctx.h, "risi_tool_call") do
              [_, _] = cs -> cs
              _ -> Process.sleep(20) && nil
            end
          end)

        send(self(), {:turn, hd(calls)["data"]["turn_id"]})

        for c <- calls do
          result =
            if c["data"]["args"]["sources"] == ["google_api"],
              do: %{
                "blocks" => [],
                "sources" => [
                  %{
                    "source" => "google_api",
                    "calendars" => [%{"ref" => @canary, "events" => 0}],
                    "read_ok" => true,
                    "reason" => nil
                  }
                ],
                "connected_sources" => ["google_api"]
              },
              else: %{
                "blocks" => [],
                "sources" => [
                  %{
                    "source" => "phone_provider",
                    "calendars" => [],
                    "read_ok" => false,
                    "reason" => "no_calendars"
                  }
                ],
                "connected_sources" => []
              }

          dev = if c["data"]["args"]["sources"] == ["google_api"], do: ctx.hg, else: ctx.hd

          {204, _} =
            api_with_token(
              :post,
              "/api/v1/risi/tool_calls/#{c["data"]["tool_call_id"]}/result",
              ctx.harsha.token,
              %{"status" => "ok", "result" => result},
              dev
            )
        end

        assert :ok = Task.await(task)
      end)

    refute log =~ @canary
    refute log =~ @token

    assert_received {:turn, turn_id}
    steps = RisiMe.Agent.TurnSteps.list(turn_id)
    assert steps != []
    refute inspect(steps, limit: :infinity) =~ @canary

    learning =
      for b <- 0..(RisiMe.Agent.LearningLog.buckets() - 1),
          day <- [Date.utc_today(), Date.add(Date.utc_today(), -1)],
          do: RisiMe.Agent.LearningLog.list_by_day(day, b)

    refute inspect(learning, limit: :infinity, printable_limit: :infinity) =~ @canary
    refute inspect(learning, limit: :infinity, printable_limit: :infinity) =~ @token

    all_events = events(ctx.h, nil) ++ events(ctx.s, nil)
    refute inspect(all_events, limit: :infinity, printable_limit: :infinity) =~ @canary
    refute inspect(all_events, limit: :infinity, printable_limit: :infinity) =~ @token
  end

  test "risi_gcal_links holds ids, states and counts only", ctx do
    put = %{
      "connect" => true,
      "state" => "connected",
      "read_calendars" => 3,
      "write_calendar" => true,
      "mirror" => true
    }

    {200, _} = api_with_token(:put, "/api/v1/risi/calendar/google", ctx.harsha.token, put, ctx.hg)

    {:ok, %{rows: [[json]]}} =
      Ecto.Adapters.SQL.query(Repo, "SELECT row_to_json(l)::text FROM risi_gcal_links l", [])

    row = Jason.decode!(json)
    assert row["user_id"] == ctx.h and row["device_id"] == ctx.hg
    assert row["state"] == "connected" and row["read_calendars"] == 3
    refute json =~ @token
    refute json =~ @canary

    assert Enum.all?(
             Map.values(row),
             &(is_nil(&1) or is_boolean(&1) or is_integer(&1) or is_binary(&1))
           )
  end
end
