defmodule RisiMe.Agent.OfflineTest do
  @moduledoc """
  PART 4 (Harsha 2026-10-12) "never silent": a request whose model is down or slow gets
  "Risi is offline, I'll reply when I'm back." once, stays queued, and is answered when the model
  is back (`RisiMe.Agent.Offline`).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers

  alias RisiMe.Agent.Offline
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    og = risi_chat!([harsha, kamal])

    keys = [:risi_tools, :risi_turn]
    old = for k <- keys, do: {k, Application.fetch_env(:risime, k)}

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end
    end)

    Application.put_env(:risime, :risi_tools, true)
    %{harsha: harsha, kamal: kamal, og: og}
  end

  defp request!(ctx, text) do
    rid = Ecto.UUID.generate()

    envelope!(ctx.og, ctx.harsha, %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => rid,
      "action" => "ask",
      "text" => text
    })

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    {rid, job.args}
  end

  defp final(answer),
    do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}

  test "the model down: one offline notice, the request waits; the model back: the answer", ctx do
    fake_llm!(fn _, _ -> {:status, 503} end)
    {rid, args} = request!(ctx, "hello there")

    assert {:snooze, 15} = perform_job(Job, args)
    assert_receive {:risi_post, og, body, a}
    assert og == ctx.og and body == Offline.text()
    assert body == "Risi is offline, I'll reply when I'm back."
    assert a["kind"] == "answer" and a["request_id"] == rid and a["answer"] == body
    assert a["notify"] == [ctx.harsha.id] and a["call_ref"] == nil and a["refs"] == []
    assert Offline.sent?(rid)

    # Still down on the next attempt: no second notice.
    assert {:snooze, 15} = perform_job(Job, args)
    refute_receive {:risi_post, _, _, _}

    # The model is back: the real reply, by itself.
    fake_llm!(fn "risi_next_action", _ -> final("Here is your answer.") end)
    assert :ok = perform_job(Job, args)
    assert_receive {:risi_post, ^og, "Here is your answer.", b}
    assert b["kind"] == "answer" and b["request_id"] == rid
    refute Offline.sent?(rid)
  end

  test "a slow model: the notice after offline_ms, then the answer of the same attempt", ctx do
    Application.put_env(:risime, :risi_turn, offline_ms: 150)

    fake_llm!(fn "risi_next_action", _ ->
      Process.sleep(700)
      final("Slow but here.")
    end)

    {rid, args} = request!(ctx, "hello there")
    assert :ok = perform_job(Job, args)
    assert_receive {:risi_post, _, notice, %{"request_id" => ^rid}}
    assert notice == Offline.text()
    assert_receive {:risi_post, _, "Slow but here.", %{"request_id" => ^rid}}
    refute_receive {:risi_post, _, _, _}, 300
  end

  test "a model that answers in time: no notice, ever", ctx do
    Application.put_env(:risime, :risi_turn, offline_ms: 300)
    fake_llm!(fn "risi_next_action", _ -> final("Quick answer.") end)

    {rid, args} = request!(ctx, "hello there")
    assert :ok = perform_job(Job, args)
    assert_receive {:risi_post, _, "Quick answer.", %{"request_id" => ^rid}}
    refute_receive {:risi_post, _, _, _}, 600
    refute Offline.sent?(rid)
  end

  test "still down after the wait bound: the turn ends with an answer, not another snooze", ctx do
    fake_llm!(fn _, _ -> {:status, 503} end)
    {rid, args} = request!(ctx, "hello there")
    stale = Map.put(args, "t0", System.system_time(:second) - 1_900)
    assert :ok = perform_job(Job, stale)
    assert_receive {:risi_post, _, body, %{"request_id" => ^rid}}
    assert body =~ "I couldn't reach my model"
  end
end
