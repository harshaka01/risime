defmodule RisiMe.Agent.MadeByS20Test do
  @moduledoc """
  v1.27 S20 (§27.1): `made_by` on every Risi envelope, from the learning-log row of its
  `call_ref`; `model: null` for rule-made messages. Fake `risi-l1`, fixed clock.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers

  alias RisiMe.Agent.{Clock, LearningLog, MadeBy, Out}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    og = risi_chat!([harsha, kamal])
    %{harsha: harsha, kamal: kamal, og: og}
  end

  defp ask!(ctx, user, text) do
    rid = Ecto.UUID.generate()

    envelope!(ctx.og, user, %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => rid,
      "action" => "ask",
      "text" => text,
      "scope" => %{"since" => nil}
    })

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert :ok = perform_job(Job, job.args)
    rid
  end

  defp ts_of(call_ref), do: call_ref |> RisiMe.TimeUUID.to_datetime() |> Clock.ts()

  test "a model answer names the model (risi-l1, risime) at the call's completion time", ctx do
    say!(ctx.og, ctx.kamal, "The site visit moved to Thursday 10 am")

    fake_llm!(fn "answer", body ->
      %{
        "answer" => "Thursday 10 am.",
        "refs" => [ref_of(body, "The site visit moved to Thursday 10 am")],
        "confidence" => 0.78
      }
    end)

    ask!(ctx, ctx.harsha, "When is the site visit?")
    assert_receive {:risi_post, _, "Thursday 10 am.", r}

    assert r["made_by"] == %{
             "model" => "risi-l1",
             "provider" => "risime",
             "at" => ts_of(r["call_ref"]),
             "also" => []
           }

    # The example's shape, exactly (envelope_risi_answer_made_by.json).
    ex = RisiMe.TabsHelpers.example("envelope_risi_answer_made_by.json")["risi"]
    assert Enum.sort(Map.keys(r)) == Enum.sort(Map.keys(ex))
    assert Enum.sort(Map.keys(r["made_by"])) == Enum.sort(Map.keys(ex["made_by"]))
  end

  test "rule-made messages carry model null, provider risime, at = Risi's clock", ctx do
    # An error reply (no model behind it).
    :ok =
      RisiMe.Agent.Requests.reply_error(
        ctx.og,
        Ecto.UUID.generate(),
        ctx.harsha.id,
        "out_of_window"
      )

    assert_receive {:risi_post, _, _, %{"kind" => "error"} = r}
    assert r["call_ref"] == nil

    assert r["made_by"] == %{
             "model" => nil,
             "provider" => "risime",
             "at" => Clock.ts(Clock.now()),
             "also" => []
           }

    assert Clock.ts(Clock.now()) == "2026-10-05T08:00:00.000Z"
  end

  test "the post-check fallback answer is server-built: model null", ctx do
    fake_llm!(fn "answer", _ ->
      %{
        "answer" => "The transcript does not contain that information.",
        "refs" => [],
        "confidence" => 0.5
      }
    end)

    ask!(ctx, ctx.harsha, "What's my dentist's name?")
    assert_receive {:risi_post, _, _, %{"kind" => "answer"} = r}
    assert is_binary(r["call_ref"])
    assert r["made_by"]["model"] == nil and r["made_by"]["provider"] == "risime"
  end

  test "a commercial model is named by its provider's display name and model id" do
    RisiMe.RisiHelpers.restore_on_exit([:risi_commercial_provider_name])
    Application.put_env(:risime, :risi_commercial_provider_name, "Anthropic")
    ref = RisiMe.TimeUUID.generate()
    local = RisiMe.TimeUUID.generate()
    speech = RisiMe.TimeUUID.generate()

    for {id, provider, model, task} <- [
          {ref, "fallback", "claude-sonnet-x", "ask"},
          {local, "risi_l1", "/models/Qwen", "risi_next_action"},
          {speech, "risi_l1", "/models/Qwen", "risi_next_action"}
        ] do
      :ok =
        LearningLog.record(%{
          call_id: id,
          conversation_id: "grp:x",
          chat_id: "grp:x",
          task: task,
          model_alias: "risi-l1",
          model: model,
          provider: provider,
          status: "ok"
        })
    end

    extra = [
      %{"model" => "faster-whisper-large-v3", "provider" => "risime", "task" => "transcribe"}
    ]

    assert MadeBy.build(ref, [local, speech, ref], extra) == %{
             "model" => "claude-sonnet-x",
             "provider" => "Anthropic",
             "at" => ts_of(ref),
             # risi-l1 once (no repeats), then the speech model; never the main model again.
             "also" => [
               %{"model" => "risi-l1", "provider" => "risime", "task" => "risi_next_action"},
               hd(extra)
             ]
           }

    # Our own model as the main one: the alias, provider risime, no repeat of itself in also.
    assert %{"model" => "risi-l1", "provider" => "risime", "also" => []} =
             MadeBy.build(local, [speech])
  end

  test "Out.post adds made_by to every risi object, keeps a caller's own", ctx do
    {:ok, _} = Out.post(ctx.og, "x", %{"kind" => "reminder"})
    assert_receive {:risi_post, _, "x", %{"made_by" => %{"model" => nil}}}

    # A rule kind stays model null even with a call_ref riding along (feedback).
    ref = RisiMe.TimeUUID.generate()
    {:ok, _} = Out.post(ctx.og, "z", %{"kind" => "skill_needed", "call_ref" => ref})
    assert_receive {:risi_post, _, "z", %{"made_by" => %{"model" => nil}, "call_ref" => ^ref}}

    own = MadeBy.rule(~U[2026-10-05 09:00:00Z])
    {:ok, _} = Out.post(ctx.og, "y", %{"kind" => "reminder", "made_by" => own})
    assert_receive {:risi_post, _, "y", %{"made_by" => ^own}}
  end
end
