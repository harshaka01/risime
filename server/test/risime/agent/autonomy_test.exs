defmodule RisiMe.Agent.AutonomyTest do
  @moduledoc """
  v1.36 §35.4 (Risi Brain v2 step 3): the always-on ingest's autonomous posts, with a fixed
  clock and a scripted fake model:

    1. a quiet discussion without items but with ≥ 3 key points is summarised to the chat;
    2. the quiet period is 30 min by default;
    3. an agreed meeting: the participant whose Calendar skill is "Allowed" gets it added
       (accepted, read back, "Added to your Risi Calendar"), the "Ask" one an invite;
    4. a timed item for a phone-calendar user under "Allowed": added on the phone at once (only
       after the phone's read-back), no card; an unconfirmed add falls back to the card;
    5. each participant's Risi Note is in their own language (Sinhala here), on the card and in
       `GET /risi/notes/{id}`.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers
  import RisiMe.GroupHelpers, only: [events: 2]

  alias RisiMe.Agent.{Commitment, Ledger, Memory, Offers, Skills}
  alias RisiMe.Agent.Calendar.Participant

  @moduletag capture_log: true

  @caps ~w(groups member_devices tabs risi_tools risi_skills risi_ledger risi_events)

  setup do
    ledger_on!()
    restore_on_exit([:risi_notes, :risi_events, :risi_quiet_s])
    Application.put_env(:risime, :risi_notes, true)
    RisiMe.CalendarHelpers.events_on!()
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()

    t0 = %{stable_t0() | microsecond: {0, 6}}
    clock!(t0)

    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    shenika = RisiMe.Fixtures.logged_in_user(display_name: "Shenika")

    for u <- [harsha, shenika],
        do: u.user |> Ecto.Changeset.change(tz: "Asia/Colombo") |> Repo.update!()

    hd = RisiMe.TabsHelpers.tabs_device!(harsha.user, caps: @caps ++ ["risi_notes"])
    sd = RisiMe.TabsHelpers.tabs_device!(shenika.user, caps: @caps ++ ["risi_notes"])
    og = risi_chat!([harsha.user, shenika.user])
    rc_h = own_risi_chat!(harsha.user)
    rc_s = own_risi_chat!(shenika.user)

    %{
      harsha: harsha,
      shenika: shenika,
      h: harsha.user.id,
      s: shenika.user.id,
      hd: hd,
      sd: sd,
      og: og,
      rc_h: rc_h,
      rc_s: rc_s,
      t0: t0
    }
  end

  ## Helpers

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp in_conv(ps, conv), do: for({^conv, body, risi} <- ps, do: {body, risi})

  defp skill!(user, state) do
    Repo.insert!(
      %Skills.State{
        user_id: user,
        skill_id: "calendar",
        state: state,
        ever_on: true,
        changed_at: DateTime.utc_now()
      },
      on_conflict: {:replace, [:state]},
      conflict_target: [:user_id, :skill_id]
    )
  end

  defp model!(extraction, other \\ fn _, _ -> %{"commitments" => []} end) do
    fake_llm!(fn
      "discussion_summary", body ->
        # Only the keys this extraction's schema has (no topic/meetings without notes users).
        props = get_in(body, ["response_format", "json_schema", "schema", "properties"]) || %{}

        Map.merge(
          %{
            "key_points" => ["Panels are installed.", "Inverter on Thursday.", "Invoice next."],
            "summary" => "Galle job progress.",
            "items" => [],
            "topic" => "Galle job",
            "language" => "en",
            "meetings" => []
          },
          extraction.(body)
        )
        |> Map.take(Map.keys(props))

      name, body ->
        other.(name, body)
    end)
  end

  defp discuss!(ctx) do
    {_, last} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], ctx.t0, 6)
    clock!(DateTime.add(last, 1800))
    assert :ok = quiet!(ctx.og)
    posts()
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

  ## 1–2. The quiet-period summary

  test "the quiet period defaults to 30 min (§35.4)" do
    Application.delete_env(:risime, :risi_quiet_s)
    assert Ledger.quiet_s() == 1800
  end

  test "a discussion without items but with 3 key points is summarised to the chat", ctx do
    Application.put_env(:risime, :risi_notes, false)
    model!(fn _ -> %{} end)
    out = discuss!(ctx)

    assert [{body, card}] = in_conv(out, ctx.og)
    assert card["kind"] == "discussion_card" and card["items_count"] == 0
    assert card["summary"] == "Galle job progress."

    assert card["key_points"] == [
             "Panels are installed.",
             "Inverter on Thursday.",
             "Invoice next."
           ]

    assert body ==
             "Summary: Galle job progress.\n• Panels are installed.\n• Inverter on Thursday.\n• Invoice next."

    # Fewer than 3 key points and no item: nothing, as before.
    model!(fn _ -> %{"key_points" => ["Hi."], "summary" => "Greetings."} end)
    {_, last} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], DateTime.add(ctx.t0, 7200), 6)
    clock!(DateTime.add(last, 3600))
    assert :ok = quiet!(ctx.og)
    assert in_conv(posts(), ctx.og) == []
  end

  ## 3. Agreed meetings: Allowed → added (read back), Ask → invite

  test "an agreed meeting is added for the 'Allowed' participant and an invite for the 'Ask' one",
       ctx do
    skill!(ctx.h, "allowed")
    skill!(ctx.s, "ask")

    model!(fn body ->
      %{
        "meetings" => [
          %{
            "title" => "Site visit",
            "start_local" => "2026-12-02T14:00",
            "end_local" => nil,
            "proposed_by" => ref_of(body, "Shenika"),
            "with" => [ref_of(body, "Harsha"), ref_of(body, "Shenika")],
            "source" => [],
            "confidence" => 0.9
          }
        ]
      }
    end)

    out = discuss!(ctx)

    [{added_body, added}] =
      for {b, %{"kind" => "event_card", "mode" => "added"} = r} <- in_conv(out, ctx.rc_h),
          do: {b, r}

    assert added_body =~ ~r/^Added to your Risi Calendar: .* · Wed 2 Dec, 2–3 PM/
    refute Enum.any?(in_conv(out, ctx.rc_h), fn {_, r} -> r["kind"] == "calendar_invite" end)
    assert Enum.any?(in_conv(out, ctx.rc_s), fn {_, r} -> r["kind"] == "calendar_invite" end)

    # Read back from the event itself.
    statuses =
      Repo.all(from p in Participant, where: p.event_id == ^added["event_id"])
      |> Map.new(&{&1.user_id, &1.status})

    assert statuses[ctx.h] == "accepted" and statuses[ctx.s] == "proposed"
  end

  ## 4. Phone calendar, Allowed

  defp phone_item!(ctx) do
    {:ok, c} =
      Commitment.seal(%Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: ctx.og,
        chat_id: ctx.og,
        state: "confirmed",
        item_state: "confirmed",
        text: "Interview with Shenika",
        owner_id: ctx.h,
        counterpart_ids: [ctx.s],
        due: DateTime.add(ctx.t0, 3 * 86_400) |> RisiMe.Agent.Clock.usec(),
        due_kind: "datetime",
        all_day: false,
        source: "chat",
        source_message_ids: [],
        proposed_at: ctx.t0
      })

    Repo.insert!(c)
  end

  test "Allowed, phone calendar: added at once after the phone's read-back; no card", ctx do
    # Not a Risi Calendar user: the phone path.
    Application.put_env(:risime, :risi_events, false)
    skill!(ctx.h, "allowed")
    c = phone_item!(ctx)

    task = Task.async(fn -> Offers.consider_all([c]) end)
    d = wait_call(ctx.h)["data"]
    assert d["tool"] == "calendar_add" and d["args"]["title"] == "Interview with Shenika"

    {204, _} =
      RisiMe.GroupHelpers.api(
        :post,
        "/api/v1/risi/tool_calls/#{d["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => %{"event_id" => "e1", "verified" => true}},
        ctx.hd
      )

    assert Task.await(task, 20_000) >= 1
    ps = in_conv(posts(), ctx.rc_h)
    refute Enum.any?(ps, fn {_, r} -> r["kind"] == "confirm" end)
    assert Enum.any?(ps, fn {b, _} -> b =~ "Added to your calendar: Interview with Shenika" end)

    assert [%{kind: "calendar", state: "added"}] =
             Offers.list(c.id) |> Enum.filter(&(&1.user_id == ctx.h))
  end

  test "Allowed, phone calendar: an add the phone doesn't read back falls back to the card",
       ctx do
    Application.put_env(:risime, :risi_events, false)
    skill!(ctx.h, "allowed")
    c = phone_item!(ctx)

    task = Task.async(fn -> Offers.consider_all([c]) end)
    d = wait_call(ctx.h)["data"]

    {204, _} =
      RisiMe.GroupHelpers.api(
        :post,
        "/api/v1/risi/tool_calls/#{d["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => %{"event_id" => "e1"}},
        ctx.hd
      )

    Task.await(task, 20_000)
    ps = in_conv(posts(), ctx.rc_h)
    refute Enum.any?(ps, fn {b, _} -> b =~ "Added to your" end)
    assert [{_, card}] = for({b, %{"kind" => "confirm"} = r} <- ps, do: {b, r})
    assert card["tool"] == "calendar_add" and card["origin"] == "offer"
  end

  ## 5. The note in each participant's language

  test "each participant's Risi Note is in their own language (card and REST)", ctx do
    # Shenika writes in Sinhala (her remembered Official messages), Harsha in English.
    for {u, text, i} <- [
          {ctx.shenika.user, "හෙට වැඩේ පටන් ගමු", 1},
          {ctx.shenika.user, "ඉන්වර්ටරය බ්‍රහස්පතින්දා", 2},
          {ctx.harsha.user, "ok, see you then", 3}
        ] do
      id = RisiMe.TimeUUID.at(DateTime.add(ctx.t0, -3600 + i))
      pt = Jason.encode!(%{"v" => 1, "type" => "text", "body" => text})
      :ok = Memory.put_message(ctx.og, %{message_id: id, sender_id: u.id, plaintext: pt})
    end

    model!(fn _ -> %{} end, fn
      "note_translate", body ->
        assert hd(body["messages"])["content"] =~ "Sinhala"

        %{
          "topic" => "ගාල්ල වැඩේ",
          "key_points" => ["පැනල් සවි කළා.", "ඉන්වර්ටරය බ්‍රහස්පතින්දා.", "ඊළඟට ඉන්වොයිසිය."]
        }

      _, _ ->
        %{"commitments" => []}
    end)

    out = discuss!(ctx)
    [h] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_h), do: r
    [{s_body, s}] = for {b, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_s), do: {b, r}

    assert h["language"] == "en" and h["topic"] == "Galle job"
    assert s["language"] == "si" and s["topic"] == "ගාල්ල වැඩේ"
    assert s["key_points"] == ["පැනල් සවි කළා.", "ඉන්වර්ටරය බ්‍රහස්පතින්දා.", "ඊළඟට ඉන්වොයිසිය."]
    assert s_body =~ "• පැනල් සවි කළා."
    assert h["note_id"] == s["note_id"]

    # One translation, sealed; REST shows each viewer their own.
    refute inspect(Repo.query!("SELECT * FROM risi_note_translations").rows) =~ "පැනල්"

    {200, sn} =
      RisiMe.GroupHelpers.api(
        :get,
        "/api/v1/risi/notes/#{s["note_id"]}",
        ctx.shenika.token,
        nil,
        ctx.sd
      )

    {200, hn} =
      RisiMe.GroupHelpers.api(
        :get,
        "/api/v1/risi/notes/#{s["note_id"]}",
        ctx.harsha.token,
        nil,
        ctx.hd
      )

    assert {sn["note"]["topic"], hn["note"]["topic"]} == {"ගාල්ල වැඩේ", "Galle job"}
    assert sn["note"]["language"] == "si"
  end
end
