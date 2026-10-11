defmodule RisiMe.Contract.ExamplesV136Test do
  @moduledoc """
  Contract v1.36 §35 (Risi Brain v2): one test for each of the 3 new example files, compared
  exactly against what the server really sends or answers (ids and clock times normalised).

  `envelope_risi_answer_memory_sources.json` has no `turn_ref`, which every turn answer carries
  (§25.4); that one key is dropped from the real envelope before the comparison.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.Memory
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do:
      ~w(risi_learning_put.json risi_learning_reply.json envelope_risi_answer_memory_sources.json)

  setup do
    calendar_world!()
  end

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

  test "risi_learning_put.json and risi_learning_reply.json: PUT then GET /risi/learning", ctx do
    put = example("risi_learning_put.json")
    # The example's chat id, as the caller's own chat.
    put = %{put | "conversation_id" => ctx.og}
    assert {204, nil} = as(ctx, :h, :put, "/api/v1/risi/learning", put)

    assert {200, reply} = as(ctx, :h, :get, "/api/v1/risi/learning")
    assert norm(reply, nil) == norm(example("risi_learning_reply.json"), nil)
    assert Memory.learning_enabled?(ctx.h, ctx.og) == false
  end

  test "envelope_risi_answer_memory_sources.json: 'summarise the work done this month'", ctx do
    ex = example("envelope_risi_answer_memory_sources.json")
    answer = ex["risi"]["answer"]
    remember!(ctx.og, ctx.harsha.user, "Kick-off", ~U[2026-09-30 04:00:00Z])

    remember!(
      ctx.og,
      ctx.shenika.user,
      "Revised quote agreed with Upali",
      ~U[2026-10-05 09:12:00Z]
    )

    fake_llm!(fn "risi_next_action", body ->
      res = body["messages"] |> Enum.filter(&(&1["role"] == "user")) |> Enum.at(1)
      [m] = Jason.decode!(res["content"])["result"]["messages"]
      %{"tool" => "final", "answer" => answer, "sources" => [m["ref"]], "next_steps" => []}
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
        "text" => "summarise the work done this month"
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert :ok = perform_job(Job, job.args)
    {_conv, body, risi} = Enum.find(posts(), fn {_, _, r} -> r["kind"] == "answer" end)

    real =
      %{"v" => 1, "type" => "text", "body" => body, "risi" => Map.delete(risi, "turn_ref")}
      |> Jason.encode!()
      |> Jason.decode!()

    assert norm(ex, nil) == norm(real, nil),
           "differs from the server's real output:\n#{Jason.encode!(norm(real, nil))}"
  end

  defp remember!(conv, user, text, at) do
    id = say!(conv, user, text, at)
    pt = Jason.encode!(%{"v" => 1, "type" => "text", "body" => text})
    :ok = Memory.put_message(conv, %{message_id: id, sender_id: user.id, plaintext: pt})
    id
  end
end
