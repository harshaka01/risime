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
  def files, do: ~w(risi_tool_result_calendar_check_sync_off.json)

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
end
