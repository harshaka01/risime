defmodule RisiMe.Contract.ExamplesV135Test do
  @moduledoc """
  Contract v1.35 §34 (Risi P0 2026-10-11): one test for each of the 10 new example files.
  Server-produced envelopes are compared exactly against what the server really posts (ids and
  clock times normalised; the never-sent `local_search: null` of the examples dropped);
  `risi_items_reply.json` and the Ask-again envelopes, whose data can't be replayed 1:1, are
  checked field by field.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [events: 2]

  alias RisiMe.Agent.{RisiItems, Writes}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(envelope_risi_answer_schedule_read.json envelope_risi_error_superseded.json
           envelope_risi_confirm_update_superseded.json envelope_risi_error_ask_again.json
           envelope_risi_answer_ask_again.json risi_items_reply.json
           risi_items_patch_reminder.json envelope_risi_answer_risi_items.json
           envelope_risi_answer_name_clarify.json device_put_risi_items.json)

  setup do
    ctx = calendar_world!()
    shirazi = RisiMe.Fixtures.logged_in_user(display_name: "Shirazi")
    RisiMe.Fixtures.befriend!(ctx.harsha.user, shirazi.user)
    Map.put(ctx, :shirazi, shirazi)
  end

  ## Exact comparison

  @any_uuid ~r/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/
  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$/
  @clock ~w(at created_at)

  defp norm(m, _key) when is_map(m), do: Map.new(m, fn {k, v} -> {k, norm(v, k)} end)
  defp norm(l, key) when is_list(l), do: Enum.map(l, &norm(&1, key))

  defp norm(s, key) when is_binary(s) do
    s = Regex.replace(@any_uuid, s, "<id>")
    if key in @clock and s =~ @ts, do: "<ts>", else: s
  end

  defp norm(v, _), do: v

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp drop_local_search(%{"risi" => r} = ex),
    do: %{ex | "risi" => if(r["local_search"] == nil, do: Map.delete(r, "local_search"), else: r)}

  defp check!(name, {_conv, body, risi}) do
    real = %{"v" => 1, "type" => "text", "body" => body, "risi" => risi}
    real = real |> Jason.encode!() |> Jason.decode!()
    ex = name |> example() |> drop_local_search()

    assert norm(ex, nil) == norm(real, nil),
           "#{name} differs from the server's real output:\n#{Jason.encode!(norm(real, nil))}"
  end

  ## Helpers

  defp final(answer),
    do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}

  defp request!(ctx, text) do
    rid = Ecto.UUID.generate()

    envelope!(
      ctx.hrc,
      ctx.harsha.user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    job
  end

  defp ask!(ctx, text), do: assert(:ok = perform_job(Job, request!(ctx, text).args))

  defp act!(ctx, action, target) do
    id =
      envelope!(
        ctx.hrc,
        ctx.harsha.user,
        %{"v" => 1, "type" => "risi_action", "target" => target, "action" => action},
        ctx.hd
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    assert :ok = perform_job(Job, job.args)
  end

  defp kind(ps, k), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == k end)

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

  defp calendar_skill!(ctx) do
    change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

    {200, _} =
      RisiMe.GroupHelpers.api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{"changes" => [change]},
        ctx.hd
      )
  end

  defp card!(ctx) do
    fake_llm!(fn _name, body ->
      q = Enum.find(body["messages"], &(&1["role"] == "user"))["content"]

      if q =~ "add dentist",
        do:
          Map.put(final("Dentist."), "draft", %{
            "kind" => "event",
            "title" => "Dentist",
            "date" => "Friday 16 Oct",
            "time" => "10am"
          }),
        else: final("Hi.")
    end)

    ask!(ctx, "add dentist Friday 16 Oct 10am")
    {_, _, card} = kind(posts(), "confirm")
    card
  end

  ## The examples

  test "envelope_risi_answer_schedule_read.json: the forced read, Risi's items, local_events",
       ctx do
    calendar_skill!(ctx)

    {201, _} =
      as(ctx, :h, :post, "/api/v1/risi/calendar/events", %{
        "client_event_id" => Ecto.UUID.generate(),
        "title" => "Interview with Shenika",
        "start" => "2026-10-12T04:30:00.000Z",
        "end" => "2026-10-12T05:30:00.000Z",
        "all_day" => false,
        "tz" => "Asia/Colombo"
      })

    :ok =
      RisiItems.record_phone_event(
        %Writes.Write{write_id: Ecto.UUID.generate(), user_id: ctx.h},
        %{
          "title" => "Call with Kamal",
          "wire" => %{
            "start" => "2026-10-12T08:30:00.000Z",
            "end" => "2026-10-12T09:30:00.000Z",
            "all_day" => false
          }
        },
        ctx.hd,
        %{
          "event_id" => "4711",
          "verified" => true,
          "calendar" => %{"name" => "Work", "account" => "Google"}
        }
      )

    posts()
    fake_llm!(fn _name, _body -> final("You have two things on Monday.") end)
    job = request!(ctx, "what appointments do I have on Monday")
    task = Task.async(fn -> perform_job(Job, job.args) end)
    call = wait_call(ctx.h)
    assert call["data"]["tool"] == "calendar_check"

    assert call["data"]["args"] == %{
             "from" => "2026-10-11T18:30:00.000Z",
             "to" => "2026-10-12T18:30:00.000Z"
           }

    result = %{
      "blocks" => [
        %{
          "start" => "2026-10-12T08:30:00.000Z",
          "end" => "2026-10-12T09:30:00.000Z",
          "busy" => true,
          "all_day" => false
        }
      ],
      "sources" => [
        %{
          "source" => "phone_provider",
          "calendars" => [
            %{"name" => "Work (Google)", "account_type" => "com.google", "events" => 1}
          ],
          "read_ok" => true,
          "reason" => nil
        }
      ],
      "connected_sources" => ["phone_provider"]
    }

    {204, _} =
      RisiMe.GroupHelpers.api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => result},
        ctx.hd
      )

    assert :ok = Task.await(task)
    check!("envelope_risi_answer_schedule_read.json", kind(posts(), "answer"))
  end

  test "envelope_risi_error_superseded.json: confirming a superseded card", ctx do
    card = card!(ctx)
    ask!(ctx, "hello")
    posts()
    act!(ctx, "confirm_write", card["write_id"])
    assert [p] = posts()
    check!("envelope_risi_error_superseded.json", p)
  end

  test "envelope_risi_confirm_update_superseded.json: a risi_items device learns it", ctx do
    RisiMe.TabsHelpers.tabs_device!(ctx.harsha.user,
      caps:
        ~w(groups member_devices tabs risi_tools risi_skills risi_ledger risi_events risi_items)
    )

    card!(ctx)
    ask!(ctx, "hello")
    check!("envelope_risi_confirm_update_superseded.json", kind(posts(), "confirm_update"))
  end

  test "envelope_risi_answer_name_clarify.json: Did you mean Shirazi?", ctx do
    fake_llm!(fn _n, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: final("?"),
        else: %{
          "tool" => "risi_calendar_add",
          "args" => %{"title" => "Meeting with Shutazi", "start" => "2026-10-13T04:30:00Z"}
        }
    end)

    ask!(ctx, "add a meeting with Shutazi Tuesday 10am")
    check!("envelope_risi_answer_name_clarify.json", kind(posts(), "answer"))
  end

  test "envelope_risi_answer_risi_items.json: the shape and the line format", ctx do
    ex = example("envelope_risi_answer_risi_items.json")

    :ok =
      RisiItems.record_phone_event(
        %Writes.Write{write_id: Ecto.UUID.generate(), user_id: ctx.h},
        %{
          "title" => "Call with Kamal",
          "wire" => %{
            "start" => "2026-10-12T08:30:00.000Z",
            "end" => "2026-10-12T09:30:00.000Z",
            "all_day" => false
          }
        },
        ctx.hd,
        %{"event_id" => "4711", "verified" => true, "calendar" => %{"name" => "Work"}}
      )

    fake_llm!(fn _n, _b -> final("never used") end)
    ask!(ctx, "what have you set up for me?")
    {_, body, a} = kind(posts(), "answer")
    assert llm_requests() == []

    assert body ==
             "Here's what I've set up for you:\n" <>
               "- Mon 12 Oct, 14:00–15:00 · Call with Kamal (added by Risi to Work)"

    assert ex["body"] =~ "- Mon 12 Oct, 14:00–15:00 · Call with Kamal (added to Work)"
    keys = Map.keys(ex["risi"]) -- ["local_search"]
    assert Enum.sort(Map.keys(a) -- ["v"]) == Enum.sort(keys -- ["v"])
    assert a["steps"] == ex["risi"]["steps"]

    assert [%{"type" => "risi_item", "kind" => "phone_event_added", "item_id" => _}] =
             a["sources"]

    assert a["made_by"]["model"] == nil and a["call_ref"] == nil and a["next_actions"] == []
  end

  test "envelope_risi_answer_ask_again.json / envelope_risi_error_ask_again.json: the chip" do
    for name <- ~w(envelope_risi_answer_ask_again.json envelope_risi_error_ask_again.json) do
      ex = example(name)

      assert %{"label" => "Ask me again", "action" => "ask", "text" => t} =
               hd(ex["risi"]["next_actions"])

      assert t == "Check my calendar for Monday, October 12th"
    end
  end

  test "risi_items_reply.json: every field of every kind", ctx do
    ex = example("risi_items_reply.json")

    :ok =
      RisiItems.record_phone_event(
        %Writes.Write{write_id: Ecto.UUID.generate(), user_id: ctx.h},
        %{
          "title" => "Call with Kamal",
          "wire" => %{
            "start" => "2026-10-12T08:30:00.000Z",
            "end" => "2026-10-12T09:30:00.000Z",
            "all_day" => false
          }
        },
        ctx.hd,
        %{
          "event_id" => "4711",
          "verified" => true,
          "calendar" => %{"name" => "Work", "account" => "Google"}
        }
      )

    {200, %{"items" => [real]}} = as(ctx, :h, :get, "/api/v1/risi/items")
    exp = Enum.find(ex["items"], &(&1["kind"] == "phone_event_added"))

    assert norm(Map.drop(real, ["id", "ref", "created_at"]), nil) ==
             norm(Map.drop(exp, ["id", "ref", "created_at"]), nil)

    assert Enum.sort(Map.keys(real["ref"])) == Enum.sort(Map.keys(exp["ref"]))

    for item <- ex["items"] do
      assert Enum.sort(Map.keys(item)) == Enum.sort(Map.keys(real))
      assert item["kind"] in RisiItems.kinds()
    end

    # Another device sees no actions on a phone row; DELETE from it is 403.
    other = calendar_device!(ctx.harsha.user)

    {200, %{"items" => [o]}} =
      RisiMe.GroupHelpers.api(:get, "/api/v1/risi/items", ctx.harsha.token, nil, other)

    assert o["actions"] == []

    assert {403, _} =
             RisiMe.GroupHelpers.api(
               :delete,
               "/api/v1/risi/items/#{real["id"]}",
               ctx.harsha.token,
               nil,
               other
             )

    assert {204, _} = as(ctx, :h, :delete, "/api/v1/risi/items/#{real["id"]}")
    assert {200, %{"items" => []}} = as(ctx, :h, :get, "/api/v1/risi/items")
  end

  test "risi_items_patch_reminder.json: PATCH a reminder", ctx do
    body = example("risi_items_patch_reminder.json")
    {:ok, key} = RisiMe.Agent.Seal.data()
    id = Ecto.UUID.generate()
    now = DateTime.utc_now()

    RisiMe.Repo.insert!(%RisiMe.Agent.Reminders{
      reminder_id: id,
      owner_id: ctx.h,
      conversation_id: ctx.hrc,
      request_conversation_id: ctx.hrc,
      due_at: ~U[2026-10-13 03:30:00.000000Z],
      text: RisiMe.Agent.Seal.seal(key, "risi_reminders:" <> id, "Pay the bill"),
      participants: [ctx.h],
      state: "pending",
      inserted_at: now,
      updated_at: now
    })

    RisiItems.record_reminder(ctx.h, id, ~U[2026-10-13 03:30:00.000000Z], ctx.hrc)
    {200, %{"items" => [item]}} = as(ctx, :h, :get, "/api/v1/risi/items")
    assert item["kind"] == "reminder" and item["title"] == "Pay the bill"

    {200, %{"item" => patched}} = as(ctx, :h, :patch, "/api/v1/risi/items/#{item["id"]}", body)
    assert patched["title"] == body["text"] and patched["start"] == body["at"]
    assert {422, _} = as(ctx, :h, :patch, "/api/v1/risi/items/#{item["id"]}", %{"x" => 1})
  end

  test "device_put_risi_items.json keeps risi_items (with risi_tools)", ctx do
    ex = example("device_put_risi_items.json")
    dev = Ecto.UUID.generate()
    {200, _} = RisiMe.GroupHelpers.api(:put, "/api/v1/me/devices/#{dev}", ctx.harsha.token, ex)
    d = RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: dev)
    assert "risi_items" in d.capabilities

    caps = ex["mls"]["capabilities"] -- ["risi_tools"]
    dev2 = Ecto.UUID.generate()

    {200, _} =
      RisiMe.GroupHelpers.api(
        :put,
        "/api/v1/me/devices/#{dev2}",
        ctx.harsha.token,
        put_in(ex, ["mls", "capabilities"], caps)
      )

    refute "risi_items" in RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: dev2).capabilities
  end
end
