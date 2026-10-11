defmodule RisiMe.Agent.ExportPdfTest do
  @moduledoc """
  Contract v1.34 §33.15 (server): the Risi `export_pdf` tool.

    * without `send_to`: a server-built answer with the `pdf` chip, no tool call, nothing sent;
    * with `send_to`: a `confirm` card built from server-held data only, [Send] runs the tool
      call on the confirming `pdf_export` device with the card's `export`, strict result checks,
      and honest sentences ("Sent" only for the phone's `sent`);
    * the `pdf_export` capability gates the tool (and is dropped without `risi_tools`).

  The server-produced examples are compared exactly (ids and clocks by kind only; `made_by` is
  added by every Risi post; the examples' source is a note, which no turn can reference yet, so
  the produced message source is substituted and the note source is checked on its own).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.{ClientTools, Notes, ToolCalls, Tools, Transcript}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @dir Path.expand("../../../../contract/v1/examples", __DIR__)
  @caps ~w(groups member_devices tabs risi_tools risi_skills pdf_export)

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal Perera")
    RisiMe.Fixtures.befriend!(harsha, kamal)
    og = risi_chat!([harsha.user, kamal.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = RisiMe.TabsHelpers.tabs_device!(harsha.user, caps: @caps)
    rc = own_risi_chat!(harsha.user)
    mid = summary_in!(rc)
    %{harsha: harsha, kamal: kamal, og: og, dev: dev, rc: rc, mid: mid}
  end

  ## Helpers

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  @any_uuid ~r/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/
  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
  @clock ~w(created_at updated_at responded_at server_ts at expires_at)

  defp norm(m, _key) when is_map(m), do: Map.new(m, fn {k, v} -> {k, norm(v, k)} end)
  defp norm(l, key) when is_list(l), do: Enum.map(l, &norm(&1, key))

  defp norm(s, key) when is_binary(s) do
    s = Regex.replace(@any_uuid, s, "<id>")
    if key in @clock and s =~ @ts, do: "<ts>", else: s
  end

  defp norm(v, _), do: v

  defp wire(term), do: term |> Jason.encode!() |> Jason.decode!()

  # A Risi summary Risi posted earlier in the Risi chat (the 24-h buffer entry `Out.post` makes).
  defp summary_in!(rc, kind \\ "summary") do
    mid = RisiMe.TimeUUID.generate()

    :ok =
      Transcript.put(rc, %{
        message_id: mid,
        sender_id: RisiMe.Risi.user_id(),
        sender_device: nil,
        plaintext:
          Jason.encode!(%{
            "v" => 1,
            "type" => "risi_post",
            "kind" => kind,
            "body" => "Summary of the interview planning"
          })
      })

    mid
  end

  defp ask!(conv, user, text, device) do
    rid = Ecto.UUID.generate()

    envelope!(
      conv,
      user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      },
      device
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    {rid, perform_job(Job, job.args)}
  end

  defp action_args(conv, user, action, target, device) do
    id =
      envelope!(
        conv,
        user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => nil
        },
        device
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    job.args
  end

  defp wait_call(user_id, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      [call | _] ->
        call

      [] when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, tries - 1)

      [] ->
        flunk("no risi_tool_call event")
    end
  end

  defp result(ctx, id, body, device),
    do: api(:post, "/api/v1/risi/tool_calls/#{id}/result", ctx.harsha.token, body, device)

  defp script(args),
    do: [
      %{
        "task" => "risi_next_action",
        "actions" => [
          %{"tool" => "export_pdf", "args" => args},
          %{
            "tool" => "final",
            "answer" => "Tap Send on the card.",
            "sources" => [],
            "next_steps" => []
          }
        ]
      }
    ]

  defp day(ctx) do
    ctx.mid
    |> RisiMe.TimeUUID.to_datetime()
    |> RisiMe.Agent.Clock.local(RisiMe.Agent.Clock.user_tz(ctx.harsha.user.id))
    |> Calendar.strftime("%a %-d %b")
  end

  defp src(ctx),
    do: %{"type" => "message", "conversation_id" => ctx.rc, "message_id" => ctx.mid}

  defp dm(ctx), do: RisiMe.Messaging.conversation_id(ctx.harsha.user.id, ctx.kamal.user.id)

  # Asks for a PDF sent to Kamal; returns the confirm card.
  defp card!(ctx) do
    scripted_llm!(script(%{"source" => "m1", "send_to" => "Kamal"}))
    {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "send this to Kamal as a pdf", ctx.dev)
    assert_receive {:risi_post, rc, body, %{"kind" => "confirm"} = card}
    assert rc == ctx.rc
    # The turn's own closing line (not the write's result).
    assert_receive {:risi_post, ^rc, _, %{"kind" => "answer"}}
    {body, card}
  end

  # [Send] on `device`; returns the tool call data, with `reply` posted as the phone's result.
  defp confirm_with(ctx, card, reply, device \\ nil) do
    device = device || ctx.dev
    args = action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], device)
    task = Task.async(fn -> perform_job(Job, args) end)
    call = wait_call(ctx.harsha.user.id)
    d = call["data"]
    {204, _} = result(ctx, d["tool_call_id"], reply, hd(d["to_devices"]))
    assert :ok = Task.await(task)
    {call, d}
  end

  ## Without send_to: the PDF button

  test "no send_to: the server's answer and the pdf chip; no card, no tool call", ctx do
    scripted_llm!(script(%{"source" => "m1", "send_to" => nil}))
    {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "make this a pdf", ctx.dev)

    assert_receive {:risi_post, rc, body, %{"kind" => "answer"} = answer}
    assert rc == ctx.rc and body == "Tap PDF to create it on your phone."
    refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
    assert events(ctx.harsha.user.id, "risi_tool_call") == []

    source = %{"type" => "message", "conversation_id" => ctx.rc, "message_id" => ctx.mid}
    assert answer["next_actions"] == [%{"label" => "PDF", "action" => "pdf", "source" => source}]
    assert answer["steps"] == [%{"tool" => "export_pdf", "status" => "ok"}]
    assert answer["next_steps"] == [] and answer["sources"] == [] and answer["refs"] == []

    # envelope_risi_answer_next_action_pdf.json: the same envelope (source substituted).
    ex = example("envelope_risi_answer_next_action_pdf.json")
    real = wire(%{"v" => 1, "type" => "text", "body" => body, "risi" => answer})
    real = update_in(real, ["risi"], &Map.delete(&1, "made_by"))

    # `local_search: null` (a v1.25 field the server doesn't send yet) reads as absent.
    ex = update_in(ex, ["risi"], &Map.delete(&1, "local_search"))
    ex_real_source = put_in(ex, ["risi", "next_actions"], real["risi"]["next_actions"])
    assert norm(ex_real_source, nil) == norm(real, nil)

    # The example's own (note) source is a PdfSource the server builds from a note it keeps.
    assert [%{"source" => %{"type" => "note", "note_id" => _}}] =
             ex["risi"]["next_actions"]
  end

  test "a source the server did not hand out fails the step; the model never writes ids", ctx do
    # m2 does not exist; the asker's own line and a confirm card get no export ref.
    summary_in!(ctx.rc, "confirm")

    for src <- [
          "m2",
          "m9",
          "n1",
          %{"calendar" => %{"view" => "year", "from" => "x", "to" => "y"}}
        ] do
      scripted_llm!(script(%{"source" => src, "send_to" => nil}))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "make a pdf", ctx.dev)
      posts = RisiMe.CalendarHelpers.posts()
      assert Enum.any?(posts, fn {_, _, r} -> r["kind"] in ~w(answer error) end)

      for {_, _, r} <- posts, r["kind"] == "answer" do
        # v1.35 §34.3: a failed step gets only the server's "Ask me again" (the original text).
        assert Map.get(r, "next_actions", []) --
                 [
                   %{"label" => "Ask me again", "action" => "ask", "text" => "make a pdf"}
                 ] == []

        refute Enum.any?(r["steps"], &(&1["tool"] == "export_pdf" and &1["status"] == "ok"))
      end
    end
  end

  test "a calendar source (at most 31 days) gives a calendar PdfSource chip", ctx do
    args = %{
      "source" => %{
        "calendar" => %{
          "view" => "week",
          "from" => "2026-10-12T00:00:00+05:30",
          "to" => "2026-10-18T23:59:00+05:30"
        }
      },
      "send_to" => nil
    }

    scripted_llm!(script(args))
    {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "pdf of next week's calendar", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "answer"} = a}

    assert [%{"action" => "pdf", "source" => src}] = a["next_actions"]
    assert %{"type" => "calendar", "view" => "week", "from" => from, "to" => to} = src
    assert {:ok, f, _} = DateTime.from_iso8601(from)
    assert {:ok, t, _} = DateTime.from_iso8601(to)
    assert DateTime.diff(t, f) <= 31 * 86_400

    # 32 days is refused.
    long = put_in(args, ["source", "calendar", "to"], "2026-11-20T00:00:00+05:30")
    scripted_llm!(script(long))
    {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "pdf of a long calendar", ctx.dev)
    posts = RisiMe.CalendarHelpers.posts()
    # v1.35 §34.3: a failed step may only bring the server's "Ask me again".
    refute Enum.any?(posts, fn {_, _, r} ->
             Enum.any?(r["next_actions"] || [], &(&1["label"] != "Ask me again"))
           end)
  end

  ## With send_to: confirm card, tool call, result

  test "send_to: the confirm card is built from server data; the example envelope", ctx do
    {body, card} = card!(ctx)
    title = "Risi summary · #{day(ctx)}"
    export = %{"source" => src(ctx), "conversation_id" => dm(ctx)}

    assert card["tool"] == "export_pdf" and card["buttons"] == ["send", "cancel"]
    assert card["when"] == nil and card["text"] == title and card["export"] == export
    assert card["summary"] == "Send \"#{title}\" as a PDF to Kamal Perera (🔒 Private)"

    assert body ==
             "Send \"#{title}\" as a PDF to Kamal Perera (Private)? Update RisiMe to answer."

    assert card["for"] == [ctx.harsha.user.id]
    refute Map.has_key?(card, "args")
    refute Map.has_key?(card, "skill_id")

    # Nothing runs before [Send].
    assert events(ctx.harsha.user.id, "risi_tool_call") == []

    # envelope_risi_confirm_export_pdf.json (source and ids substituted, no made_by).
    ex = example("envelope_risi_confirm_export_pdf.json")
    real = wire(%{"v" => 1, "type" => "text", "body" => body, "risi" => card})
    real = update_in(real, ["risi"], &Map.delete(&1, "made_by"))

    ex = put_in(ex, ["risi", "export", "source"], export["source"])
    ex = put_in(ex, ["risi", "text"], title)
    ex = put_in(ex, ["risi", "summary"], card["summary"])
    ex = put_in(ex, ["body"], body)
    assert norm(ex, nil) == norm(real, nil)

    assert Map.keys(example("envelope_risi_confirm_export_pdf.json")["risi"]) |> Enum.sort() ==
             Map.keys(card) |> Kernel.--(["made_by"]) |> Enum.sort()
  end

  test "[Send]: the call goes to the confirming pdf_export device; sent says Sent", ctx do
    {_, card} = card!(ctx)
    other = RisiMe.TabsHelpers.tabs_device!(ctx.harsha.user, caps: @caps)

    {call, d} =
      confirm_with(ctx, card, example("risi_tool_result_export_pdf.json"), other)

    assert d["tool"] == "export_pdf" and d["to_devices"] == [other]
    assert d["undo_entry_id"] == nil

    assert d["args"] == %{
             "write_id" => card["write_id"],
             "source" => card["export"]["source"],
             "conversation_id" => card["export"]["conversation_id"]
           }

    # event_risi_tool_call_export_pdf.json: same shape and keys, ids and clocks by kind.
    ex = example("event_risi_tool_call_export_pdf.json")
    ex = put_in(ex, ["data", "args", "source"], card["export"]["source"])
    ex = put_in(ex, ["data", "conversation_id"], ctx.rc)
    assert norm(ex, nil) == norm(wire(call), nil)

    assert_receive {:risi_post, rc, "Sent the PDF to Kamal Perera.", %{"kind" => "answer"} = a}
    assert rc == ctx.rc
    assert a["steps"] == [%{"tool" => "export_pdf", "status" => "ok"}]
    assert events(ctx.harsha.user.id, "risi_tool_call") == []
  end

  test "a device without pdf_export is not the confirming target; the turn's device is", ctx do
    {_, card} = card!(ctx)
    plain = RisiMe.TabsHelpers.risi_tools_device!(ctx.harsha.user)
    {_, d} = confirm_with(ctx, card, example("risi_tool_result_export_pdf.json"), plain)
    assert d["to_devices"] == [ctx.dev]
  end

  test "queued says the outbox, never Sent", ctx do
    {_, card} = card!(ctx)
    confirm_with(ctx, card, %{"status" => "ok", "result" => %{"state" => "queued", "pages" => 2}})

    assert_receive {:risi_post, _, body, %{"kind" => "answer"}}
    assert body == "The PDF is in your phone's outbox. It goes as soon as your phone is online."
    refute body =~ "Sent"
  end

  test "errors: the five sentences; risi_tool_result_export_pdf_error.json", ctx do
    ex = example("risi_tool_result_export_pdf_error.json")

    for {code, text} <- [
          {"source_unavailable", "I couldn't send it: the message isn't on this phone."},
          {"not_member", "I couldn't send it: you're not in that chat any more."},
          {"files_not_ready",
           "I couldn't send it: Kamal Perera needs to update RisiMe to receive files."},
          {"too_large", "I couldn't send it: it's too long for a PDF."},
          {"pdf_failed", "I couldn't send it: the phone couldn't make the PDF."}
        ] do
      {_, card} = card!(ctx)
      confirm_with(ctx, card, put_in(ex, ["result", "code"], code))
      assert_receive {:risi_post, _, body, %{"kind" => "answer"} = a}
      assert body == text
      assert a["steps"] == [%{"tool" => "export_pdf", "status" => "failed"}]
      refute body =~ ~r/\bSent\b/
    end
  end

  test "a declined result and a timeout never say Sent", ctx do
    {_, card} = card!(ctx)
    confirm_with(ctx, card, %{"status" => "declined"})
    assert_receive {:risi_post, _, body, %{"kind" => "answer"}}
    assert body == "Your phone declined it, so nothing was changed."

    restore_on_exit([:risi_tool_deadline_ms])
    Application.put_env(:risime, :risi_tool_deadline_ms, 150)
    {_, card} = card!(ctx)
    args = action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
    assert :ok = perform_job(Job, args)
    assert_receive {:risi_post, _, _, %{"kind" => "error", "code" => "tool_timeout"} = e}
    refute inspect(e) =~ "Sent"
  end

  test "strict result checks: state, pages and codes", ctx do
    {_, card} = card!(ctx)
    args = action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
    task = Task.async(fn -> perform_job(Job, args) end)
    d = wait_call(ctx.harsha.user.id)["data"]

    for bad <- [
          %{"status" => "ok", "result" => %{"state" => "delivered", "pages" => 3}},
          %{"status" => "ok", "result" => %{"state" => "sent"}},
          %{"status" => "ok", "result" => %{"state" => "sent", "pages" => 0}},
          %{"status" => "ok", "result" => %{"state" => "sent", "pages" => 201}},
          %{"status" => "ok", "result" => %{"state" => "sent", "pages" => "3"}},
          %{"status" => "ok", "result" => %{"state" => "sent", "pages" => 3, "extra" => 1}},
          %{"status" => "ok", "result" => %{"sent" => true}},
          %{"status" => "ok", "result" => nil},
          %{"status" => "error", "result" => %{"code" => "insert_failed"}},
          %{"status" => "error", "result" => %{"code" => "pdf_failed", "detail" => "x"}},
          %{"status" => "error", "code" => "pdf_failed"},
          %{"status" => "declined", "result" => %{"state" => "sent", "pages" => 3}}
        ] do
      assert {422, _} = result(ctx, d["tool_call_id"], bad, ctx.dev), inspect(bad)
    end

    # The call is still open: the real example is accepted.
    {204, _} =
      result(ctx, d["tool_call_id"], example("risi_tool_result_export_pdf.json"), ctx.dev)

    assert :ok = Task.await(task)
    assert_receive {:risi_post, _, "Sent the PDF to Kamal Perera.", _}
  end

  test "an unconfirmed write is refused a result (409 write_not_confirmed)", ctx do
    {_, card} = card!(ctx)

    # A call for this write_id without [Send]: the phone's `ok` is refused.
    {:ok, row} =
      ToolCalls.start(%{
        user: ctx.harsha.user.id,
        device: ctx.dev,
        tool: "export_pdf",
        args: %{},
        write_id: card["write_id"]
      })

    assert {409, _} =
             result(
               ctx,
               row.tool_call_id,
               example("risi_tool_result_export_pdf.json"),
               ctx.dev
             )
  end

  test "send_to: an unknown name fails the step; Official only for \"this chat\"", ctx do
    scripted_llm!(script(%{"source" => "m1", "send_to" => "Nobody Here"}))
    {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "send this to Nobody as pdf", ctx.dev)
    posts = RisiMe.CalendarHelpers.posts()
    refute Enum.any?(posts, fn {_, _, r} -> r["kind"] == "confirm" end)

    # "this chat" in the Risi chat is never a target.
    scripted_llm!(script(%{"source" => "m1", "send_to" => "this chat"}))
    {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "send it here as pdf", ctx.dev)
    posts = RisiMe.CalendarHelpers.posts()
    refute Enum.any?(posts, fn {_, _, r} -> r["kind"] == "confirm" end)
  end

  ## The capability

  test "export_pdf is offered only to a pdf_export device with an active Risi chat", ctx do
    h = ctx.harsha.user.id
    base = %{asker: h, conv: ctx.rc, member?: true, risi_chat?: true, gated?: false}
    tool = Tools.get("export_pdf")

    assert Tools.authorize(tool, Map.put(base, :device_id, ctx.dev)) == :ok

    no_pdf =
      RisiMe.TabsHelpers.tabs_device!(ctx.harsha.user,
        caps: ~w(groups member_devices tabs risi_tools risi_skills)
      )

    assert Tools.authorize(tool, Map.put(base, :device_id, no_pdf)) == {:error, :denied}

    # pdf_export without risi_tools is silently dropped.
    stray =
      RisiMe.TabsHelpers.tabs_device!(ctx.harsha.user, caps: ~w(groups tabs pdf_export))

    refute "pdf_export" in RisiMe.Devices.get(h, stray).capabilities
    assert Tools.authorize(tool, Map.put(base, :device_id, stray)) == {:error, :denied}

    assert Tools.authorize(
             tool,
             Map.put(base, :risi_chat?, false) |> Map.put(:device_id, ctx.dev)
           ) ==
             {:error, :denied}

    refute "export_pdf" in Enum.map(Tools.allowed(Map.put(base, :device_id, no_pdf)), & &1.name)
    assert "export_pdf" in Enum.map(Tools.allowed(Map.put(base, :device_id, ctx.dev)), & &1.name)
  end

  ## A note source (no turn can reference a note yet: the server builds it from a kept note)

  test "a note ref gives a note PdfSource and a title from the note's topic and date", ctx do
    id = Ecto.UUID.generate()
    at = ~U[2026-10-09 05:00:00.000000Z]

    {:ok, note} =
      Notes.build(
        %{
          summary_id: id,
          conversation_id: ctx.og,
          chat_id: ctx.og,
          source: "chat",
          call_id: nil,
          media: nil,
          duration_s: nil,
          started_at: at,
          ended_at: at,
          participants: [ctx.harsha.user.id],
          made_by: %{},
          call_ref: nil,
          created_at: at
        },
        %{topic: "Interview planning", language: "en", key_points: ["Agree the panel"]}
      )

    RisiMe.Repo.transaction(fn -> Notes.insert!(note, [ctx.harsha.user.id]) end)

    run_ctx = %{
      asker: ctx.harsha.user.id,
      tz: "Asia/Colombo",
      now: at,
      conv: ctx.rc,
      in_risi_chat?: true,
      refs: %{"n1" => %{"type" => "note", "note_id" => id}},
      export_refs: %{}
    }

    tool = Tools.get("export_pdf")

    assert {:ok, %{"status" => "button_ready"}, %{pdf_source: src}} =
             tool.run.(%{"source" => "n1", "send_to" => nil}, run_ctx)

    assert src == %{"type" => "note", "note_id" => id}

    assert {:propose, card} = tool.run.(%{"source" => "n1", "send_to" => "Kamal"}, run_ctx)
    assert card.text == "Interview planning · Fri 9 Oct"

    assert card.summary ==
             "Send \"Interview planning · Fri 9 Oct\" as a PDF to Kamal Perera (🔒 Private)"

    assert card.export["source"] == src

    # The same shape as the example's note source.
    ex = example("envelope_risi_confirm_export_pdf.json")["risi"]["export"]["source"]
    assert Map.keys(ex) == Map.keys(src)

    # A note someone else keeps (or a deleted one) is not exportable.
    other = Ecto.UUID.generate()

    assert {:error, "failed", _} =
             tool.run.(
               %{"source" => "n1", "send_to" => nil},
               %{run_ctx | refs: %{"n1" => %{"type" => "note", "note_id" => other}}}
             )

    assert {:error, "failed", _} =
             tool.run.(%{"source" => "n1", "send_to" => nil}, %{
               run_ctx
               | asker: ctx.kamal.user.id
             })

    assert ClientTools.pdf_kinds() |> Enum.sort() ==
             ~w(answer digest discussion_summary note_card report summary)
  end
end
