defmodule RisiMe.Contract.ExamplesV132Test do
  @moduledoc """
  Contract v1.32 §29.7 `sync_off`: the example `risi_tool_result_calendar_check_sync_off.json` is
  the exact body the server accepts for the phone `calendar_check` result, and the answer says
  that sync is off.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(risi_tool_result_calendar_check_sync_off.json risi_tool_result_calendar_add_v132.json
         risi_tool_result_calendar_add_error_v132.json)

  setup do
    calendar_world!()
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

  test "risi_tool_result_calendar_check_sync_off.json is accepted; the answer says sync is off",
       ctx do
    ex = @dir |> Path.join("risi_tool_result_calendar_check_sync_off.json") |> File.read!()
    ex = Jason.decode!(ex)
    assert ex["status"] == "ok"

    assert [%{"source" => "phone_provider", "reason" => "sync_off", "read_ok" => false} | _] =
             ex["result"]["sources"]

    change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

    {200, _} =
      api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, ctx.hd)

    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: %{"tool" => "final", "answer" => "You are free.", "sources" => [], "next_steps" => []},
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
        "text" => "Am I free Monday 2pm?"
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    task = Task.async(fn -> perform_job(Job, job.args) end)
    call = wait_call(ctx.h)

    assert {204, _} =
             api(
               :post,
               "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
               ctx.harsha.token,
               ex,
               ctx.hd
             )

    assert :ok = Task.await(task)

    {_, body, _} = Enum.find(posts(), fn {_, _, r} -> r["kind"] == "answer" end)
    assert body =~ "Not checked: Phone calendar (sync is off)"
  end

  defp example!(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  test "the calendar_add result examples are accepted exactly; a bare event_id still parses" do
    alias RisiMe.Agent.ToolCalls
    ok = example!("risi_tool_result_calendar_add_v132.json")

    assert {:ok, "ok", %{"event_id" => "4711", "verified" => true}} =
             ToolCalls.parse("calendar_add", ok)

    err = example!("risi_tool_result_calendar_add_error_v132.json")
    assert {:ok, "error", %{"code" => "verify_failed"}} = ToolCalls.parse("calendar_add", err)

    # verified must be true; an unknown code or a long detail is refused.
    assert {:error, :bad_request} =
             ToolCalls.parse("calendar_add", %{
               "status" => "ok",
               "result" => %{"event_id" => "1", "verified" => false}
             })

    assert {:error, :bad_request} =
             ToolCalls.parse("calendar_add", %{"status" => "error", "code" => "nope"})

    assert {:error, :bad_request} =
             ToolCalls.parse("calendar_add", %{
               "status" => "error",
               "code" => "insert_failed",
               "detail" => String.duplicate("x", 201)
             })

    assert {:ok, "ok", %{"event_id" => "1"}} =
             ToolCalls.parse("calendar_add", %{"status" => "ok", "result" => %{"event_id" => "1"}})
  end

  test "a model sentence claiming an add is removed; next_actions are built from next_steps",
       ctx do
    fake_llm!(fn _name, _body ->
      %{
        "tool" => "final",
        "answer" => "I've added the meeting with Upali to your calendar. Anything else?",
        "sources" => [],
        "next_steps" => [
          "Connect your calendar in Settings",
          "What's on my calendar this week?",
          "Allow calendar access",
          "Gibberish that cannot be classified"
        ]
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
        "text" => "Thanks"
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert :ok = perform_job(Job, job.args)
    {_, body, a} = Enum.find(posts(), fn {_, _, r} -> r["kind"] == "answer" end)
    refute body =~ ~r/added/i
    assert body =~ "Anything else?"

    assert a["next_actions"] == [
             %{
               "label" => "Connect your calendar in Settings",
               "action" => "open",
               "target" => "settings.calendar"
             },
             %{
               "label" => "What's on my calendar this week?",
               "action" => "ask",
               "text" => "What's on my calendar this week?"
             },
             %{
               "label" => "Allow calendar access",
               "action" => "open",
               "target" => "settings.calendar_permission"
             }
           ]

    # next_steps stays for old apps (instructions only, questions dropped).
    assert is_list(a["next_steps"])
  end
end
