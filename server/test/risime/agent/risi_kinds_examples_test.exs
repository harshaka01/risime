defmodule RisiMe.Agent.RisiKindsExamplesTest do
  @moduledoc """
  Hotfix 2026-10-14 (P1, the morning brief missing in the Risi chat): every Risi message kind the
  server posts has a `contract/v1/examples/envelope_risi_*.json`, so the Android test that renders
  every example (`RisiRenderTest`) covers it. A new `"kind" => "…"` literal in `server/lib` must be
  listed here as posted (and get an example) or as internal (a job, an item, an event), so a new
  kind can't reach phones unseen.
  """
  use ExUnit.Case, async: true

  @lib Path.expand("../../../lib", __DIR__)
  @examples Path.expand("../../../../contract/v1/examples", __DIR__)

  # The `risi.kind`s the server posts in a `text` envelope today (Out.post and friends).
  @posted ~w(
    action_confirm action_items answer calendar_invite calendar_reminder calendar_suggestion
    call_transcribe call_transcript call_transcript_compare commitment commitment_update confirm
    confirm_update digest discussion_card draft discussion_summary error escalation event_card
    event_update google_reconnect item_clarify item_due item_nudge item_overdue item_update
    message_sent needs_permission note_card notes_saved ops_alert reminder reminder_set report risi_call_transcript skill_done
    skill_needed summary voice_transcript
  )

  # Posted kinds without an example yet (a contract gap root fills; remove the entry with it).
  #   risi_call_transcript: proposal 2026-10-14-call-risi §41.5 (Android draws it: CallTranscript.ALT_KIND).
  @known_gaps ~w(risi_call_transcript)

  # `"kind" => "…"` literals in server/lib that are not Risi message kinds: Oban job args, item and
  # draft kinds, inbox/store rows.
  @internal ~w(
    action busy calendar_backfill calendar_changed calendar_pending chat_event committer creating
    call_transcribe_summary digest_sweep discussion_quiet dm_committer dm_ops dm_wake event expire
    extract follow_up forget grp item_expire item_reminder memory_ingest message name_pending
    offers_backfill pending_copies phone_event_added promise prune reminder_fire request resume
    risi_calendar_event scheduled_message stale_device_ops status undo_timeout voice_actions
    voice_delete wake
  )

  # Kinds built from a variable (e.g. `common(c, "item_due", to)` in LedgerReminders).
  @dynamic ~w(item_due item_nudge item_overdue)

  defp example_kinds do
    Path.wildcard(Path.join(@examples, "envelope_risi_*.json"))
    |> Enum.flat_map(fn f ->
      case Jason.decode!(File.read!(f)) do
        %{"risi" => %{"kind" => k}} -> [k]
        _ -> []
      end
    end)
    |> MapSet.new()
  end

  defp source_kinds do
    Path.wildcard(Path.join(@lib, "**/*.ex"))
    |> Enum.flat_map(fn f ->
      Regex.scan(~r/"kind" => "([a-z0-9_]+)"/, File.read!(f), capture: :all_but_first)
    end)
    |> List.flatten()
    |> MapSet.new()
  end

  test "every posted Risi kind has an envelope_risi_* example" do
    have = example_kinds()
    missing = Enum.reject(@posted -- @known_gaps, &MapSet.member?(have, &1))
    assert missing == [], "posted Risi kinds without a contract example: #{inspect(missing)}"
  end

  test "a known gap is removed once its example exists" do
    have = example_kinds()
    filled = Enum.filter(@known_gaps, &MapSet.member?(have, &1))
    assert filled == [], "drop these from @known_gaps: #{inspect(filled)}"
  end

  test "every \"kind\" literal in server/lib is listed as posted or internal" do
    listed = MapSet.new(@posted ++ @internal)
    unlisted = source_kinds() |> MapSet.difference(listed) |> Enum.sort()

    assert unlisted == [],
           "new \"kind\" literals: add each to @posted (with a contract example) or @internal: " <>
             inspect(unlisted)
  end

  test "every posted kind still appears in server/lib" do
    src = source_kinds()

    stale =
      Enum.reject(@posted -- @dynamic, &MapSet.member?(src, &1))

    assert stale == [], "no longer posted, drop from @posted: #{inspect(stale)}"
  end
end
