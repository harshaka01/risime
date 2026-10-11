defmodule RisiMe.Contract.ExamplesV131Test do
  @moduledoc """
  Contract v1.31 §31 (Google Calendar link): every §31 example in `contract/v1/examples/` is the
  server's real output (or the exact body the server accepted), checked **exactly**: same keys,
  same values, except ids (any UUID) and wall-clock timestamps (`at`, `server_ts`, `expires_at`,
  `connected_at`, `updated_at`, `reported_at`, `state_changed_at`), which only have to be of the
  same kind. Risi's clock is fixed (Fri 9 Oct 2026, Colombo).

  Root regenerates the files from this flow with
  `RISIME_WRITE_EXAMPLES=1 mix test test/contract/examples_v131_test.exs` (integration only).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @write System.get_env("RISIME_WRITE_EXAMPLES") == "1"

  @files ~w(auth_config_v131.json device_put_google_calendar.json
            envelope_risi_answer_calendar_google_no_answer.json
            envelope_risi_answer_calendar_sources_google.json envelope_risi_google_reconnect.json
            error_not_google_device.json event_google_calendar_link.json
            event_risi_tool_call_calendar_check_google.json risi_google_link_put.json
            risi_google_link_reply.json risi_skills_reply_v131.json
            risi_tool_result_calendar_check_google.json
            risi_tool_result_calendar_check_google_reauth.json)

  @doc false
  def files, do: @files

  @url "/api/v1/risi/calendar/google"

  setup do
    ctx = calendar_world!()
    gcal_on!()
    restore_on_exit([:risi_tool_deadline_ms, :risi_notes])
    Application.put_env(:risime, :risi_notes, true)
    hg = gcal_device!(ctx.harsha.user)

    # The Google phone registered as "Pixel 8" (§1.2).
    token = Repo.get_by!(RisiMe.Accounts.UserToken, user_id: ctx.h)
    token |> Ecto.Changeset.change(device_name: "Pixel 8") |> Repo.update!()

    Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^hg),
      set: [user_token_id: token.id]
    )

    Map.put(ctx, :hg, hg)
  end

  ## Exact comparison

  @any_uuid ~r/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/
  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$/
  @clock ~w(server_ts at expires_at connected_at updated_at reported_at state_changed_at)

  defp norm(m, _key) when is_map(m), do: Map.new(m, fn {k, v} -> {k, norm(v, k)} end)
  defp norm(l, key) when is_list(l), do: Enum.map(l, &norm(&1, key))

  defp norm(s, key) when is_binary(s) do
    s = Regex.replace(@any_uuid, s, "<id>")
    if key in @clock and s =~ @ts, do: "<ts>", else: s
  end

  defp norm(v, _), do: v

  defp wire(term), do: term |> Jason.encode!() |> Jason.decode!()

  defp check!(name, real) do
    real = wire(real)
    assert name in @files
    path = Path.join(@dir, name)

    if @write do
      File.write!(path, Jason.encode!(real) <> "\n")
    else
      ex = path |> File.read!() |> Jason.decode!()

      assert norm(ex, nil) == norm(real, nil),
             "#{name} differs from the server's real output:\n#{Jason.encode!(norm(real, nil))}"
    end

    real
  end

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp envelope({_conv, body, risi}),
    do: %{"v" => 1, "type" => "text", "body" => body, "risi" => risi}

  defp answer(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)

  defp config do
    Phoenix.ConnTest.build_conn()
    |> Phoenix.ConnTest.dispatch(RisiMeWeb.Endpoint, :get, "/api/v1/auth/config")
    |> Map.fetch!(:resp_body)
    |> Jason.decode!()
  end

  ## Flow helpers

  # The asking phone's own calendar permission is denied: the phone is not consulted
  # ("Phone calendar (not connected)").
  defp phone_denied!(ctx) do
    change = %{"id" => "calendar", "client_permission" => "denied"}

    {200, _} =
      api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, ctx.hd)
  end

  defp skill_patch!(ctx, dev) do
    change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

    {200, body} =
      api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, dev)

    body
  end

  defp ask!(ctx) do
    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: %{
          "tool" => "final",
          "answer" => "You're free then.",
          "sources" => [],
          "next_steps" => []
        },
        else: %{
          "tool" => "risi_calendar_check",
          "args" => %{"from" => "2026-10-12T02:30:00Z", "to" => "2026-10-12T14:30:00Z"}
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
    Task.async(fn -> perform_job(Job, job.args) end)
  end

  defp wait_call(user_id, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      [call | _] ->
        call

      [] when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, tries - 1)

      _ ->
        flunk("no risi_tool_call event")
    end
  end

  defp post_result(ctx, call, result) do
    {204, _} =
      api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => result},
        ctx.hg
      )
  end

  ## The examples

  test "§31.1: /auth/config and the device capability", ctx do
    ex = example("auth_config_v131.json")
    real = config()
    assert real["google_calendar"] == "on" and ex["google_calendar"] == "on"

    for k <- ~w(risi_events risi_ledger risi_notes risi_skills risi_tools),
        do: assert(real[k] == ex[k])

    put =
      check!("device_put_google_calendar.json", %{
        "platform" => "android",
        "push_token" => nil,
        "app_version" => "0.3.0-nightly.49",
        "mls" => %{
          "signature_key" => "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
          "capabilities" =>
            ~w(groups member_devices images deletes calls history_share video group_calls
               call_switch screen_share tabs risi_tools risi_skills risi_ledger risi_events
               risi_notes google_calendar)
        }
      })

    dev = Ecto.UUID.generate()
    {200, _} = api(:put, "/api/v1/me/devices/#{dev}", ctx.harsha.token, put, nil)
    assert RisiMe.Devices.google_calendar_device?(ctx.h, dev)
  end

  test "§31.3: the link, its event and the 409", ctx do
    skill_patch!(ctx, ctx.hg)
    body = check!("risi_google_link_put.json", example("risi_google_link_put.json"))
    {200, _} = api(:put, @url, ctx.harsha.token, body, ctx.hg)

    {200, reply} = api(:get, @url, ctx.harsha.token, nil, ctx.hg)
    check!("risi_google_link_reply.json", reply)

    other = gcal_device!(ctx.harsha.user)
    {409, err} = api(:put, @url, ctx.harsha.token, %{body | "connect" => false}, other)
    check!("error_not_google_device.json", err)

    {204, _} = api(:delete, @url, ctx.harsha.token, nil, other)
    ev = events(ctx.h, "google_calendar_link") |> List.last()
    check!("event_google_calendar_link.json", ev)
  end

  test "§26.1 amended: the Calendar skill for a google_calendar device", ctx do
    check!("risi_skills_reply_v131.json", skill_patch!(ctx, ctx.hg))
  end

  test "§31.4/§31.5: the routed check, both results and the answers", ctx do
    skill_patch!(ctx, ctx.hg)
    phone_denied!(ctx)
    body = example("risi_google_link_put.json")
    {200, _} = api(:put, @url, ctx.harsha.token, body, ctx.hg)

    # Read: two calendars, one busy block.
    task = ask!(ctx)
    call = wait_call(ctx.h)
    check!("event_risi_tool_call_calendar_check_google.json", call)

    result =
      check!(
        "risi_tool_result_calendar_check_google.json",
        example("risi_tool_result_calendar_check_google.json")["result"]
        |> then(&%{"status" => "ok", "result" => &1})
      )

    post_result(ctx, call, result["result"])
    assert :ok = Task.await(task)
    check!("envelope_risi_answer_calendar_sources_google.json", envelope(answer(posts())))

    # Never answers: no_answer.
    Application.put_env(:risime, :risi_tool_deadline_ms, 300)
    task = ask!(ctx)
    assert :ok = Task.await(task)
    # v1.35 §34.3 adds the server's "Ask me again" chip to a no-read answer (this v1.31 example
    # predates it); everything else is compared exactly.
    {conv, body, a} = answer(posts())

    assert a["next_actions"] == [
             %{
               "label" => "Ask me again",
               "action" => "ask",
               "text" => "Monday 2pm ok for the call?"
             }
           ]

    check!(
      "envelope_risi_answer_calendar_google_no_answer.json",
      envelope({conv, body, Map.delete(a, "next_actions")})
    )
  end

  test "§31.4: reauth_needed result; §31.8: the reconnect card", ctx do
    skill_patch!(ctx, ctx.hg)
    phone_denied!(ctx)
    body = example("risi_google_link_put.json")
    {200, _} = api(:put, @url, ctx.harsha.token, body, ctx.hg)

    ex = example("risi_tool_result_calendar_check_google_reauth.json")
    check!("risi_tool_result_calendar_check_google_reauth.json", ex)

    # The server accepts it (a Google call is open) ...
    task = ask!(ctx)
    call = wait_call(ctx.h)
    post_result(ctx, call, ex["result"])
    assert :ok = Task.await(task)
    posts()

    # ... and the phone PUTs reauth_needed: one card.
    {200, _} =
      api(
        :put,
        @url,
        ctx.harsha.token,
        %{body | "connect" => false, "state" => "reauth_needed"},
        ctx.hg
      )

    [card] = for {_, _, %{"kind" => "google_reconnect"}} = p <- posts(), do: p
    check!("envelope_risi_google_reconnect.json", envelope(card))
  end
end
