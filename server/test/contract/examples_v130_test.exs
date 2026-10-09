defmodule RisiMe.Contract.ExamplesV130Test do
  @moduledoc """
  Contract v1.30 §30 Risi Notes: every §30 example in `contract/v1/examples/` is the server's
  real output (or the exact body the server accepted), checked **exactly**: same keys, same
  values, except ids (any UUID) and wall-clock timestamps (`created_at`, `updated_at`, `at`,
  `expires_at`, `server_ts`, `done_at`), which only have to be of the same kind. Risi's clock is
  fixed (Fri 9 Oct 2026, Colombo), so the note's times, titles and bodies are stable.

  Root regenerates the files from this flow with
  `RISIME_WRITE_EXAMPLES=1 mix test test/contract/examples_v130_test.exs` (integration only).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @write System.get_env("RISIME_WRITE_EXAMPLES") == "1"

  @files ~w(device_put_risi_notes.json envelope_risi_note_card.json
            envelope_risi_notes_saved.json risi_notes_reply.json risi_note_reply.json
            envelope_risi_action_item_reopen.json risi_commitments_reply_v130.json)

  @doc false
  def files, do: @files

  @caps ~w(groups member_devices tabs risi_tools risi_skills risi_ledger risi_events risi_notes)

  # Fri 9 Oct 2026, 09:30 in Colombo (+05:30).
  @t0 ~U[2026-10-09 04:00:00.000000Z]

  setup do
    ledger_on!()
    restore_on_exit([:risi_notes])
    Application.put_env(:risime, :risi_notes, true)
    RisiMe.CalendarHelpers.events_on!()
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    clock!(@t0)

    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    shenika = RisiMe.Fixtures.logged_in_user(display_name: "Shenika")

    for u <- [harsha, shenika],
        do: u.user |> Ecto.Changeset.change(tz: "Asia/Colombo") |> Repo.update!()

    sd = RisiMe.TabsHelpers.tabs_device!(shenika.user, caps: @caps)
    og = risi_chat!([harsha.user, shenika.user])

    %{
      harsha: harsha,
      shenika: shenika,
      h: harsha.user.id,
      s: shenika.user.id,
      sd: sd,
      og: og,
      rc_h: own_risi_chat!(harsha.user),
      rc_s: own_risi_chat!(shenika.user)
    }
  end

  ## Exact comparison

  @any_uuid ~r/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/
  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$/
  @clock ~w(created_at updated_at server_ts at expires_at done_at)

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
      assert norm(ex, nil) == norm(real, nil), "#{name} differs from the server's real output"
    end

    real
  end

  defp envelope({_conv, body, risi}),
    do: %{"v" => 1, "type" => "text", "body" => body, "risi" => risi}

  defp post_in(ps, conv, kind),
    do: Enum.find(ps, fn {c, _, r} -> c == conv and r["kind"] == kind end)

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp api(ctx, who, method, path, dev) do
    token = if who == :h, do: ctx.harsha.token, else: ctx.shenika.token
    RisiMe.GroupHelpers.api(method, "/api/v1/risi" <> path, token, nil, dev)
  end

  defp act!(ctx, who, dev, env) do
    {user, rc} = if who == :h, do: {ctx.harsha.user, ctx.rc_h}, else: {ctx.shenika.user, ctx.rc_s}
    id = envelope!(rc, user, env, dev)
    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    assert :ok = perform_job(Job, job.args)
  end

  defp action(target, action),
    do: %{
      "v" => 1,
      "type" => "risi_action",
      "target" => target,
      "action" => action,
      "edit" => nil
    }

  test "§30.1, §30.3–§30.6: the device, note_card, notes_saved, tick ↔ reopen, My promises with
        note_id, the Notes list and a note",
       ctx do
    # The device capability (risi_notes only with risi_events).
    put =
      check!("device_put_risi_notes.json", %{
        "platform" => "android",
        "push_token" => nil,
        "app_version" => "0.3.0-nightly.48",
        "mls" => %{
          "signature_key" => "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
          "capabilities" =>
            ~w(groups member_devices images deletes calls history_share video group_calls
               call_switch screen_share tabs risi_tools risi_skills risi_ledger risi_events
               risi_notes)
        }
      })

    hd = Ecto.UUID.generate()
    {200, _} = RisiMe.GroupHelpers.api(:put, "/api/v1/me/devices/#{hd}", ctx.harsha.token, put)
    assert RisiMe.Agent.Notes.user?(ctx.h)

    # A discussion in the Official chat goes quiet: an item and an agreed meeting.
    fake_llm!(fn
      "discussion_summary", body ->
        %{
          "key_points" => [
            "The interview needs a room.",
            "Shenika leads it.",
            "Harsha sends the revised quote first."
          ],
          "summary" => "Interview planning: a room, Shenika leads, the quote goes first.",
          "topic" => "interview planning",
          "language" => "en",
          "items" => [
            item(body, "Harsha", "Send the revised quote",
              to: ["Shenika"],
              due: "2026-10-16",
              due_text: "by Fri 16 Oct"
            )
          ],
          "meetings" => [
            %{
              "title" => "Interview",
              "start_local" => "2026-10-14T14:00",
              "end_local" => nil,
              "proposed_by" => ref_of(body, "Shenika"),
              "with" => [ref_of(body, "Harsha"), ref_of(body, "Shenika")],
              "source" => [],
              "confidence" => 0.9
            }
          ]
        }

      _, _ ->
        %{"commitments" => []}
    end)

    {_, last} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], @t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    ps = posts()

    {_, _, card} = note = post_in(ps, ctx.rc_h, "note_card")
    assert card["for"] == ctx.h and card["source"] == "chat"
    assert [%{"state" => "proposed"}] = card["items"]
    assert length(card["events"]) == 1
    check!("envelope_risi_note_card.json", envelope(note))

    {_, _, saved} = s = post_in(ps, ctx.og, "notes_saved")
    assert saved["note_id"] == card["note_id"] and saved["notify"] == []
    check!("envelope_risi_notes_saved.json", envelope(s))

    # Harsha confirms; Shenika (counterpart) ticks it; Harsha un-ticks it (item_reopen).
    [%{"item_id" => item_id}] = card["items"]
    act!(ctx, :h, hd, action(item_id, "item_confirm"))
    act!(ctx, :s, ctx.sd, action(item_id, "done"))
    posts()

    reopen = check!("envelope_risi_action_item_reopen.json", action(item_id, "item_reopen"))
    act!(ctx, :h, hd, reopen)
    ups = for {_, _, %{"kind" => "item_update"} = r} <- posts(), do: r
    assert length(ups) == 2 and Enum.all?(ups, &(&1["state"] == "confirmed"))

    # My promises: the item carries its note.
    {200, promises} = api(ctx, :h, :get, "/commitments", hd)
    assert [%{"note_id" => nid, "state" => "confirmed"}] = promises["commitments"]
    assert nid == card["note_id"]
    check!("risi_commitments_reply_v130.json", promises)

    # The Notes list (a search hit) and the note (live states).
    {200, list} = api(ctx, :h, :get, "/notes?q=quote&limit=20", hd)
    assert [%{"note_id" => ^nid, "open_items_count" => 1}] = list["notes"]
    check!("risi_notes_reply.json", list)

    {200, one} = api(ctx, :h, :get, "/notes/" <> nid, hd)
    assert [%{"state" => "confirmed"}] = one["note"]["items"]
    check!("risi_note_reply.json", one)
  end
end
