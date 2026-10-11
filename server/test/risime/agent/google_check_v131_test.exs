defmodule RisiMe.Agent.GoogleCheckV131Test do
  @moduledoc """
  v1.31 §31.4: the `risi_calendar_check` round trip to the Google device. The Google device is a
  test client that answers the `calendar_check` tool call (or doesn't, for `no_answer`).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [events: 2, api: 5]

  alias RisiMe.Agent.GoogleLink
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @put %{
    "connect" => true,
    "state" => "connected",
    "read_calendars" => 2,
    "write_calendar" => true,
    "mirror" => true
  }

  setup do
    ctx = calendar_world!()
    gcal_on!()
    restore_on_exit([:risi_tool_deadline_ms])
    # Harsha's Google phone (a second device of his) and his plain Risi phone `hd`.
    hg = gcal_device!(ctx.harsha.user)
    Map.put(ctx, :hg, hg)
  end

  defp token(ctx), do: ctx.harsha.token
  defp url, do: "/api/v1/risi/calendar/google"

  defp skill!(ctx, state) do
    change = %{"id" => "calendar", "state" => state, "client_permission" => "granted"}
    {200, _} = api(:patch, "/api/v1/risi/skills", token(ctx), %{"changes" => [change]}, ctx.hd)
  end

  defp link!(ctx, device, extra \\ %{}) do
    {200, _} = api(:put, url(), token(ctx), Map.merge(@put, extra), device)
  end

  defp check_llm! do
    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: %{"tool" => "final", "answer" => "Done.", "sources" => [], "next_steps" => []},
        else: %{
          "tool" => "risi_calendar_check",
          "args" => %{"from" => "2026-10-12T08:00:00Z", "to" => "2026-10-12T10:00:00Z"}
        }
    end)
  end

  defp ask!(ctx, device) do
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
      device
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    Task.async(fn -> perform_job(Job, job.args) end)
  end

  # Waits for `n` tool-call events.
  defp wait_calls(user_id, n, tries \\ 150) do
    calls = events(user_id, "risi_tool_call")

    cond do
      length(calls) >= n ->
        calls

      tries > 0 ->
        Process.sleep(20)
        wait_calls(user_id, n, tries - 1)

      true ->
        flunk("expected #{n} risi_tool_call events, got #{length(calls)}")
    end
  end

  defp post(ctx, call, device, status, result) do
    api(
      :post,
      "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
      token(ctx),
      %{"status" => status, "result" => result},
      device
    )
  end

  defp phone_result do
    %{
      "blocks" => [],
      "sources" => [
        %{
          "source" => "phone_provider",
          "calendars" => [%{"name" => "Gzzname", "account_type" => "com.google", "events" => 0}],
          "read_ok" => true,
          "reason" => nil
        }
      ],
      "connected_sources" => ["phone_provider"]
    }
  end

  defp google_result(blocks \\ []) do
    %{
      "blocks" => blocks,
      "sources" => [
        %{
          "source" => "google_api",
          "calendars" => [%{"ref" => "g4k2m7qa", "events" => length(blocks)}],
          "read_ok" => true,
          "reason" => nil
        }
      ],
      "connected_sources" => ["google_api"]
    }
  end

  defp busy(s, e), do: %{"start" => s, "end" => e, "busy" => true, "all_day" => false}

  # The risi_calendar_check result the model was given.
  defp model_result do
    reqs = llm_requests()

    for req <- reqs,
        %{"role" => "user", "content" => c} <- req["messages"],
        {:ok, %{"ok" => true, "result" => %{"source" => "risi_calendar"} = r}} <- [
          Jason.decode(c)
        ],
        do: r
  end

  test "a connected link: two parallel calls, the Google one routed to the Google device", ctx do
    skill!(ctx, "ask")
    link!(ctx, ctx.hg)
    check_llm!()
    task = ask!(ctx, ctx.hd)

    [_, _] = calls = wait_calls(ctx.h, 2)
    phone = Enum.find(calls, &(&1["data"]["args"]["sources"] == nil))
    google = Enum.find(calls, &(&1["data"]["args"]["sources"] == ["google_api"]))

    assert phone["data"]["to_devices"] == [ctx.hd]
    assert google["data"]["to_devices"] == [ctx.hg]
    # `device_id` stays the asking device.
    assert google["data"]["device_id"] == ctx.hd
    assert google["data"]["tool"] == "calendar_check"
    assert Enum.sort(Map.keys(google["data"]["args"])) == ~w(from sources to)

    # Only the device in to_devices may post.
    assert {404, _} = post(ctx, google, ctx.hd, "ok", google_result())
    assert {404, _} = post(ctx, phone, ctx.hg, "ok", phone_result())

    # A `name` or `account_type` in a google_api entry: 422.
    for extra <- [%{"name" => "Gzzname"}, %{"account_type" => "com.google"}] do
      bad =
        update_in(
          google_result(),
          ["sources", Access.at(0), "calendars", Access.at(0)],
          &Map.merge(&1, extra)
        )

      assert {422, _} = post(ctx, google, ctx.hg, "ok", bad)
    end

    # The result's sources must be exactly the sources asked for.
    assert {422, _} = post(ctx, google, ctx.hg, "ok", phone_result())
    # More than 10 entries, or a bad ref.
    many = for i <- 1..11, do: %{"ref" => "g#{i}", "events" => 0}

    assert {422, _} =
             post(
               ctx,
               google,
               ctx.hg,
               "ok",
               put_in(google_result(), ["sources", Access.at(0), "calendars"], many)
             )

    badref = [%{"ref" => "G 4!", "events" => 0}]

    assert {422, _} =
             post(
               ctx,
               google,
               ctx.hg,
               "ok",
               put_in(google_result(), ["sources", Access.at(0), "calendars"], badref)
             )

    assert {204, _} = post(ctx, phone, ctx.hd, "ok", phone_result())

    blocks = [busy("2026-10-12T08:30:00.000Z", "2026-10-12T09:30:00.000Z")]
    assert {204, _} = post(ctx, google, ctx.hg, "ok", google_result(blocks))
    assert :ok = Task.await(task)

    [r | _] = model_result()
    g = r["google"]

    assert g == %{
             "source" => "google_api",
             "read_ok" => true,
             "reason" => nil,
             "calendars" => 1,
             "blocks" => [
               %{
                 "start" => "2026-10-12T08:30:00.000Z",
                 "end" => "2026-10-12T09:30:00.000Z",
                 "all_day" => false,
                 "ref" => "c1"
               }
             ]
           }

    # No refs of calendars and no names reach the model.
    refute inspect(llm_requests()) =~ "g4k2m7qa"
    refute inspect(llm_requests()) =~ "Gzzname"
  end

  test "the Google phone never answers: no_answer after the deadline, the turn goes on", ctx do
    skill!(ctx, "ask")
    link!(ctx, ctx.hg)
    Application.put_env(:risime, :risi_tool_deadline_ms, 400)
    check_llm!()
    task = ask!(ctx, ctx.hd)

    calls = wait_calls(ctx.h, 2)
    phone = Enum.find(calls, &(&1["data"]["args"]["sources"] == nil))
    google = Enum.find(calls, &(&1["data"]["args"]["sources"] == ["google_api"]))
    assert {204, _} = post(ctx, phone, ctx.hd, "ok", phone_result())

    assert :ok = Task.await(task)
    [r | _] = model_result()

    assert %{"read_ok" => false, "reason" => "no_answer", "calendars" => 0, "blocks" => []} =
             r["google"]

    # A late result: 409 tool_call_expired.
    assert {409, %{"error" => %{"code" => "tool_call_expired"}}} =
             post(ctx, google, ctx.hg, "ok", google_result())
  end

  test "the asker is also the Google device: one call with both sources", ctx do
    {200, _} =
      api(
        :patch,
        "/api/v1/risi/skills",
        token(ctx),
        %{
          "changes" => [%{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}]
        },
        ctx.hg
      )

    link!(ctx, ctx.hg)
    check_llm!()
    task = ask!(ctx, ctx.hg)

    [call] = wait_calls(ctx.h, 1)
    assert call["data"]["args"]["sources"] == ["phone_provider", "google_api"]
    assert call["data"]["to_devices"] == [ctx.hg]
    assert call["data"]["device_id"] == ctx.hg

    both = %{
      "blocks" => [],
      "sources" => phone_result()["sources"] ++ google_result()["sources"],
      "connected_sources" => ["phone_provider", "google_api"]
    }

    assert {204, _} = post(ctx, call, ctx.hg, "ok", both)
    assert :ok = Task.await(task)
    [r | _] = model_result()
    assert %{"read_ok" => true, "calendars" => 1} = r["google"]
    assert r["phone"]["sources"] |> Enum.map(& &1["source"]) == ["phone_provider"]
  end

  test "reauth_needed: no call, the source is reauth_needed", ctx do
    skill!(ctx, "ask")
    link!(ctx, ctx.hg)
    link!(ctx, ctx.hg, %{"connect" => false, "state" => "reauth_needed"})
    check_llm!()
    task = ask!(ctx, ctx.hd)

    # Only the phone check goes out.
    [phone] = wait_calls(ctx.h, 1)
    assert phone["data"]["args"]["sources"] == nil
    assert {204, _} = post(ctx, phone, ctx.hd, "ok", phone_result())
    assert :ok = Task.await(task)
    assert length(events(ctx.h, "risi_tool_call")) <= 1

    [r | _] = model_result()
    assert %{"read_ok" => false, "reason" => "reauth_needed", "calendars" => 0} = r["google"]
  end

  test "Calendar skill off: no call, the source is paused", ctx do
    link!(ctx, ctx.hg)
    skill!(ctx, "off")
    check_llm!()
    task = ask!(ctx, ctx.hd)
    assert :ok = Task.await(task)
    assert events(ctx.h, "risi_tool_call") == []
    [r | _] = model_result()
    assert %{"read_ok" => false, "reason" => "paused"} = r["google"]
  end

  test "switch off, or no link: v1.30 (no google key, no Google call)", ctx do
    skill!(ctx, "ask")
    # No link.
    check_llm!()
    task = ask!(ctx, ctx.hd)
    [phone] = wait_calls(ctx.h, 1)
    assert {204, _} = post(ctx, phone, ctx.hd, "ok", phone_result())
    assert :ok = Task.await(task)
    [r | _] = model_result()
    refute Map.has_key?(r, "google")

    # A link, but the switch is off.
    link!(ctx, ctx.hg)
    Application.put_env(:risime, :risi_gcal, false)
    task = ask!(ctx, ctx.hd)
    [call] = wait_calls(ctx.h, 1)
    assert call["data"]["args"]["sources"] == nil
    assert {204, _} = post(ctx, call, ctx.hd, "ok", phone_result())
    assert :ok = Task.await(task)
    assert GoogleLink.get(ctx.h)
  end
end
