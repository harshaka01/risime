defmodule RisiMe.Agent.MemoryLiveTest do
  @moduledoc """
  Risi Brain v2 step 2 against the real self-hosted `risi-l1` (decision 061). Excluded by
  default; run on spark2 with the model up (the root canary step):

      mix test --only llm_live test/risime/agent/memory_live_test.exs

    1. `memory_extract` returns schema-valid, sourced items from a mixed English/Sinhala chat;
    2. a Sinhala "summarise the work done this month" is answered from memory, in Sinhala
       script, citing a `memory` source;
    3. the same in Tamil.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.{Lang, LLM, Memory, MemoryIngest}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :llm_live
  @moduletag timeout: 180_000

  setup do
    old = Application.get_env(:risime, :risi_llm)

    Application.put_env(:risime, :risi_llm,
      url: "http://127.0.0.1:8100/v1",
      api_key: System.get_env("LLM_API_KEY"),
      req_options: []
    )

    restore_on_exit([:risi_turn])
    Application.put_env(:risime, :risi_turn, call_ms: 60_000, turn_ms: 150_000)
    on_exit(fn -> Application.put_env(:risime, :risi_llm, old) end)
    calendar_world!()
  end

  defp remember!(conv, user, text, at) do
    id = say!(conv, user, text, at)
    pt = Jason.encode!(%{"v" => 1, "type" => "text", "body" => text})
    :ok = Memory.put_message(conv, %{message_id: id, sender_id: user.id, plaintext: pt})
    id
  end

  defp chat!(ctx) do
    remember!(
      ctx.og,
      ctx.harsha.user,
      "Kick-off for the Galle solar job",
      ~U[2026-09-30 04:00:00Z]
    )

    remember!(
      ctx.og,
      ctx.shenika.user,
      "Panels for Galle installed today, 12 of 12.",
      ~U[2026-10-05 09:12:00Z]
    )

    remember!(
      ctx.og,
      ctx.harsha.user,
      "හොඳයි. ඉන්වර්ටරය බ්‍රහස්පතින්දා සවි කරමු.",
      ~U[2026-10-05 09:20:00Z]
    )

    remember!(
      ctx.og,
      ctx.shenika.user,
      "I'll send the final invoice to the client by Friday.",
      ~U[2026-10-06 05:00:00Z]
    )
  end

  defp ask!(ctx, text) do
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
    assert :ok = perform_job(Job, job.args)
    {_, body, a} = Enum.find(posts(), fn {_, _, r} -> r["kind"] == "answer" end)
    IO.puts("risi-l1 live (#{text}): #{body}")
    {body, a}
  end

  test "memory_extract on the live model: sourced items", ctx do
    chat!(ctx)
    assert :ok = MemoryIngest.run(ctx.og)

    items =
      RisiMe.Agent.Memory.Item
      |> Repo.all()
      |> Enum.map(&elem(Memory.open_item(&1), 1))

    IO.puts("risi-l1 live items: #{inspect(Enum.map(items, &{&1.kind, &1.text}))}")
    assert items != []
    assert Enum.all?(items, &(&1.source_message_ids != []))
    _ = LLM
  end

  test "Sinhala: summarise the work done this month, in Sinhala script with memory sources",
       ctx do
    chat!(ctx)
    {body, a} = ask!(ctx, "මේ මාසේ කරපු වැඩ සාරාංශ කරන්න")
    assert [%{"tool" => "summarise_range", "status" => "ok"} | _] = a["steps"]
    refute Lang.wrong_script?("si", body)
    assert Enum.any?(a["sources"], &(&1["type"] == "memory"))
  end

  test "Tamil: what did I agree, in Tamil script", ctx do
    chat!(ctx)
    {body, a} = ask!(ctx, "இந்த மாதம் Shenika உடன் நான் என்ன ஒப்புக்கொண்டேன்?")
    assert [%{"tool" => "memory_search", "status" => "ok"} | _] = a["steps"]
    refute Lang.wrong_script?("ta", body)
  end

  test "English: summarise the work done this month", ctx do
    chat!(ctx)
    {body, a} = ask!(ctx, "summarise the work done this month")
    assert [%{"tool" => "summarise_range", "status" => "ok"} | _] = a["steps"]
    assert body =~ ~r/panel|invoice|inverter/i
  end

  test "step 3: note_translate on the live model gives Sinhala and Tamil script", ctx do
    now = DateTime.utc_now()
    id = Ecto.UUID.generate()

    d = %{
      summary_id: id,
      conversation_id: ctx.og,
      chat_id: ctx.og,
      source: "chat",
      call_id: nil,
      media: nil,
      duration_s: nil,
      started_at: now,
      ended_at: now,
      participants: [ctx.h, ctx.s],
      made_by: %{},
      call_ref: nil,
      created_at: now
    }

    found = %{
      topic: "Galle solar job",
      language: "en",
      key_points: ["All 12 panels are installed.", "The inverter goes in on Thursday."]
    }

    {:ok, n} = RisiMe.Agent.Notes.build(d, found)
    RisiMe.Agent.Notes.insert!(n, [ctx.h, ctx.s])

    for lang <- ~w(si ta) do
      t = RisiMe.Agent.NoteLang.localise(n, lang, create: true)
      IO.puts("risi-l1 live note (#{lang}): #{t.topic} | #{Enum.join(t.key_points, " | ")}")
      assert t.language == lang and length(t.key_points) == 2
      refute Lang.wrong_script?(lang, Enum.join([t.topic | t.key_points], " "))
    end
  end

  test "step 4: the live model marks a cancelled meeting and the event is cancelled", ctx do
    {:ok, view} =
      RisiMe.Agent.Calendar.create(ctx.h, %{
        title: "Site visit",
        notes: nil,
        tz: "Asia/Colombo",
        start: ~U[2026-10-12 08:30:00.000000Z],
        stop: ~U[2026-10-12 09:30:00.000000Z],
        all_day: false,
        with: [ctx.s],
        drop: true,
        reminder_min: :default,
        source: %{conversation_id: ctx.og, message_ids: [], item_id: nil, note_id: nil},
        created_by: "risi"
      })

    remember!(
      ctx.og,
      ctx.harsha.user,
      "Site visit Monday 12 Oct at 2pm with Shenika?",
      ~U[2026-10-08 04:00:00Z]
    )

    remember!(ctx.og, ctx.shenika.user, "Yes, Monday 2pm works.", ~U[2026-10-08 04:05:00Z])

    remember!(
      ctx.og,
      ctx.shenika.user,
      "Sorry, we have to cancel Monday's site visit, the client is away.",
      ~U[2026-10-09 03:00:00Z]
    )

    assert :ok = MemoryIngest.run(ctx.og)
    {e, _} = RisiMe.Agent.Calendar.load(view["event_id"])
    IO.puts("risi-l1 live step 4: event state after the cancel message: #{e.state}")
    assert e.state == "cancelled"
  end
end
