defmodule RisiMe.ContractExamplesTest do
  @moduledoc """
  Every file in contract/v1/examples/ is decoded and checked against the server's own
  encoders (by producing the same payload for real and comparing shape and formats) and
  decoders (by feeding the example in).
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.{Messaging, Repo}
  alias RisiMeWeb.{ApiJSON, InboxChannel, UserSocket}

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files @dir |> File.ls!() |> Enum.filter(&String.ends_with?(&1, ".json")) |> Enum.sort()
  @checked ~w(auth_verify_reply.json contacts_reply.json event_message.json event_status.json
              join_reply.json msg_send.json msg_send_reply.json presence_watch.json
              presence_watch_reply.json signal_presence.json signal_typing.json typing.json
              auth_config.json error_not_allowlisted.json error_invalid_token.json
              error_identity_conflict.json auth_refresh.json auth_refresh_reply.json
              auth_refresh_error.json auth_config_v14.json me_reply_unverified.json
              phone_verify_request_reply.json phone_verify_confirm.json
              error_phone_unverified.json error_invalid_code_attempts.json
              error_already_verified.json error_sms_unavailable.json device_put.json
              error_invalid_device.json push_inbox.json invite_create.json invite_reply.json
              invites_reply.json friend_request.json friend_request_reply.json friends_reply.json
              friend_accept_reply.json block.json signal_friend.json error_not_friends.json
              user_vouched.json
              device_put_mls.json device_put_mls_reply.json attestation_keys.json key_packages_upload.json key_packages_count.json key_packages_claim.json key_packages_claim_reply.json mls_group.json mls_commit_request.json mls_commit_reply.json mls_commits_reply.json error_epoch_conflict.json error_not_ready.json msg_send_e2ee.json event_message_e2ee.json event_mls_commit.json event_mls_welcome.json event_mls_membership.json signal_mls_key_packages_low.json error_e2ee_required.json
              reaction_payload.json msg_send_reaction.json msg_send_reaction_and_body.json
              event_reaction.json error_unknown_target.json error_invalid_emoji.json
              limits_graphemes.json)
  # v1.9 (MLS groups, §12): checked in the "v1.9" describe below.
  @checked_v1_9 ~w(group_create.json group_reply.json groups_reply.json group_members_add.json
                  group_role_patch.json group_reset.json group_reset_reply.json group_meta.json
                  group_receipts_reply.json blob_upload_reply.json device_put_groups.json
                  key_packages_upload_replace.json key_packages_claim_group.json
                  mls_commit_request_group.json mls_commits_reply_paged.json
                  friends_reply_v19.json msg_send_group.json msg_send_group_reply.json
                  typing_group.json signal_typing_group.json event_message_group.json
                  event_mls_commit_group_ref.json event_mls_welcome_ref.json
                  event_group_created.json event_group_added.json event_group_removed.json
                  event_group_left.json event_group_role_changed.json
                  event_group_metadata_changed.json event_group_add_expired.json
                  event_group_reset.json event_group_op.json event_group_receipt.json
                  error_not_member.json error_not_admin.json error_too_many_members.json
                  error_too_many_devices.json error_last_admin.json error_log_expired.json
                  error_generation_conflict.json error_not_ready_groups.json)
  # v1.10 (history, §13): checked by the tests named after them below.
  @checked_v1_10 ~w(inbox_join_reply_v110.json event_message_sender_copy.json
                   error_quota_exceeded.json)
  # v1.11 (encrypted images, §14): checked in the "v1.11" describe below. The `image` envelopes
  # and `group_meta_icon.json` travel inside MLS (the server never sees them): they are checked
  # against the §14.4 validation rules and the §14.3 size formula instead.
  @checked_v1_11 ~w(image_payload.json image_payload_no_thumb.json image_payload_png.json
                   image_payload_bad_key.json group_meta_icon.json blob_upload_media_reply.json
                   blob_usage_reply.json device_put_images.json mls_group_images_ready.json
                   error_not_e2ee.json error_storage_full.json error_bad_media_type.json)
  # v1.12 (deleting messages and chats, §15): checked in the "v1.12" describe below. The `delete`
  # envelopes travel inside MLS: they are checked against the §15.3 strict validation instead.
  @checked_v1_12 ~w(delete_payload.json delete_payload_bad.json msg_delete_everyone_group.json
                   msg_delete_everyone_dm.json msg_delete_me.json msg_delete_reply.json
                   msg_delete_reply_gone.json event_delete_group.json event_delete_dm.json
                   event_delete_dm_e2ee.json error_delete_too_old.json error_not_sender.json
                   chat_clear.json device_put_deletes.json mls_group_deletes_ready.json)
  # v1.13 (1:1 voice calls, §16): checked in the "v1.13" describe below. The call envelopes
  # travel inside MLS (the server never sees them): they are checked against the §16.2 rules.
  @checked_v1_13 ~w(call_offer_payload.json call_offer_payload_bad.json call_ringing_payload.json
                   call_answer_payload.json call_accepted_payload.json call_ice_payload.json
                   call_busy_payload.json call_cancel_payload.json call_end_payload.json
                   call_end_missed_payload.json call_signal_push.json call_signal_reply.json
                   call_signal_event.json calls_turn_reply.json push_call.json
                   device_put_calls.json mls_group_calls_ready.json error_calls_unavailable.json
                   error_calls_not_ready.json)
  # v1.14 (members restore an existing member's devices, §12.4a): checked below; the behaviour
  # in test/risime_web/controllers/groups_member_devices_test.exs.
  @checked_v1_14 ~w(device_put_member_devices.json)
  # v1.15 (history sharing, §17): checked in the "v1.15" describe below; the behaviour in
  # test/risime_web/channels/history_v115_test.exs. The envelopes and the bundle lines travel
  # inside MLS / the encrypted bundle: they are checked against the §17.6/§17.7 rules instead.
  @checked_v1_15 ~w(blob_upload_history_reply.json blob_usage_reply_history.json
                    device_put_history_share.json error_request_open.json
                    event_history_request.json event_history_request_closed.json
                    event_history_share.json event_history_status.json
                    event_history_status_refresh.json history_ack.json
                    history_bundle_entry.json history_bundle_header.json
                    history_cancel.json history_deliver.json
                    history_escalate.json history_refresh.json
                    history_request_payload.json history_request_push.json
                    history_request_reply.json history_respond.json
                    history_respond_stale.json history_share_payload.json)

  # v1.17 (profile photos, §18): checked in the "v1.17" describe below; the behaviour in
  # test/risime_web/controllers/profile_photos_v117_test.exs. The `profile_photo` envelopes
  # travel inside MLS: they are checked against the §18.1 strict validation instead.
  @checked_v1_17 ~w(blob_upload_avatar_reply.json event_message_silent.json msg_send_silent.json
                    profile_photo_payload.json profile_photo_payload_bad.json
                    profile_photo_payload_removed.json)

  # v1.18 (1:1 video calls, §19): checked in the "v1.18" describe below; the behaviour in
  # test/risime_web/channels/calls_v118_test.exs. The envelopes travel inside MLS: they are
  # checked against the §19.3/§19.4 rules instead.
  @checked_v1_18 ~w(call_end_video_payload.json call_media_payload.json
                    call_offer_video_payload.json call_offer_video_payload_bad.json
                    call_signal_event_video.json call_signal_push_video.json device_put_video.json
                    error_video_not_ready.json mls_group_video_ready.json)

  # v1.19 (group calls, §20): checked in the "v1.19" describe below; the behaviour in
  # test/risime_web/controllers/group_calls_v119_test.exs. The envelopes travel inside MLS: they
  # are checked against the §20.3/§20.4 rules instead.
  @checked_v1_19 ~w(call_member_payload.json call_offer_sfu_payload.json
                    call_signal_event_group.json call_signal_push_group.json calls_room_reply.json
                    calls_room_request.json calls_room_status_reply.json
                    device_put_group_calls.json error_call_ended.json error_call_full.json
                    group_call_ended_payload.json group_call_started_payload.json
                    livekit_token_claims.json mls_group_group_calls_ready.json)

  # v1.20 (open sign-up, §21): checked in the "v1.20" describe below; the behaviour in
  # test/risime_web/controllers/open_signup_test.exs.
  @checked_v1_20 ~w(auth_config_v120.json signup_request.json signup_reply.json
                    error_signup_required.json error_signup_closed.json error_phone_taken.json
                    error_signup_rate_limited.json error_bad_request.json friends_reply_v120.json
                    signal_friend_v120.json)

  # v1.21 (reinstalls without reset, §12.12; plus the v1.16 DM-op examples that were missing):
  # checked in the "v1.21" describe below; the behaviour in
  # test/risime_web/controllers/rejoin_v121_test.exs.
  @checked_v1_21 ~w(error_rejoin_pending.json event_group_op_cleanup.json event_mls_dm_op.json
                    group_rejoin_reply_v121.json mls_commit_request_dm_op.json
                    mls_dm_rejoin_reply.json mls_dm_rejoin_reply_v121.json)

  # v1.22 (encrypted backups, §22): checked in the "v1.22" describe below; the behaviour in
  # test/risime_web/controllers/backups_v122_test.exs. The file and bundle formats are the
  # clients' (the server never sees them): checked against the §22.4/§22.5 shapes instead.
  @checked_v1_22 ~w(auth_config_v122.json backup_bundle_header.json backup_create_reply.json
                    backup_create_request.json backup_entry_contact.json
                    backup_entry_conversation.json backup_entry_group_event.json
                    backup_entry_message.json backup_entry_tombstone.json backup_file_header.json
                    backup_key_put.json backup_key_reply.json backups_reply.json
                    blob_upload_backup_reply.json blob_usage_reply_backup.json
                    error_backup_device_mismatch.json error_backup_key_conflict.json
                    error_backup_unavailable.json error_no_backup_key.json)

  # v1.23 (voice/video switching and screen sharing, §23): checked in the "v1.23" describe below;
  # the behaviour in test/risime_web/controllers/call_switch_v123_test.exs. The 1:1 envelopes
  # travel inside MLS (the server never sees them): checked against the §23.2–§23.4 shapes.
  @checked_v1_23 ~w(call_answer_features_payload.json call_answer_renegotiate_payload.json
                    call_media_group_payload.json call_media_screen_payload.json
                    call_offer_features_payload.json call_offer_renegotiate_payload.json
                    call_offer_renegotiate_payload_bad.json call_switch_accept_payload.json
                    call_switch_group_payload.json call_switch_request_payload.json
                    call_switch_voice_payload.json calls_room_status_reply_v123.json
                    calls_room_upgrade_reply.json calls_room_upgrade_request.json
                    device_put_call_switch.json error_not_in_call.json
                    error_too_many_for_video.json livekit_token_claims_v123.json
                    livekit_update_participant.json)

  # v1.24 (two tabs and Risi stage 1, §24): checked in the "v1.24" describe below. Chat/Group
  # shapes are server-produced; the envelopes travel inside MLS (client-produced): structure only.
  @checked_v1_24 ~w(auth_config_v124.json
                    backup_bundle_header_v124.json
                    backup_entry_conversation_v124.json
                    chat_official_create.json
                    chat_official_create_reply.json
                    chat_patch_official.json
                    chat_reply.json
                    chats_reply.json
                    device_put_tabs.json
                    envelope_risi_action.json
                    envelope_risi_answer.json
                    envelope_risi_commitment.json
                    envelope_risi_commitment_update.json
                    envelope_risi_digest.json
                    envelope_risi_error.json
                    envelope_risi_escalation.json
                    envelope_risi_offer.json
                    envelope_risi_reminder.json
                    envelope_risi_report.json
                    envelope_risi_request.json
                    envelope_risi_summary.json
                    error_official_off.json
                    error_private_tab.json
                    event_chat_official_created.json
                    event_chat_official_off.json
                    event_chat_official_on.json
                    event_group_agent_added.json
                    event_group_agent_removed.json
                    event_group_created_official.json
                    group_meta_official.json
                    group_reply_v124.json
                    risi_commitments_reply.json
                    risi_facts_reply.json
                    risi_feedback.json)

  # v1.25 (Risi with tools, §25): checked in the "v1.25" describe below. Server-produced
  # replies against real responses where the server produces them; client formats (envelopes
  # inside MLS, tool results) and not-yet-produced events by structure.
  @checked_v1_25 ~w(auth_config_v125.json
                    chat_reply_risi.json
                    device_put_risi_tools.json
                    envelope_risi_action_confirm_write.json
                    envelope_risi_action_me_too.json
                    envelope_risi_answer_v2.json
                    envelope_risi_confirm.json
                    envelope_risi_draft.json
                    envelope_risi_reminder_set.json
                    error_tool_call_expired.json
                    event_risi_tool_call_calendar_add.json
                    event_risi_tool_call_calendar_check.json
                    risi_chat_create_reply.json
                    risi_facts_reply_v125.json
                    risi_tool_result_calendar_add.json
                    risi_tool_result_calendar_check.json
                    signal_risi_progress.json)

  # v1.26 (Risi skills, §26): checked in the "v1.26" describe below.
  @checked_v1_26 ~w(auth_config_v126.json
                    device_put_risi_skills.json
                    envelope_risi_action_calendar_accept.json
                    envelope_risi_action_calendar_decline.json
                    envelope_risi_calendar_offer.json
                    envelope_risi_confirm_schedule_message.json
                    envelope_risi_confirm_set_alarm.json
                    envelope_risi_skill_done.json
                    envelope_risi_skill_needed.json
                    error_skill_unavailable.json
                    error_undo_unavailable.json
                    event_risi_tool_call_calendar_remove.json
                    event_risi_tool_call_cancel_scheduled.json
                    event_risi_tool_call_schedule_message.json
                    event_risi_tool_call_set_alarm.json
                    risi_skill_activity_reply.json
                    risi_skill_activity_scheduled_reply.json
                    risi_skill_undo.json
                    risi_skill_undo_reply.json
                    risi_skills_patch.json
                    risi_skills_patch_reply.json
                    risi_skills_reply.json
                    risi_tool_result_calendar_remove.json
                    risi_tool_result_cancel_scheduled.json
                    risi_tool_result_schedule_message.json
                    risi_tool_result_set_alarm.json)

  # v1.27 (made_by, the Commitment Ledger follow-ups, call transcription, §27): checked in the
  # "v1.27" describe below; server-produced shapes against real responses where the server
  # produces them (auth/config, the device capability, the ledger envelopes and My promises in
  # test/risime/agent/ledger_*_test.exs); MLS envelopes and the call parts by structure.
  @checked_v1_27 ~w(auth_config_v127.json
                    call_offer_sfu_risi_payload.json
                    calls_room_reply_risi.json
                    calls_room_request_risi.json
                    calls_room_risi_stop.json
                    calls_room_risi_stop_reply.json
                    calls_room_status_reply_v127.json
                    device_put_risi_ledger.json
                    envelope_risi_action_item_confirm.json
                    envelope_risi_action_item_decline.json
                    envelope_risi_action_item_edit.json
                    envelope_risi_answer_made_by.json
                    envelope_risi_call_listen.json
                    envelope_risi_call_listen_stopped.json
                    envelope_risi_digest_personal.json
                    envelope_risi_discussion_card.json
                    envelope_risi_discussion_summary_call.json
                    envelope_risi_discussion_summary_chat.json
                    envelope_risi_item_due.json
                    envelope_risi_item_due_counterpart.json
                    envelope_risi_item_nudge.json
                    envelope_risi_item_overdue.json
                    envelope_risi_item_update.json
                    group_call_started_risi_payload.json
                    livekit_token_claims_risi.json
                    risi_commitments_reply_v127.json
                    signal_call_risi.json)

  # v1.28 (the Risi action loop, proactive offers, My promises, §28; plus the §27.13 examples):
  # checked in the "v1.28" describe below by structure; the server's real output against them in
  # test/risime/agent/{ledger_s23,ledger_s24,offers_items_8_10}_test.exs and
  # test/risime_web/controllers/risi_rest_s8_test.exs.
  @checked_v1_28 ~w(envelope_risi_action_confirm_write_edit.json
                    envelope_risi_confirm_calendar_add.json
                    envelope_risi_confirm_offer.json
                    envelope_risi_digest_personal_v128.json
                    envelope_risi_item_clarify.json
                    envelope_risi_request_period.json
                    envelope_risi_summary_period.json
                    risi_commitments_reply_v128.json
                    risi_facts_reply_summaries.json
                    risi_skills_patch_calendar.json
                    risi_tool_result_calendar_add_v128.json)

  # v1.29 (Risi Calendar, §29): every example is the server's real output, checked exactly in
  # test/contract/examples_v129_test.exs (which also regenerates them).
  @checked_v1_29 ~w(auth_config_v129.json device_put_risi_events.json
                    envelope_risi_action_confirm_write_edit_risi_calendar.json
                    envelope_risi_action_event_accept.json envelope_risi_action_event_suggest.json
                    envelope_risi_answer_calendar_sources_risi.json
                    envelope_risi_calendar_invite.json envelope_risi_calendar_reminder.json
                    envelope_risi_calendar_suggestion.json
                    envelope_risi_confirm_risi_calendar_add.json
                    envelope_risi_digest_personal_v129.json envelope_risi_event_card_added.json
                    envelope_risi_event_card_official.json envelope_risi_event_update.json
                    error_cursor_expired.json error_not_invitable.json
                    error_version_conflict.json event_risi_calendar_changed.json
                    risi_calendar_changes_reply.json risi_calendar_event_create.json
                    risi_calendar_event_create_reply.json risi_calendar_event_patch.json
                    risi_calendar_events_reply.json risi_calendar_respond_accept.json
                    risi_calendar_respond_suggest.json risi_calendar_settings.json
                    risi_calendar_suggestion_resolve.json)

  # v1.30 (Risi Notes, §30): every example is the server's real output, checked exactly in
  # test/contract/examples_v130_test.exs (which also regenerates them).
  @checked_v1_30 ~w(device_put_risi_notes.json envelope_risi_action_item_reopen.json
                    envelope_risi_note_card.json envelope_risi_notes_saved.json
                    risi_commitments_reply_v130.json risi_note_reply.json
                    risi_notes_reply.json)

  # v1.31 (Google Calendar link, §31): checked in test/contract/examples_v131_test.exs.
  @checked_v1_31 RisiMe.Contract.ExamplesV131Test.files()

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  @timeuuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()
  # What actually goes over the wire.
  defp wire(term), do: term |> Jason.encode!() |> Jason.decode!()
  defp keys(map), do: map |> Map.keys() |> Enum.sort()

  # v1.20 added `phone_confirmed` (absent = true) to User, Friend, incoming Request and the
  # friend signal: older examples are compared without it (checked in the "v1.20" describe).
  defp pre120(map) when is_map(map),
    do: map |> Map.delete("phone_confirmed") |> Map.new(fn {k, v} -> {k, pre120(v)} end)

  defp pre120(list) when is_list(list), do: Enum.map(list, &pre120/1)
  defp pre120(other), do: other

  # Same keys, and every value has the same "kind" (uuid / timeuuid / timestamp / type).
  defp assert_same_shape(ours, theirs) when is_map(ours) and is_map(theirs) do
    assert keys(ours) == keys(theirs)
    for {k, v} <- theirs, do: assert_same_shape(ours[k], v)
  end

  defp assert_same_shape(ours, theirs) when is_binary(theirs) do
    for re <- [@timeuuid, @uuid, @ts], Regex.match?(re, theirs) do
      assert ours =~ re, "#{inspect(ours)} should match #{inspect(re)} like #{theirs}"
    end

    assert is_binary(ours)
  end

  defp assert_same_shape(ours, theirs) when is_list(theirs), do: assert(is_list(ours))
  defp assert_same_shape(ours, theirs) when is_boolean(theirs), do: assert(is_boolean(ours))
  defp assert_same_shape(ours, theirs) when is_integer(theirs), do: assert(is_integer(ours))
  defp assert_same_shape(ours, nil), do: assert(ours == nil or is_binary(ours))

  test "every example file is covered by this test" do
    assert length(@files) > 0

    covered =
      @checked ++
        @checked_v1_9 ++
        @checked_v1_10 ++
        @checked_v1_11 ++
        @checked_v1_12 ++
        @checked_v1_13 ++
        @checked_v1_14 ++
        @checked_v1_15 ++
        @checked_v1_17 ++
        @checked_v1_18 ++
        @checked_v1_19 ++
        @checked_v1_20 ++
        @checked_v1_21 ++
        @checked_v1_22 ++
        @checked_v1_23 ++
        @checked_v1_24 ++
        @checked_v1_25 ++
        @checked_v1_26 ++
        @checked_v1_27 ++
        @checked_v1_28 ++
        @checked_v1_29 ++
        @checked_v1_30 ++
        @checked_v1_31

    assert @files -- covered == [], "add checks for: #{inspect(@files -- covered)}"
  end

  setup do
    a = logged_in_user(display_name: "A", company: "CodeGen")
    b = logged_in_user(display_name: "B", company: "Rise")
    befriend!(a, b)
    {:ok, sock_a} = connect(UserSocket, %{"token" => a.token})
    {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})
    {:ok, _, chan_a} = subscribe_and_join(sock_a, InboxChannel, "inbox:" <> a.user.id, %{})
    {:ok, _, chan_b} = subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})
    %{a: a, b: b, sock_b: sock_b, chan_a: chan_a, chan_b: chan_b}
  end

  test "auth_verify_reply.json", %{a: a} do
    ex = example("auth_verify_reply.json")
    assert is_binary(ex["token"])
    # v1.6 added `vouched_by`; examples written before it omit it (absent = null).
    assert ApiJSON.user(atomize(ex["user"])) |> wire() |> pre120() |> Map.delete("vouched_by") ==
             ex["user"]

    ours = pre120(wire(%{token: a.token, user: ApiJSON.user(a.user)}))
    assert ours["user"]["vouched_by"] == nil
    assert_same_shape(update_in(ours["user"], &Map.delete(&1, "vouched_by")), ex)
  end

  test "contacts_reply.json", %{a: a} do
    ex = example("contacts_reply.json")
    for c <- ex["contacts"], do: assert(ApiJSON.contact(atomize(c)) |> wire() == c)

    _unregistered = allowlist_entry()
    ours = wire(%{contacts: Enum.map(RisiMe.Accounts.list_contacts(a.user), &ApiJSON.contact/1)})

    for c <- ours["contacts"],
        do:
          assert_same_shape(c, Enum.find(ex["contacts"], &(&1["registered"] == c["registered"])))
  end

  test "msg_send.json is accepted by the server decoder", %{a: a, b: b, chan_a: chan_a} do
    ex = example("msg_send.json")
    # The example's recipient does not exist here: it parses, then fails on the recipient.
    assert {:error, :not_friends} = Messaging.send(a.user.id, ex)

    ref = push(chan_a, "msg:send", %{ex | "to" => b.user.id})
    assert_reply ref, :ok, reply
    assert_same_shape(wire(reply), example("msg_send_reply.json"))
  end

  test "msg_send_reply.json", %{chan_a: chan_a, b: b} do
    ref =
      push(chan_a, "msg:send", %{
        "client_msg_id" => Uniq.UUID.uuid4(),
        "to" => b.user.id,
        "body" => "x"
      })

    assert_reply ref, :ok, reply
    assert_same_shape(wire(reply), example("msg_send_reply.json"))
  end

  test "event_message.json and event_status.json", %{a: a, b: b, chan_a: chan_a, chan_b: chan_b} do
    ref =
      push(chan_a, "msg:send", %{
        "client_msg_id" => Uniq.UUID.uuid4(),
        "to" => b.user.id,
        "body" => "x"
      })

    assert_reply ref, :ok, %{message_id: id}

    topic_b = "inbox:" <> b.user.id
    assert_receive %Phoenix.Socket.Message{topic: ^topic_b, event: "event", payload: message}
    assert_same_shape(wire(message), example("event_message.json"))

    ref = push(chan_b, "msg:ack", %{"message_ids" => [id], "status" => "delivered"})
    assert_reply ref, :ok, %{}
    topic_a = "inbox:" <> a.user.id
    # v1.10 §13.1: the sender's copy comes first (event_message_sender_copy.json).
    assert_receive %Phoenix.Socket.Message{topic: ^topic_a, event: "event", payload: copy}
    assert_same_shape(wire(copy), example("event_message_sender_copy.json"))
    assert wire(copy) == wire(message)
    assert copy.data["from"] == a.user.id

    assert_receive %Phoenix.Socket.Message{topic: ^topic_a, event: "event", payload: status}
    assert_same_shape(wire(status), example("event_status.json"))

    # The decoders accept the example events: an ack for the example message id is a no-op.
    ex_id = example("event_message.json")["data"]["message_id"]
    ref = push(chan_b, "msg:ack", %{"message_ids" => [ex_id], "status" => "read"})
    assert_reply ref, :ok, %{}
  end

  test "join_reply.json", %{sock_b: sock_b, b: b} do
    ex = example("join_reply.json")

    {:ok, reply, _} =
      subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{"since" => nil})

    # v1.10 adds `history_before`, null on a socket without a device_id.
    assert %{"history_before" => nil} = wire(reply)
    assert_same_shape(wire(reply) |> Map.delete("history_before"), ex)
  end

  test "inbox_join_reply_v110.json: history_before on join and sync", %{b: b} do
    ex = example("inbox_join_reply_v110.json")
    {:ok, sock} = connect(UserSocket, %{"token" => b.token, "device_id" => Ecto.UUID.generate()})

    {:ok, reply, chan} =
      subscribe_and_join(sock, InboxChannel, "inbox:" <> b.user.id, %{"since" => nil})

    assert_same_shape(wire(reply), ex)
    ref = push(chan, "sync", %{"since" => nil})
    assert_reply ref, :ok, sync
    assert_same_shape(wire(sync), ex)
    assert sync.history_before == reply.history_before
  end

  test "error_quota_exceeded.json", %{a: a, b: b} do
    import RisiMe.GroupHelpers, only: [groups_device!: 1, api: 5, assert_status: 2]
    with_attestation_key(%{})
    a_dev = groups_device!(a)
    groups_device!(b)
    RisiMe.GroupHelpers.clear_legacy!()
    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]}

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    ex = example("error_quota_exceeded.json")
    limit = ex["error"]["limit"]
    used = ex["error"]["used"]
    # A blob row of `used` bytes (the quota counts metadata; no file needed).
    Repo.insert_all("blobs", [
      %{
        id: Ecto.UUID.dump!(Ecto.UUID.generate()),
        owner: Ecto.UUID.dump!(a.user.id),
        purpose: "mls",
        conversation_id: id,
        size: used,
        sha256: :crypto.hash(:sha256, ""),
        expires_at: DateTime.add(DateTime.utc_now(), 1, :day),
        inserted_at: DateTime.utc_now()
      }
    ])

    {413, err} =
      RisiMe.GroupHelpers.api_raw(
        "/api/v1/blobs?purpose=mls&conversation_id=#{id}",
        a.token,
        :binary.copy(<<0>>, limit - used + 1)
      )

    assert_same_shape(err, ex)
    assert err == ex
  end

  test "presence_watch.json and presence_watch_reply.json", %{chan_a: chan_a, a: a} do
    # The decoder accepts the example; its id isn't a registered user here, so it is left out.
    ref = push(chan_a, "presence:watch", example("presence_watch.json"))
    assert_reply ref, :ok, %{presences: []}

    %{user: c} = logged_in_user()
    befriend!(a.user, c)
    RisiMe.Accounts.touch_last_seen(c.id, DateTime.utc_now())
    ref = push(chan_a, "presence:watch", %{"user_ids" => [c.id]})
    assert_reply ref, :ok, reply
    ex = example("presence_watch_reply.json")
    assert_same_shape(wire(reply), ex)
    assert_same_shape(hd(wire(reply)["presences"]), hd(ex["presences"]))
  end

  test "signal_presence.json", %{chan_a: chan_a, a: a} do
    %{user: d, token: token} = logged_in_user()
    befriend!(a.user, d)
    ref = push(chan_a, "presence:watch", %{"user_ids" => [d.id]})
    assert_reply ref, :ok, _

    {:ok, sock_d} = connect(UserSocket, %{"token" => token})
    {:ok, _, _} = subscribe_and_join(sock_d, InboxChannel, "inbox:" <> d.id, %{})

    topic_a = "inbox:" <> a.user.id
    assert_receive %Phoenix.Socket.Message{topic: ^topic_a, event: "signal", payload: signal}
    ex = example("signal_presence.json")
    assert_same_shape(wire(signal), ex)
    assert wire(signal)["data"]["last_seen"] == nil
  end

  test "typing.json and signal_typing.json", %{a: a, b: b, chan_a: chan_a} do
    ex = example("typing.json")
    # The example's recipient does not exist here: it parses, then fails on the recipient.
    assert {:error, :not_friends} = Messaging.typing(a.user.id, ex)

    ref = push(chan_a, "typing", %{ex | "to" => b.user.id})
    assert_reply ref, :ok, %{}

    topic_b = "inbox:" <> b.user.id
    assert_receive %Phoenix.Socket.Message{topic: ^topic_b, event: "signal", payload: signal}
    assert_same_shape(wire(signal), example("signal_typing.json"))
    assert wire(signal)["data"]["conversation_id"] =~ ~r/^dm:[0-9a-f-]{36}_[0-9a-f-]{36}$/
  end

  ## v1.3 (§6)

  defp http, do: Phoenix.ConnTest.build_conn()

  defp get_json(path, token \\ nil) do
    conn =
      if token,
        do: Plug.Conn.put_req_header(http(), "authorization", "Bearer " <> token),
        else: http()

    conn = Phoenix.ConnTest.dispatch(conn, @endpoint, :get, path, nil)
    {conn.status, Jason.decode!(conn.resp_body)}
  end

  test "auth_config.json is what GET /auth/config returns with both modes" do
    # The v1.3 example predates `phone_verification` (v1.4), which clients may ignore.
    {200, ours} = get_json("/api/v1/auth/config")
    # v1.22 added `backup` (absent = off): checked in auth_config_v122.json.
    assert Map.drop(ours, ["phone_verification", "signup", "backup"]) ==
             example("auth_config.json")
  end

  test "error_invalid_token.json, error_not_allowlisted.json, error_identity_conflict.json" do
    RisiMe.Auth.clear_cache()
    assert get_json("/api/v1/me", "nope") == {401, example("error_invalid_token.json")}

    assert get_json("/api/v1/me", RisiMe.OIDCHelpers.access_token("nobody@example.com")) ==
             {403, example("error_not_allowlisted.json")}

    entry = allowlist_entry()

    {200, _} =
      get_json("/api/v1/me", RisiMe.OIDCHelpers.access_token(entry.email, %{"sub" => "x1"}))

    assert get_json("/api/v1/me", RisiMe.OIDCHelpers.access_token(entry.email, %{"sub" => "x2"})) ==
             {409, example("error_identity_conflict.json")}
  end

  test "auth_refresh.json, auth_refresh_reply.json and auth_refresh_error.json" do
    import RisiMe.OIDCHelpers
    entry = allowlist_entry()

    {:ok, sock} =
      connect(UserSocket, %{},
        connect_info: %{auth_token: access_token(entry.email, %{"sub" => "c1"})}
      )

    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> sock.assigns.user_id, %{})

    # The decoder accepts the example payload (its token is not a valid JWT here).
    ref = push(chan, "auth:refresh", example("auth_refresh.json"))
    assert_reply ref, :error, %{reason: "invalid_token"}

    ref = push(chan, "auth:refresh", %{"token" => access_token(entry.email, %{"sub" => "c1"})})
    assert_reply ref, :ok, reply
    assert_same_shape(wire(reply), example("auth_refresh_reply.json"))

    other = allowlist_entry()
    ref = push(chan, "auth:refresh", %{"token" => access_token(other.email)})
    assert_reply ref, :error, error
    assert wire(error) == example("auth_refresh_error.json")
  end

  ## v1.4 (§7)

  defp post_json(path, token, body) do
    conn =
      http()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
      |> Phoenix.ConnTest.dispatch(@endpoint, :post, path, body)

    {conn.status, Jason.decode!(conn.resp_body)}
  end

  describe "v1.4 with the phone gate on" do
    setup do
      RisiMe.Auth.clear_cache()
      Application.put_env(:risime, :phone_verification, :required)

      on_exit(fn ->
        Application.put_env(:risime, :phone_verification, :off)
        Application.put_env(:risime, :dev_local_auth, true)
        Application.put_env(:risime, :sms_mode, :test)
      end)

      entry = allowlist_entry()

      %{
        entry: entry,
        token: RisiMe.OIDCHelpers.access_token(entry.email, %{"sub" => "v14-" <> entry.phone})
      }
    end

    test "auth_config_v14.json" do
      Application.put_env(:risime, :dev_local_auth, false)
      {200, ours} = get_json("/api/v1/auth/config")
      # v1.20 added `signup` (absent = invite): checked in auth_config_v120.json.
      assert Map.drop(ours, ["signup", "backup"]) == example("auth_config_v14.json")
    end

    test "me_reply_unverified.json and error_phone_unverified.json", %{token: t} do
      ex = example("me_reply_unverified.json")

      assert ApiJSON.user(atomize(ex["user"])) |> wire() |> pre120() |> Map.delete("vouched_by") ==
               ex["user"]

      {200, ours} = get_json("/api/v1/me", t)
      assert_same_shape(update_in(pre120(ours)["user"], &Map.delete(&1, "vouched_by")), ex)
      assert ours["user"]["phone_verified"] == false

      assert get_json("/api/v1/contacts", t) == {403, example("error_phone_unverified.json")}
    end

    test "phone_verify_request_reply.json, phone_verify_confirm.json, error_invalid_code_attempts.json, error_already_verified.json",
         %{entry: e, token: t} do
      {200, reply} = post_json("/api/v1/me/phone/verify/request", t, %{})
      ex = example("phone_verify_request_reply.json")
      assert_same_shape(reply, ex)
      assert reply["to"] =~ ~r/^\+9477(•)+\d\d$/u and ex["to"] =~ ~r/^\+9477(•)+\d\d$/u
      assert_receive {:otp, :sms, phone, %{code: code}}
      assert phone == e.phone

      # The decoder accepts the example payload (its code is wrong here: two wrong tries).
      confirm = example("phone_verify_confirm.json")
      wrong = if confirm["code"] == code, do: "000000", else: confirm["code"]
      {401, _} = post_json("/api/v1/me/phone/verify/confirm", t, %{"code" => wrong})

      assert post_json("/api/v1/me/phone/verify/confirm", t, %{"code" => wrong}) ==
               {401, example("error_invalid_code_attempts.json")}

      {200, %{"user" => %{"phone_verified" => true}}} =
        post_json("/api/v1/me/phone/verify/confirm", t, %{"code" => code})

      assert post_json("/api/v1/me/phone/verify/request", t, %{}) ==
               {409, example("error_already_verified.json")}
    end

    test "error_sms_unavailable.json", %{token: t} do
      Application.put_env(:risime, :sms_mode, :log)

      ExUnit.CaptureLog.capture_log(fn ->
        assert post_json("/api/v1/me/phone/verify/request", t, %{}) ==
                 {503, example("error_sms_unavailable.json")}
      end)
    end
  end

  ## v1.5 (§8)

  test "device_put.json is accepted; error_invalid_device.json is the 422 body", %{a: a} do
    put = fn id, body ->
      conn =
        http()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> a.token)
        |> Phoenix.ConnTest.dispatch(@endpoint, :put, "/api/v1/me/devices/#{id}", body)

      {conn.status, conn.resp_body}
    end

    assert {204, ""} = put.(Ecto.UUID.generate(), example("device_put.json"))

    {422, body} = put.("not-a-uuid", example("device_put.json"))
    assert Jason.decode!(body) == example("error_invalid_device.json")
  end

  test "push_inbox.json is the only push payload, and FCM sends exactly it as data" do
    assert RisiMe.Push.payload() == example("push_inbox.json")

    assert RisiMe.Push.FCM.message("t", RisiMe.Push.payload())
           |> wire()
           |> get_in(["message", "data"]) ==
             example("push_inbox.json")
  end

  ## v1.6 (§9)

  defp req_json(method, path, token, body \\ nil) do
    conn =
      http()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
      |> Phoenix.ConnTest.dispatch(@endpoint, method, path, body)

    {conn.status, if(conn.resp_body == "", do: nil, else: Jason.decode!(conn.resp_body))}
  end

  describe "v1.6" do
    test "invite_create.json, invite_reply.json, invites_reply.json", %{a: a} do
      {201, reply} = req_json(:post, "/api/v1/invites", a.token, example("invite_create.json"))
      assert_same_shape(reply, example("invite_reply.json"))
      assert reply["invite"]["link"] == example("invite_reply.json")["invite"]["link"]
      assert reply["invite"]["subject"] == example("invite_reply.json")["invite"]["subject"]

      {200, list} = req_json(:get, "/api/v1/invites", a.token)
      assert_same_shape(hd(list["invites"]), hd(example("invites_reply.json")["invites"]))
    end

    test "friend_request.json, friend_request_reply.json, friends_reply.json, friend_accept_reply.json, block.json",
         %{a: a} do
      ex = example("friends_reply.json")
      d = logged_in_user(display_name: "D")
      f = logged_in_user(display_name: "F")

      assert req_json(:post, "/api/v1/friends/requests", a.token, example("friend_request.json")) ==
               {202, example("friend_request_reply.json")}

      # incoming from d, outgoing to a new phone, a blocked user, and b (setup) as a friend.
      {202, _} = req_json(:post, "/api/v1/friends/requests", d.token, %{"phone" => a.user.phone})

      {202, _} =
        req_json(:post, "/api/v1/friends/requests", a.token, %{"phone" => unique_phone()})

      assert {204, nil} =
               req_json(:post, "/api/v1/blocks", a.token, %{
                 example("block.json")
                 | "user_id" => f.user.id
               })

      {200, ours} = req_json(:get, "/api/v1/friends", a.token)
      ours = pre120(ours)
      assert keys(ours) == keys(ex)
      # v1.9 added `group_ready` (absent = false in v1.6 examples): checked in friends_reply_v19.
      ours = update_in(ours["friends"], fn fs -> Enum.map(fs, &Map.delete(&1, "group_ready")) end)
      for k <- ~w(friends incoming blocked), do: assert_same_shape(hd(ours[k]), hd(ex[k]))
      assert Enum.all?(ours["outgoing"], &(&1["user_id"] == nil and &1["display_name"] == nil))
      assert_same_shape(hd(ours["outgoing"]), hd(ex["outgoing"]))

      [%{"id" => id}] = ours["incoming"]
      {200, accepted} = req_json(:post, "/api/v1/friends/requests/#{id}/accept", a.token)
      assert_same_shape(pre120(accepted), example("friend_accept_reply.json"))
    end

    test "signal_friend.json", %{a: a} do
      d = logged_in_user(display_name: "D")
      Phoenix.PubSub.subscribe(RisiMe.PubSub, Messaging.topic(a.user.id))
      {202, _} = req_json(:post, "/api/v1/friends/requests", d.token, %{"phone" => a.user.phone})
      assert_receive {:signal, %{kind: "friend"} = signal}
      assert_same_shape(pre120(wire(signal)), example("signal_friend.json"))
    end

    test "error_not_friends.json", %{chan_a: chan_a} do
      %{user: stranger} = logged_in_user()

      ref =
        push(chan_a, "msg:send", %{
          "client_msg_id" => Uniq.UUID.uuid4(),
          "to" => stranger.id,
          "body" => "x"
        })

      assert_reply ref, :error, error
      assert wire(error) == example("error_not_friends.json")
    end

    test "user_vouched.json", %{a: a} do
      RisiMe.Auth.clear_cache()

      {201, _} =
        req_json(:post, "/api/v1/invites", a.token, %{
          "phone" => unique_phone(),
          "email" => "vouched@example.com",
          "name" => "V"
        })

      {200, ours} =
        req_json(:get, "/api/v1/me", RisiMe.OIDCHelpers.access_token("vouched@example.com"))

      ex = example("user_vouched.json")
      # The example predates nothing else but omits phone_verified (absent = true).
      assert_same_shape(update_in(pre120(ours)["user"], &Map.delete(&1, "phone_verified")), ex)
      assert ours["user"]["company"] == ""
    end
  end

  ## v1.7 (§10). MLS blobs are opaque placeholders; the server checks shapes and routing only.

  describe "v1.7" do
    setup :with_attestation_key

    defp mls_req(method, path, token, body, device) do
      conn =
        http()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
        |> Plug.Conn.put_req_header("x-device-id", device)
        |> Phoenix.ConnTest.dispatch(@endpoint, method, path, body)

      {conn.status, if(conn.resp_body == "", do: nil, else: Jason.decode!(conn.resp_body))}
    end

    defp stored(user_id, kind) do
      {:ok, events, _} = Messaging.fetch_events(user_id, nil)
      events |> Enum.filter(&(&1.kind == kind)) |> List.last() |> wire()
    end

    test "devices, attestation keys, key packages, claim", %{a: a, b: b} do
      a_dev = Ecto.UUID.generate()

      {200, reply} =
        mls_req(
          :put,
          "/api/v1/me/devices/#{a_dev}",
          a.token,
          example("device_put_mls.json"),
          a_dev
        )

      assert_same_shape(reply, example("device_put_mls_reply.json"))

      {200, %{"keys" => [ours]}} = get_json("/api/v1/mls/attestation_keys")

      assert_same_shape(ours, hd(example("attestation_keys.json")["keys"]))

      b_dev = mls_device!(b)

      {204, nil} =
        mls_req(
          :post,
          "/api/v1/me/devices/#{b_dev}/key_packages",
          b.token,
          example("key_packages_upload.json"),
          b_dev
        )

      {200, count} =
        mls_req(:get, "/api/v1/me/devices/#{b_dev}/key_packages/count", b.token, nil, b_dev)

      assert_same_shape(count, example("key_packages_count.json"))

      :ok = RisiMe.MLS.record_instance(b.user.id, nil, "jwt", nil)
      claim = %{example("key_packages_claim.json") | "user_ids" => [b.user.id]}

      {200, %{"devices" => devices}} =
        mls_req(:post, "/api/v1/mls/key_packages/claim", a.token, claim, a_dev)

      [ex_mls, ex_legacy] = example("key_packages_claim_reply.json")["devices"]
      assert_same_shape(Enum.find(devices, & &1["mls"]), ex_mls)
      assert_same_shape(Enum.find(devices, &(!&1["mls"])), ex_legacy)
    end

    test "groups, commits, conflicts, readiness", %{a: a, b: b} do
      a_dev = mls_device!(a)
      b_dev = mls_device!(b)
      conv = Messaging.conversation_id(a.user.id, b.user.id)

      :ok = RisiMe.MLS.record_instance(b.user.id, nil, "jwt", nil)
      {200, view} = mls_req(:get, "/api/v1/mls/groups/#{conv}", a.token, nil, a_dev)
      ex = example("mls_group.json")
      # v1.11 adds images_ready / missing_images (absent = false; mls_group_images_ready.json),
      # v1.12 deletes_ready / missing_deletes (mls_group_deletes_ready.json), v1.13 calls_ready /
      # missing_calls (DMs; mls_group_calls_ready.json).
      assert keys(
               Map.drop(
                 view,
                 ~w(images_ready missing_images deletes_ready missing_deletes calls_ready missing_calls video_ready missing_video group_calls_ready missing_group_calls)
               )
             ) ==
               keys(ex)

      assert_same_shape(hd(view["missing"]), hd(ex["missing"]))

      body = %{
        example("mls_commit_request.json")
        | "added" => [%{"user_id" => b.user.id, "device_id" => b_dev}]
      }

      {409, not_ready} = mls_req(:post, "/api/v1/mls/groups/#{conv}/commit", a.token, body, a_dev)
      assert_same_shape(not_ready, example("error_not_ready.json"))
      assert not_ready["error"]["message"] == example("error_not_ready.json")["error"]["message"]

      # The setup's sockets are pre-v1.7 (no device_id): clear every legacy instance.
      Repo.delete_all(from i in "app_instances", where: like(i.instance_key, "legacy:%"))

      assert {200, reply} =
               mls_req(:post, "/api/v1/mls/groups/#{conv}/commit", a.token, body, a_dev)

      assert reply == example("mls_commit_reply.json")

      {200, commits} =
        mls_req(:get, "/api/v1/mls/groups/#{conv}/commits?since_epoch=0", a.token, nil, a_dev)

      assert_same_shape(hd(commits["commits"]), hd(example("mls_commits_reply.json")["commits"]))

      {409, conflict} = mls_req(:post, "/api/v1/mls/groups/#{conv}/commit", a.token, body, a_dev)
      assert_same_shape(conflict, example("error_epoch_conflict.json"))

      assert conflict["error"]["message"] ==
               example("error_epoch_conflict.json")["error"]["message"]

      assert_same_shape(stored(b.user.id, "mls_commit"), example("event_mls_commit.json"))
      assert_same_shape(stored(b.user.id, "mls_welcome"), example("event_mls_welcome.json"))

      {204, nil} = mls_req(:delete, "/api/v1/me/devices/#{b_dev}", b.token, nil, b_dev)
      assert_same_shape(stored(a.user.id, "mls_membership"), example("event_mls_membership.json"))
    end

    test "msg_send_e2ee.json, event_message_e2ee.json, error_e2ee_required.json, signal_mls_key_packages_low.json",
         %{a: a, b: b} do
      a_dev = mls_device!(a)
      b_dev = mls_device!(b)
      e2ee_group!(a, b)
      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})

      ref = push(chan, "msg:send", %{example("msg_send_e2ee.json") | "to" => b.user.id})
      assert_reply ref, :ok, %{message_id: id}
      ev = stored(b.user.id, "message")
      assert ev["event_id"] == id
      assert_same_shape(ev, example("event_message_e2ee.json"))

      ref =
        push(chan, "msg:send", %{
          "client_msg_id" => Uniq.UUID.uuid4(),
          "to" => b.user.id,
          "body" => "x"
        })

      assert_reply ref, :error, error
      assert wire(error) == example("error_e2ee_required.json")

      {204, nil} =
        mls_req(
          :post,
          "/api/v1/me/devices/#{b_dev}/key_packages",
          b.token,
          %{"key_packages" => [b64(), b64()]},
          b_dev
        )

      Phoenix.PubSub.subscribe(RisiMe.PubSub, Messaging.topic(b.user.id))

      {200, _} =
        mls_req(
          :post,
          "/api/v1/mls/key_packages/claim",
          a.token,
          %{"user_ids" => [b.user.id]},
          a_dev
        )

      assert_receive {:signal, %{kind: "mls_key_packages_low"} = signal}
      assert_same_shape(wire(signal), example("signal_mls_key_packages_low.json"))
    end
  end

  ## v1.8 (§11)

  describe "v1.8" do
    defp send_payload(chan, payload) do
      ref = push(chan, "msg:send", payload)

      receive do
        %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
      after
        2000 -> flunk("no reply")
      end
    end

    test "limits_graphemes.json: every case and the generated 4096/4097 rule", %{
      b: b,
      chan_a: chan
    } do
      ex = example("limits_graphemes.json")
      assert ex["max_graphemes"] == 4096 and ex["max_bytes"] == 16 * 1024

      for c <- ex["cases"], do: assert(String.length(c["text"]) == c["graphemes"], c["name"])

      for g <- ex["generated"] do
        text = String.duplicate(g["repeat"], g["count"])
        assert String.length(text) == g["count"]

        reply =
          send_payload(chan, %{
            "client_msg_id" => Uniq.UUID.uuid4(),
            "to" => b.user.id,
            "body" => text
          })

        if g["ok"],
          do: assert({:ok, _} = reply),
          else: assert({:error, %{reason: "too_long"}} = reply)
      end
    end

    test "reaction_payload.json is a valid envelope by the server's rules" do
      ex = example("reaction_payload.json")

      assert %{"v" => 1, "type" => "reaction", "op" => op, "target" => target, "emoji" => emoji} =
               ex

      assert op in ["add", "remove"] and target =~ @timeuuid
      assert Messaging.valid_emoji?(emoji)
    end

    test "msg_send_reaction.json, event_reaction.json, error_*; msg_send_reaction_and_body.json",
         %{b: b, chan_a: chan} do
      {:ok, %{message_id: target}} =
        send_payload(chan, %{
          "client_msg_id" => Uniq.UUID.uuid4(),
          "to" => b.user.id,
          "body" => "hi"
        })

      ex = example("msg_send_reaction.json")
      reaction = %{ex["reaction"] | "target" => target}

      assert {:ok, %{message_id: rid}} =
               send_payload(chan, %{ex | "to" => b.user.id, "reaction" => reaction})

      {:ok, events, _} = Messaging.fetch_events(b.user.id, nil)
      ev = events |> Enum.find(&(&1.event_id == rid)) |> wire()
      assert_same_shape(ev, example("event_reaction.json"))

      assert {:error, e} =
               send_payload(chan, %{ex | "to" => b.user.id, "client_msg_id" => Uniq.UUID.uuid4()})

      assert wire(e) == example("error_unknown_target.json")

      bad = %{reaction | "emoji" => "no"}

      assert {:error, e} =
               send_payload(chan, %{
                 ex
                 | "to" => b.user.id,
                   "client_msg_id" => Uniq.UUID.uuid4(),
                   "reaction" => bad
               })

      assert wire(e) == example("error_invalid_emoji.json")

      both = example("msg_send_reaction_and_body.json")
      assert {:error, %{reason: "bad_request"}} = send_payload(chan, %{both | "to" => b.user.id})
    end
  end

  ## v1.9 (§12)

  describe "v1.9" do
    setup :with_attestation_key

    defp g_api(method, path, token, body \\ nil, device \\ nil),
      do: RisiMe.GroupHelpers.api(method, path, token, body, device)

    defp g_last(user_id, kind), do: RisiMe.GroupHelpers.last_event(user_id, kind)

    # Members/receipts: compare with the example entry of the same phone/null shape.
    defp assert_entries(ours, theirs, key) do
      for o <- ours do
        t = Enum.find(theirs, &(is_nil(&1[key]) == is_nil(o[key]))) || hd(theirs)
        assert_same_shape(Map.put(o, key, t[key] && o[key]), Map.put(t, key, o[key] && t[key]))
      end
    end

    defp assert_group(ours, ex) do
      assert keys(ours) == keys(ex)
      assert_same_shape(Map.drop(ours, ~w(members pending)), Map.drop(ex, ~w(members pending)))
      assert_entries(ours["members"], ex["members"], "phone")
      assert_entries(ours["members"], ex["members"], "joined_at")
      for p <- ours["pending"], do: assert_same_shape(p, hd(ex["pending"]))
    end

    defp assert_error(ours, name, dynamic \\ []) do
      ex = example(name)
      assert_same_shape(ours, ex)
      assert Map.drop(ours["error"], dynamic) == Map.drop(ex["error"], dynamic)
    end

    test "every §12 example against the server's real payloads", %{a: a, b: b} do
      import RisiMe.GroupHelpers, only: [groups_device!: 1, ref: 2, create_commit: 2]

      clear = fn ->
        Repo.delete_all(from i in "app_instances", where: like(i.instance_key, "legacy:%"))
      end

      # device_put_groups.json, key_packages_upload_replace.json
      a_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        g_api(:put, "/api/v1/me/devices/#{a_dev}", a.token, example("device_put_groups.json"))

      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")
      assert %{capabilities: ["groups"]} = Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)

      {204, nil} =
        g_api(
          :post,
          "/api/v1/me/devices/#{a_dev}/key_packages",
          a.token,
          %{"key_packages" => [b64(), b64()]},
          a_dev
        )

      {204, nil} =
        g_api(
          :post,
          "/api/v1/me/devices/#{a_dev}/key_packages",
          a.token,
          example("key_packages_upload_replace.json"),
          a_dev
        )

      assert {:ok, 1} = RisiMe.MLS.key_package_count(a.user.id, a_dev)

      b_dev = groups_device!(b)
      kamal = logged_in_user(display_name: "Kamal")
      befriend!(a, kamal)
      k_old = Ecto.UUID.generate()
      groups_device!(kamal)

      {:ok, nil} =
        RisiMe.Devices.register(kamal.user.id, k_old, %{
          "platform" => "android",
          "push_token" => "t-#{k_old}"
        })

      :ok = RisiMe.MLS.record_instance(kamal.user.id, k_old, nil, "0.2.0")
      clear.()

      # friends_reply_v19.json
      {200, friends} = g_api(:get, "/api/v1/friends", a.token)
      friends = pre120(friends)
      ex = example("friends_reply_v19.json")
      assert keys(friends) == keys(ex)
      for f <- friends["friends"], do: assert_same_shape(f, hd(ex["friends"]))
      assert Enum.find(friends["friends"], &(&1["user_id"] == b.user.id))["group_ready"]
      refute Enum.find(friends["friends"], &(&1["user_id"] == kamal.user.id))["group_ready"]

      # error_not_ready_groups.json
      create = %{example("group_create.json") | "member_ids" => [b.user.id, kamal.user.id]}
      {409, err} = g_api(:post, "/api/v1/groups", a.token, create, a_dev)
      assert_same_shape(err, example("error_not_ready_groups.json"))
      assert err["error"]["message"] == example("error_not_ready_groups.json")["error"]["message"]

      assert_same_shape(
        hd(err["error"]["missing"]),
        hd(example("error_not_ready_groups.json")["error"]["missing"])
      )

      # error_too_many_members.json
      many = %{create | "member_ids" => for(_ <- 1..256, do: Ecto.UUID.generate())}
      {422, err} = g_api(:post, "/api/v1/groups", a.token, many, a_dev)
      assert_error(err, "error_too_many_members.json")

      # error_too_many_devices.json: 77 friends with 10 groups devices each (> 768).
      now = DateTime.utc_now()

      big =
        for _ <- 1..77 do
          u = RisiMe.GroupHelpers.fast_user!()
          befriend!(a, u)

          Repo.insert_all(
            RisiMe.Devices.Device,
            for _ <- 1..10 do
              %{
                user_id: u.id,
                device_id: Ecto.UUID.generate(),
                platform: "android",
                mls_signature_key: :crypto.strong_rand_bytes(32),
                mls_attestation: "x",
                capabilities: ["groups"],
                last_seen_at: now,
                inserted_at: now,
                updated_at: now
              }
            end
          )

          u.id
        end

      {422, err} = g_api(:post, "/api/v1/groups", a.token, %{create | "member_ids" => big}, a_dev)
      assert_error(err, "error_too_many_devices.json")

      # group_create.json → 201, then the epoch-0 commit.
      {201, %{"group" => %{"id" => id} = g}} =
        g_api(:post, "/api/v1/groups", a.token, %{create | "member_ids" => [b.user.id]}, a_dev)

      assert g["state"] == "creating"

      # key_packages_claim_group.json
      :ok = RisiMe.MLS.upload_key_packages(b.user.id, b_dev, %{"key_packages" => [b64()]})

      claim = %{
        example("key_packages_claim_group.json")
        | "user_ids" => [b.user.id],
          "conversation_id" => id
      }

      {200, %{"devices" => [dev]}} =
        g_api(:post, "/api/v1/mls/key_packages/claim", a.token, claim, a_dev)

      assert_same_shape(dev, hd(example("key_packages_claim_reply.json")["devices"]))

      {200, %{"epoch" => 1}} =
        g_api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          create_commit([a.user.id, b.user.id], {a.user.id, a_dev}),
          a_dev
        )

      assert_same_shape(g_last(b.user.id, "group_event"), example("event_group_created.json"))

      assert_entries(
        g_last(b.user.id, "group_event")["data"]["members"],
        example("event_group_created.json")["data"]["members"],
        "phone"
      )

      # error_not_admin.json; group_members_add.json → group_reply.json / groups_reply.json
      nimal = logged_in_user(display_name: "Nimal")
      befriend!(a, nimal)
      n_dev = groups_device!(nimal)
      add = %{example("group_members_add.json") | "user_ids" => [nimal.user.id]}
      {403, err} = g_api(:post, "/api/v1/groups/#{id}/members", b.token, add, b_dev)
      assert_error(err, "error_not_admin.json")

      {200, _} = g_api(:post, "/api/v1/groups/#{id}/members", a.token, add, a_dev)
      {200, %{"group" => gb}} = g_api(:get, "/api/v1/groups/#{id}", b.token)
      assert_group(gb, example("group_reply.json")["group"])
      {200, %{"groups" => [gl]}} = g_api(:get, "/api/v1/groups", b.token)
      assert_group(gl, hd(example("groups_reply.json")["groups"]))

      # event_group_op.json
      op_ev = g_last(a.user.id, "group_op")
      assert_same_shape(op_ev, example("event_group_op.json"))
      op_id = op_ev["data"]["op"]["op_id"]

      # blob_upload_reply.json, then mls_commit_request_group.json with a welcome_ref.
      bytes = :crypto.strong_rand_bytes(70_000)

      {201, up} =
        RisiMe.GroupHelpers.api_raw(
          "/api/v1/blobs?purpose=mls&conversation_id=#{id}",
          a.token,
          bytes
        )

      assert_same_shape(up, example("blob_upload_reply.json"))

      req = %{
        example("mls_commit_request_group.json")
        | "epoch" => 1,
          "welcome_ref" => Map.take(up, ~w(blob_id size sha256)),
          "added" => [ref(nimal.user.id, n_dev)],
          "op_id" => op_id
      }

      {200, %{"epoch" => 2}} =
        g_api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, req, a_dev)

      assert_same_shape(
        g_last(nimal.user.id, "mls_welcome"),
        example("event_mls_welcome_ref.json")
      )

      assert_same_shape(g_last(b.user.id, "group_event"), example("event_group_added.json"))

      # event_mls_commit_group_ref.json (a self-update by reference)
      {201, up2} =
        RisiMe.GroupHelpers.api_raw(
          "/api/v1/blobs?purpose=mls&conversation_id=#{id}",
          a.token,
          bytes
        )

      ref_commit = %{
        "generation" => 1,
        "epoch" => 2,
        "commit" => nil,
        "commit_ref" => Map.take(up2, ~w(blob_id size sha256))
      }

      {200, %{"epoch" => 3}} =
        g_api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, ref_commit, a_dev)

      assert_same_shape(
        g_last(b.user.id, "mls_commit"),
        example("event_mls_commit_group_ref.json")
      )

      # mls_commits_reply_paged.json
      {200, first} =
        g_api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=0&limit=1", a.token)

      assert first["has_more"]
      {200, page} = g_api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=1&limit=2", a.token)
      ex = example("mls_commits_reply_paged.json")
      assert keys(page) == keys(ex)
      [inline, by_ref] = page["commits"]
      assert_same_shape(inline, hd(ex["commits"]))
      assert_same_shape(by_ref, List.last(ex["commits"]))

      # msg_send_group.json → msg_send_group_reply.json, event_message_group.json
      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
      send = %{example("msg_send_group.json") | "conversation_id" => id, "epoch" => 3}
      ref = push(chan, "msg:send", send)
      assert_reply ref, :ok, reply
      assert_same_shape(wire(reply), example("msg_send_group_reply.json"))
      mid = reply.message_id
      assert_same_shape(g_last(b.user.id, "message"), example("event_message_group.json"))

      # typing_group.json → signal_typing_group.json
      Phoenix.PubSub.subscribe(RisiMe.PubSub, Messaging.topic(b.user.id))
      ref = push(chan, "typing", %{example("typing_group.json") | "conversation_id" => id})
      assert_reply ref, :ok, %{}
      assert_receive {:signal, %{kind: "typing"} = signal}
      assert_same_shape(wire(signal), example("signal_typing_group.json"))

      # error_not_member.json
      ref =
        push(chan, "msg:send", %{
          send
          | "conversation_id" => "grp:" <> Ecto.UUID.generate(),
            "client_msg_id" => Ecto.UUID.generate()
        })

      assert_reply ref, :error, err
      assert wire(err) == example("error_not_member.json")

      # event_group_receipt.json, group_receipts_reply.json
      :ok = Messaging.ack(b.user.id, [mid], "read")
      assert_same_shape(g_last(a.user.id, "group_receipt"), example("event_group_receipt.json"))
      {200, receipts} = g_api(:get, "/api/v1/groups/#{id}/messages/#{mid}/receipts", a.token)
      ex = example("group_receipts_reply.json")
      assert keys(receipts) == keys(ex)
      assert_entries(receipts["receipts"], ex["receipts"], "read_at")

      # group_role_patch.json → event_group_role_changed.json; then metadata_changed
      {200, %{"group" => %{"pending" => [role_op]} = gr}} =
        g_api(
          :patch,
          "/api/v1/groups/#{id}/members/#{b.user.id}",
          a.token,
          example("group_role_patch.json"),
          a_dev
        )

      assert_group(gr, example("group_reply.json")["group"])

      role_commit = %{
        "generation" => 1,
        "epoch" => 3,
        "commit" => b64(),
        "op_id" => role_op["op_id"],
        "meta_changed" => true
      }

      {200, %{"epoch" => 4}} =
        g_api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, role_commit, a_dev)

      assert_same_shape(
        g_last(nimal.user.id, "group_event"),
        example("event_group_role_changed.json")
      )

      {200, _} =
        g_api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          %{role_commit | "epoch" => 4, "op_id" => nil},
          a_dev
        )

      assert_same_shape(
        g_last(nimal.user.id, "group_event"),
        example("event_group_metadata_changed.json")
      )

      # group_meta.json: the server never sees it; its admin list is a list of user ids.
      meta = example("group_meta.json")
      assert meta["v"] == 1 and String.length(meta["name"]) in 1..100 and meta["icon"] == nil
      assert Enum.all?(meta["admins"], &(&1 =~ @uuid))

      # event_group_left.json: Nimal leaves; admin b commits the removal.
      {204, nil} = g_api(:post, "/api/v1/groups/#{id}/leave", nimal.token, nil, n_dev)
      [leave_op] = RisiMe.Groups.Ops.list(id)

      leave = %{
        "generation" => 1,
        "epoch" => 5,
        "commit" => b64(),
        "removed" => [ref(nimal.user.id, n_dev)],
        "op_id" => leave_op.op_id
      }

      {200, %{"epoch" => 6}} =
        g_api(:post, "/api/v1/mls/groups/#{id}/commit", b.token, leave, b_dev)

      assert_same_shape(g_last(nimal.user.id, "group_event"), example("event_group_left.json"))

      # event_group_removed.json: a removes b (a is the creator, so may remove an admin).
      {204, nil} =
        g_api(:delete, "/api/v1/groups/#{id}/members/#{b.user.id}", a.token, nil, a_dev)

      [rm_op] = RisiMe.Groups.Ops.list(id)
      rm = %{leave | "epoch" => 6, "removed" => [ref(b.user.id, b_dev)], "op_id" => rm_op.op_id}
      {200, %{"epoch" => 7}} = g_api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, rm, a_dev)
      assert_same_shape(g_last(b.user.id, "group_event"), example("event_group_removed.json"))

      {409, err} = g_api(:post, "/api/v1/groups/#{id}/leave", a.token, nil, a_dev)
      assert_error(err, "error_last_admin.json")

      # event_group_add_expired.json (Kamal updates his old app first)
      Repo.delete_all(from i in "app_instances", where: i.instance_key == ^("device:" <> k_old))

      {200, %{"group" => %{"pending" => [add_op]}}} =
        g_api(
          :post,
          "/api/v1/groups/#{id}/members",
          a.token,
          %{"user_ids" => [kamal.user.id]},
          a_dev
        )

      :ok =
        RisiMe.Workers.GroupTimer.perform(%Oban.Job{
          args: %{"kind" => "expire", "op_id" => add_op["op_id"]}
        })

      assert_same_shape(g_last(a.user.id, "group_event"), example("event_group_add_expired.json"))

      # error_log_expired.json
      Repo.delete_all(from c in "mls_commits", where: c.conversation_id == ^id and c.epoch < 3)
      {410, err} = g_api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=0", a.token)
      assert_error(err, "error_log_expired.json")

      # group_reset.json → group_reset_reply.json, event_group_reset.json, error_generation_conflict.json
      {200, reply} =
        g_api(
          :post,
          "/api/v1/mls/groups/#{id}/reset",
          a.token,
          example("group_reset.json"),
          a_dev
        )

      assert reply == example("group_reset_reply.json")
      ev = g_last(a.user.id, "group_event")
      assert_same_shape(ev, example("event_group_reset.json"))

      {409, err} =
        g_api(
          :post,
          "/api/v1/mls/groups/#{id}/reset",
          a.token,
          example("group_reset.json"),
          a_dev
        )

      assert_error(err, "error_generation_conflict.json")
    end
  end

  describe "v1.11" do
    setup :with_attestation_key

    @vectors Path.expand("../../../contract/v1/media_vectors.json", __DIR__)
    @mib 1024 * 1024

    defp v_api(method, path, token, body \\ nil, device \\ nil),
      do: RisiMe.GroupHelpers.api(method, path, token, body, device)

    # PUT /me/devices with the given capabilities and a fixed key (so a re-PUT isn't a key change).
    defp put_device(user, device_id, key, caps) do
      body = %{
        example("device_put_images.json")
        | "mls" => %{"signature_key" => key, "capabilities" => caps}
      }

      {200, %{"attestation" => _}} =
        v_api(:put, "/api/v1/me/devices/#{device_id}", user.token, body)

      :ok = RisiMe.MLS.record_instance(user.user.id, device_id, nil, "0.3.0")
      device_id
    end

    defp raw_upload(token, params, bytes, ct \\ "application/octet-stream") do
      conn =
        http()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
        |> Plug.Conn.put_req_header("content-type", ct)
        |> Plug.Conn.put_req_header("content-length", Integer.to_string(byte_size(bytes)))
        |> Phoenix.ConnTest.dispatch(
          @endpoint,
          :post,
          "/api/v1/blobs?" <> URI.encode_query(params),
          bytes
        )

      {conn.status, Jason.decode!(conn.resp_body)}
    end

    defp media_params(conv),
      do: %{
        "purpose" => "media",
        "conversation_id" => conv,
        "client_blob_id" => Ecto.UUID.generate()
      }

    # §14.3: Padmé(L) and the ciphertext size of a plaintext of L bytes.
    defp padme(1), do: 1

    defp padme(l) do
      e = trunc(:math.log2(l))
      e = if Bitwise.bsl(1, e + 1) <= l, do: e + 1, else: e
      sz = trunc(:math.log2(e)) + 1
      mask = Bitwise.bsl(1, e - sz) - 1
      Bitwise.band(l + mask, Bitwise.bnot(mask))
    end

    defp cipher_size(plain) do
      p = padme(plain)
      p + 16 * div(p + 65_535, 65_536)
    end

    defp vint(n) when is_integer(n), do: n
    defp vint(s) when is_binary(s), do: String.to_integer(s)

    defp b64_len(s, n), do: match?({:ok, <<_::binary-size(n)>>}, Base.decode64(s))

    defp b64_max(s, n) do
      case Base.decode64(s) do
        {:ok, b} -> byte_size(b) <= n
        _ -> false
      end
    end

    # The §14.4 strict receive validation (what every client must apply before storing).
    defp valid_image_ref?(%{"blob" => blob, "enc" => enc}) do
      enc["alg"] == "A256GCM-S64K" and b64_len(enc["key"], 32) and is_integer(enc["plain_size"]) and
        enc["plain_size"] >= 1 and cipher_size(enc["plain_size"]) == blob["size"] and
        blob["size"] <= 16 * @mib and b64_len(blob["sha256"], 32) and
        match?({:ok, _}, Ecto.UUID.cast(blob["blob_id"]))
    end

    defp valid_image?(%{"v" => 1, "type" => "image"} = e) do
      thumb = e["thumb"]

      valid_image_ref?(e) and e["mime"] in ~w(image/jpeg image/png image/webp) and
        e["w"] in 1..2048 and e["h"] in 1..2048 and
        (thumb == nil or
           (thumb["mime"] in ~w(image/jpeg image/webp) and thumb["w"] in 1..128 and
              thumb["h"] in 1..128 and b64_max(thumb["data"], 4096))) and
        byte_size(Jason.encode!(e)) <= 22_528
    end

    test "image envelopes and group_meta_icon.json follow §14.3/§14.4; bad_key is dropped" do
      for name <- ~w(image_payload.json image_payload_no_thumb.json image_payload_png.json),
          do: assert(valid_image?(example(name)), name)

      refute valid_image?(example("image_payload_bad_key.json"))
      assert example("image_payload_no_thumb.json")["thumb"] == nil

      meta = example("group_meta_icon.json")
      assert meta["v"] == 1 and Enum.all?(meta["admins"], &(&1 =~ @uuid))
      icon = meta["icon"]
      assert valid_image_ref?(icon) and icon["blob"]["size"] <= 512 * 1024
      assert {icon["mime"], icon["w"], icon["h"]} == {"image/jpeg", 512, 512}
    end

    test "media_vectors.json: the server stores and serves the ciphertext byte-exact, with the client's size and SHA-256",
         %{a: a, b: b} do
      vectors = @vectors |> File.read!() |> Jason.decode!()
      ka = Base.encode64(:crypto.strong_rand_bytes(32))
      put_device(a, Ecto.UUID.generate(), ka, ["groups", "images"])

      put_device(b, Ecto.UUID.generate(), Base.encode64(:crypto.strong_rand_bytes(32)), [
        "groups",
        "images"
      ])

      conv = e2ee_group!(a, b)

      for v <- vectors["positive"] do
        cipher = Base.decode16!(v["cipher"], case: :lower)
        size = vint(v["cipher_size"])
        assert byte_size(cipher) == size
        assert cipher_size(vint(v["plain_size"])) == size, v["name"]
        assert padme(vint(v["plain_size"])) == vint(v["padded_size"])

        {201, up} = raw_upload(a.token, media_params(conv), cipher)
        assert up["size"] == size
        assert up["sha256"] == v["sha256"] |> Base.decode16!(case: :lower) |> Base.encode64()

        conn =
          http()
          |> Plug.Conn.put_req_header("authorization", "Bearer " <> b.token)
          |> Phoenix.ConnTest.dispatch(@endpoint, :get, "/api/v1/blobs/#{up["blob_id"]}")

        assert conn.resp_body == cipher
        assert Plug.Conn.get_resp_header(conn, "etag") == [~s("#{v["sha256"]}")]

        # A resume at the last segment boundary (segment i starts at i·65552).
        last = (vint(v["segments"]) - 1) * 65_552

        conn =
          http()
          |> Plug.Conn.put_req_header("authorization", "Bearer " <> b.token)
          |> Plug.Conn.put_req_header("range", "bytes=#{last}-")
          |> Phoenix.ConnTest.dispatch(@endpoint, :get, "/api/v1/blobs/#{up["blob_id"]}")

        assert conn.status == 206
        assert conn.resp_body == binary_part(cipher, last, size - last)
      end
    end

    test "device_put_images.json, mls_group_images_ready.json, blob_upload_media_reply.json, blob_usage_reply.json and the errors",
         %{a: a, b: b} do
      # device_put_images.json: the capability is stored.
      a_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        v_api(:put, "/api/v1/me/devices/#{a_dev}", a.token, example("device_put_images.json"))

      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")

      assert %{capabilities: ["groups", "images"]} =
               Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)

      kb = Base.encode64(:crypto.strong_rand_bytes(32))
      b_dev = put_device(b, Ecto.UUID.generate(), kb, ["groups"])
      RisiMe.GroupHelpers.clear_legacy!()

      # Plaintext: never images_ready.
      plain = Messaging.conversation_id(a.user.id, b.user.id)
      {200, view} = v_api(:get, "/api/v1/mls/groups/#{plain}", a.token)
      assert view["images_ready"] == false

      # error_not_e2ee.json
      assert raw_upload(a.token, media_params(plain), "x") ==
               {409, example("error_not_e2ee.json")}

      # mls_group_images_ready.json: b's device lacks `images`.
      conv = e2ee_group!(a, b)
      ex = example("mls_group_images_ready.json")
      {200, view} = v_api(:get, "/api/v1/mls/groups/#{conv}", a.token)

      assert keys(
               Map.drop(
                 view,
                 ~w(deletes_ready missing_deletes calls_ready missing_calls video_ready missing_video group_calls_ready missing_group_calls)
               )
             ) ==
               keys(ex)

      assert_same_shape(
        Map.drop(
          view,
          ~w(missing devices missing_images deletes_ready missing_deletes calls_ready missing_calls video_ready missing_video group_calls_ready missing_group_calls)
        ),
        Map.drop(ex, ~w(missing devices missing_images))
      )

      assert view["images_ready"] == false
      assert view["missing_images"] == [%{"user_id" => b.user.id, "device_id" => b_dev}]
      assert_same_shape(hd(view["missing_images"]), hd(ex["missing_images"]))

      # b updates (same key): ready, for both callers.
      put_device(b, b_dev, kb, ["groups", "images"])
      {200, view} = v_api(:get, "/api/v1/mls/groups/#{conv}", b.token)
      assert {view["images_ready"], view["missing_images"]} == {true, []}

      # blob_upload_media_reply.json
      {201, up} = raw_upload(a.token, media_params(conv), :crypto.strong_rand_bytes(1000))
      assert_same_shape(up, example("blob_upload_media_reply.json"))

      # blob_usage_reply.json
      {200, usage} = v_api(:get, "/api/v1/blobs/usage", a.token)
      # v1.15 §17.9 adds `history` (blob_usage_reply_history.json, checked in "v1.15").
      # v1.22 §22.3 adds `backup` (blob_usage_reply_backup.json, checked in "v1.22").
      assert_same_shape(Map.drop(usage, ["history", "backup"]), example("blob_usage_reply.json"))
      ex_usage = example("blob_usage_reply.json")
      assert usage["media"]["limit"] == ex_usage["media"]["limit"]
      assert usage["media"]["hourly_limit"] == ex_usage["media"]["hourly_limit"]
      assert usage["media"]["daily_limit"] == ex_usage["media"]["daily_limit"]
      assert usage["mls"]["limit"] == ex_usage["mls"]["limit"]
      assert usage["media"]["used"] == 1000 and usage["media"]["uploads_last_hour"] == 1

      # error_bad_media_type.json
      assert raw_upload(a.token, media_params(conv), "x", "image/jpeg") ==
               {415, example("error_bad_media_type.json")}

      # error_storage_full.json
      prev = Application.get_env(:risime, :blob_disk)
      Application.put_env(:risime, :blob_disk, {1, 1024 ** 4})
      on_exit(fn -> Application.put_env(:risime, :blob_disk, prev) end)

      assert raw_upload(a.token, media_params(conv), "x") ==
               {507, example("error_storage_full.json")}
    end

    test "images_ready in a group, and a legacy instance or a member without an images device",
         %{a: a, b: b} do
      import RisiMe.GroupHelpers, only: [create_commit: 2, clear_legacy!: 0]

      a_dev =
        put_device(a, Ecto.UUID.generate(), Base.encode64(:crypto.strong_rand_bytes(32)), [
          "groups",
          "images"
        ])

      put_device(b, Ecto.UUID.generate(), Base.encode64(:crypto.strong_rand_bytes(32)), [
        "groups",
        "images"
      ])

      clear_legacy!()

      body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]}
      {201, %{"group" => %{"id" => id}}} = v_api(:post, "/api/v1/groups", a.token, body, a_dev)

      {200, _} =
        v_api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          create_commit([a.user.id, b.user.id], {a.user.id, a_dev}),
          a_dev
        )

      {200, view} = v_api(:get, "/api/v1/mls/groups/#{id}", a.token, nil, a_dev)
      assert {view["images_ready"], view["missing_images"]} == {true, []}

      # A pre-v1.7 instance of b seen after b's latest registration blocks (device_id null).
      Process.sleep(5)
      :ok = RisiMe.MLS.record_instance(b.user.id, nil, "tok", "0.1.0")
      {200, view} = v_api(:get, "/api/v1/mls/groups/#{id}", a.token, nil, a_dev)
      assert view["images_ready"] == false
      assert view["missing_images"] == [%{"user_id" => b.user.id, "device_id" => nil}]

      # A removed device (e.g. an old install) no longer counts; a user with no images device does.
      clear_legacy!()
      c = logged_in_user()
      RisiMe.GroupHelpers.clear_legacy!()
      assert RisiMe.MLS.Images.missing([c.user.id]) == [%{user_id: c.user.id, device_id: nil}]
      old = Ecto.UUID.generate()
      :ok = RisiMe.MLS.record_instance(a.user.id, old, nil, "0.2.0")
      assert RisiMe.MLS.Images.missing([a.user.id]) == []
    end
  end

  ## v1.12 (§15)

  describe "v1.12" do
    setup :with_attestation_key

    alias RisiMe.MLS.Wire

    defp d_device(user, caps) do
      dev = Ecto.UUID.generate()

      {:ok, _} =
        RisiMe.Devices.register(user.user.id, dev, %{
          "platform" => "android",
          "mls" => %{"signature_key" => b64(), "capabilities" => caps}
        })

      :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0")
      dev
    end

    defp d_ct(targets),
      do:
        Wire.encode_private_message("g", 1, Wire.delete_aad(targets), b64raw(), b64raw())
        |> Base.encode64()

    defp b64raw, do: :crypto.strong_rand_bytes(24)

    # A push on a socket of `user` with `device` (nil = legacy), as the wire sees the reply.
    defp d_push(user, device, event, payload) do
      params = %{"token" => user.token}
      params = if device, do: Map.put(params, "device_id", device), else: params
      {:ok, sock} = connect(UserSocket, params)
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
      ref = push(chan, event, payload)

      receive do
        %Phoenix.Socket.Reply{ref: ^ref, status: st, payload: p} -> {st, wire(p)}
      after
        2000 -> flunk("no reply to #{event}")
      end
    end

    defp d_event(user_id, id),
      do: user_id |> RisiMe.GroupHelpers.events() |> Enum.find(&(&1["event_id"] == id))

    defp assert_delete_event(ev, ex) do
      assert_same_shape(ev, ex)
      [full, null] = ex["data"]["targets"]

      for t <- ev["data"]["targets"] do
        theirs = if t["from"], do: full, else: null
        assert keys(t) == keys(theirs)
        assert_same_shape(t, theirs)
      end
    end

    # §15.3 strict validation (what every client applies before acting).
    defp valid_delete?(%{"v" => 1, "type" => "delete", "targets" => ts}) when is_list(ts) do
      length(ts) in 1..100 and Enum.uniq(ts) == ts and
        Enum.all?(ts, &(is_binary(&1) and &1 =~ @timeuuid))
    end

    defp valid_delete?(_), do: false

    defp plant_old(sender, conv, recipients) do
      id = RisiMe.TimeUUID.at(DateTime.add(DateTime.utc_now(), -49, :hour))
      store = RisiMe.Messaging.Store.impl()

      :ok =
        store.put_message(%{
          message_id: id,
          sender_id: sender,
          recipient_id: nil,
          recipients: recipients,
          client_msg_id: Ecto.UUID.generate(),
          conversation_id: conv,
          status: "sent"
        })

      id
    end

    test "delete_payload.json and delete_payload_bad.json (§15.3); the AAD encoding" do
      ok = example("delete_payload.json")
      assert valid_delete?(ok)
      refute valid_delete?(example("delete_payload_bad.json"))
      assert length(example("delete_payload_bad.json")["targets"]) == 101

      aad = Wire.delete_aad(Enum.reverse(ok["targets"]))
      assert <<1, ?D, rest::binary>> = aad
      assert byte_size(rest) == 32
      assert aad == Wire.delete_aad(ok["targets"])
      [x, y] = for <<u::binary-16 <- rest>>, do: u
      assert x < y
    end

    test "msg:delete examples, replies, events and errors against the server", %{a: a, b: b} do
      import RisiMe.GroupHelpers, only: [create_commit: 2, clear_legacy!: 0]
      caps = ["groups", "images", "deletes"]
      a_dev = d_device(a, caps)
      b_dev = d_device(b, caps)
      clear_legacy!()

      body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]}
      {201, %{"group" => %{"id" => id}}} = v_api(:post, "/api/v1/groups", a.token, body, a_dev)

      {200, _} =
        v_api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          create_commit([a.user.id, b.user.id], {a.user.id, a_dev}),
          a_dev
        )

      # The examples as they are parse; their group is unknown to us.
      grp = example("msg_delete_everyone_group.json")
      assert {:error, %{"reason" => "not_member"}} = d_push(a, a_dev, "msg:delete", grp)

      # msg_delete_everyone_group.json → msg_delete_reply.json, event_delete_group.json
      {:ok, %{message_id: m}} =
        Messaging.send(
          b.user.id,
          %{
            "client_msg_id" => Ecto.UUID.generate(),
            "conversation_id" => id,
            "ciphertext" => b64(),
            "generation" => 1,
            "epoch" => 1
          },
          device_id: b_dev
        )

      unknown = RisiMe.TimeUUID.generate()

      req = %{
        grp
        | "client_msg_id" => Ecto.UUID.generate(),
          "conversation_id" => id,
          "targets" => [m, unknown],
          "blob_ids" => [],
          "ciphertext" => d_ct([m, unknown]),
          "epoch" => 1
      }

      {:ok, reply} = d_push(a, a_dev, "msg:delete", req)
      ex = example("msg_delete_reply.json")
      assert keys(reply) == keys(ex)
      assert_same_shape(reply, ex)
      assert {reply["deleted"], reply["gone"]} == {[m], [unknown]}

      assert_delete_event(
        d_event(b.user.id, reply["message_id"]),
        example("event_delete_group.json")
      )

      # msg_delete_reply_gone.json: nothing new, no event.
      {:ok, gone} =
        d_push(a, a_dev, "msg:delete", %{req | "client_msg_id" => Ecto.UUID.generate()})

      ex = example("msg_delete_reply_gone.json")
      assert keys(gone) == keys(ex) and gone["message_id"] == nil and gone["server_ts"] == nil
      assert gone["deleted"] == [] and gone["gone"] == [m, unknown]

      # error_delete_too_old.json: b's own 49 h message and a's message, from member b.
      old = plant_old(b.user.id, id, [a.user.id])

      {:ok, %{message_id: m_a}} =
        Messaging.send(
          a.user.id,
          %{
            "client_msg_id" => Ecto.UUID.generate(),
            "conversation_id" => id,
            "ciphertext" => b64(),
            "generation" => 1,
            "epoch" => 1
          },
          device_id: a_dev
        )

      {:error, err} =
        d_push(b, b_dev, "msg:delete", %{
          req
          | "client_msg_id" => Ecto.UUID.generate(),
            "targets" => [old, m_a],
            "ciphertext" => d_ct([old, m_a])
        })

      ex = example("error_delete_too_old.json")
      [t1, t2] = ex["failures"]
      assert err == %{ex | "failures" => [%{t1 | "target" => old}, %{t2 | "target" => m_a}]}

      # msg_delete_me.json
      {:ok, mine} =
        d_push(b, b_dev, "msg:delete", %{
          example("msg_delete_me.json")
          | "conversation_id" => id,
            "targets" => [m_a]
        })

      assert keys(mine) == keys(example("msg_delete_reply.json"))
      assert mine["message_id"] == nil and mine["deleted"] == [m_a]

      # msg_delete_everyone_dm.json (plaintext) → event_delete_dm.json; error_not_sender.json
      plain = Messaging.conversation_id(a.user.id, b.user.id)

      {:ok, %{message_id: p}} =
        Messaging.send(a.user.id, %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => b.user.id,
          "body" => "hi"
        })

      dm_req = %{
        example("msg_delete_everyone_dm.json")
        | "client_msg_id" => Ecto.UUID.generate(),
          "conversation_id" => plain,
          "targets" => [p]
      }

      {:error, err} = d_push(b, nil, "msg:delete", dm_req)
      ex = example("error_not_sender.json")
      assert err == %{ex | "failures" => [%{hd(ex["failures"]) | "target" => p}]}

      {:ok, reply} = d_push(a, nil, "msg:delete", dm_req)
      ev = d_event(b.user.id, reply["message_id"])
      ex = example("event_delete_dm.json")
      assert keys(ev["data"]) == keys(ex["data"])
      assert_same_shape(Map.delete(ev, "data"), Map.delete(ex, "data"))
      assert_same_shape(Map.delete(ev["data"], "targets"), Map.delete(ex["data"], "targets"))
      assert_same_shape(hd(ev["data"]["targets"]), hd(ex["data"]["targets"]))

      # event_delete_dm_e2ee.json
      dm = e2ee_group!(a, b)

      {:ok, %{message_id: e}} =
        Messaging.send(
          a.user.id,
          %{
            "client_msg_id" => Ecto.UUID.generate(),
            "to" => b.user.id,
            "ciphertext" => b64(),
            "generation" => 1,
            "epoch" => 1
          },
          device_id: a_dev
        )

      {:ok, reply} =
        d_push(
          a,
          a_dev,
          "msg:delete",
          %{
            dm_req
            | "client_msg_id" => Ecto.UUID.generate(),
              "conversation_id" => dm,
              "targets" => [e]
          }
          |> Map.merge(%{"ciphertext" => d_ct([e]), "generation" => 1, "epoch" => 1})
        )

      ev = d_event(b.user.id, reply["message_id"])
      ex = example("event_delete_dm_e2ee.json")
      assert keys(ev["data"]) == keys(ex["data"])
      assert_same_shape(Map.delete(ev["data"], "targets"), Map.delete(ex["data"], "targets"))
      assert_same_shape(hd(ev["data"]["targets"]), hd(ex["data"]["targets"]))

      # chat_clear.json as it is: accepted.
      assert {:ok, %{}} = d_push(a, a_dev, "chat:clear", example("chat_clear.json"))
    end

    test "device_put_deletes.json and mls_group_deletes_ready.json", %{a: a, b: b} do
      a_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        v_api(:put, "/api/v1/me/devices/#{a_dev}", a.token, example("device_put_deletes.json"))

      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")

      assert %{capabilities: ["groups", "images", "deletes"]} =
               Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)

      b_dev = d_device(b, ["groups", "images"])
      RisiMe.GroupHelpers.clear_legacy!()
      conv = e2ee_group!(a, b)

      ex = example("mls_group_deletes_ready.json")
      {200, view} = v_api(:get, "/api/v1/mls/groups/#{conv}", a.token)

      assert keys(
               Map.drop(
                 view,
                 ~w(calls_ready missing_calls video_ready missing_video group_calls_ready missing_group_calls)
               )
             ) == keys(ex)

      assert_same_shape(
        Map.drop(
          view,
          ~w(missing devices calls_ready missing_calls video_ready missing_video group_calls_ready missing_group_calls)
        ),
        Map.drop(ex, ~w(missing devices))
      )

      assert view["deletes_ready"] == false and view["images_ready"] == true
      assert view["missing_deletes"] == [%{"user_id" => b.user.id, "device_id" => b_dev}]
      assert_same_shape(hd(view["missing_deletes"]), hd(ex["missing_deletes"]))
    end
  end

  test "v1.14 device_put_member_devices.json: the capability is stored (§12.1, §12.4a)",
       %{a: a} do
    with_attestation_key(%{})
    ex = example("device_put_member_devices.json")
    assert "member_devices" in ex["mls"]["capabilities"]
    dev = Ecto.UUID.generate()
    {:ok, att} = RisiMe.Devices.register(a.user.id, dev, ex)
    assert is_binary(att)

    stored = RisiMe.Repo.get_by!(RisiMe.Devices.Device, user_id: a.user.id, device_id: dev)
    assert stored.capabilities == ex["mls"]["capabilities"]
  end

  describe "v1.15" do
    setup :with_attestation_key

    alias RisiMe.MLS.Wire

    defp h_device(user, caps \\ ["groups", "history_share"]) do
      dev = Ecto.UUID.generate()

      token_id =
        Repo.one(
          from t in RisiMe.Accounts.UserToken,
            where: t.user_id == ^user.user.id,
            limit: 1,
            select: t.id
        )

      {:ok, _} =
        RisiMe.Devices.register(
          user.user.id,
          dev,
          %{
            "platform" => "android",
            "mls" => %{"signature_key" => b64(), "capabilities" => caps}
          },
          token_id
        )

      :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
      dev
    end

    defp h_join(user, dev) do
      {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
      chan
    end

    defp h_ct(conv, rid) do
      g = RisiMe.MLS.group(conv)

      Base.encode64(
        Wire.encode_private_message(
          "#{conv}##{g.generation}",
          g.epoch,
          Wire.history_aad(rid),
          "s",
          "c"
        )
      )
    end

    defp h_push(chan, event, payload) do
      ref = push(chan, event, payload)
      assert_reply ref, status, reply
      {status, reply}
    end

    defp h_event(user, kind) do
      {:ok, events, _} = Messaging.fetch_events(user.user.id, nil)
      events |> Enum.filter(&(&1.kind == kind)) |> List.last() |> wire()
    end

    # a's new device asks in an e2ee group with b; a's old device and b provide.
    setup %{a: a, b: b} do
      RisiMe.GroupHelpers.clear_legacy!()
      a_old = h_device(a)
      a_new = h_device(a)
      b_dev = h_device(b)
      :ok = RisiMe.MLS.record_instance(a.user.id, a_old, nil, "0.3.0-test")

      %{"group" => %{"id" => conv}} =
        RisiMe.GroupHelpers.api(
          :post,
          "/api/v1/groups",
          a.token,
          %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]},
          a_new
        )
        |> RisiMe.GroupHelpers.assert_status(201)

      RisiMe.GroupHelpers.api(
        :post,
        "/api/v1/mls/groups/#{conv}/commit",
        a.token,
        RisiMe.GroupHelpers.create_commit([a.user.id, b.user.id], {a.user.id, a_new}),
        a_new
      )
      |> RisiMe.GroupHelpers.assert_status(200)

      hour_ago = DateTime.add(DateTime.utc_now(), -3600, :second)
      Repo.update_all("app_instances", set: [first_seen_at: hour_ago])
      Repo.update_all("group_member_intervals", set: [active_from: hour_ago])
      %{conv: conv, a_old: a_old, a_new: a_new, b_dev: b_dev}
    end

    defp h_request(ctx, chan) do
      ex = example("history_request_push.json")
      rid = Ecto.UUID.generate()
      now = DateTime.utc_now()
      g = RisiMe.MLS.group(ctx.conv)

      p = %{
        ex
        | "request_id" => rid,
          "conversation_id" => ctx.conv,
          "range" => %{
            "from" => Messaging.iso(DateTime.add(now, -1800, :second)),
            "to" => Messaging.iso(DateTime.add(now, -60, :second))
          },
          "ciphertext" => h_ct(ctx.conv, rid),
          "generation" => g.generation,
          "epoch" => g.epoch
      }

      {:ok, reply} = h_push(chan, "history:request", p)
      {rid, reply}
    end

    test "device_put_history_share.json: the capability is stored (§17.1)", %{a: a} do
      ex = example("device_put_history_share.json")
      dev = Ecto.UUID.generate()
      {:ok, _} = RisiMe.Devices.register(a.user.id, dev, ex)
      stored = Repo.get_by!(RisiMe.Devices.Device, user_id: a.user.id, device_id: dev)
      assert "history_share" in stored.capabilities
      assert RisiMe.Devices.history?(stored)
    end

    test "the pushes, replies and events of a whole request (§17.4, §17.5, §17.9)", ctx do
      %{a: a, b: b, conv: conv} = ctx
      chan_a = h_join(a, ctx.a_new)
      chan_old = h_join(a, ctx.a_old)
      chan_b = h_join(b, ctx.b_dev)

      # history_request_push.json → history_request_reply.json, event_history_request.json.
      {rid, reply} = h_request(ctx, chan_a)
      assert_same_shape(wire(reply), example("history_request_reply.json"))

      assert_same_shape(
        hd(wire(reply)["own_devices"]),
        hd(example("history_request_reply.json")["own_devices"])
      )

      ev = h_event(a, "history_request")
      ex = example("event_history_request.json")
      assert ev["kind"] == ex["kind"]
      assert_same_shape(ev["data"], ex["data"])
      assert_same_shape(hd(ev["data"]["intervals"]), hd(ex["data"]["intervals"]))
      assert_same_shape(ev["data"]["range"], ex["data"]["range"])
      assert ev["event_id"] =~ @timeuuid

      # error_request_open.json.
      other = Ecto.UUID.generate()

      {:error, err} =
        h_push(chan_a, "history:request", %{
          example("history_request_push.json")
          | "conversation_id" => conv,
            "request_id" => other,
            "ciphertext" => h_ct(conv, other),
            "range" => ev["data"]["range"],
            "generation" => 1,
            "epoch" => RisiMe.MLS.group(conv).epoch
        })

      assert_same_shape(wire(err), example("error_request_open.json"))
      assert err.request_id == rid

      # history_escalate.json (members named), history_respond_stale.json → event_history_status_refresh.json.
      assert {:ok, %{}} =
               h_push(chan_a, "history:escalate", %{
                 example("history_escalate.json")
                 | "request_id" => rid
               })

      assert {:ok, %{}} =
               h_push(chan_b, "history:respond", %{
                 example("history_respond_stale.json")
                 | "request_id" => rid
               })

      st = h_event(a, "history_status")
      assert_same_shape(st, example("event_history_status_refresh.json"))
      assert st["data"]["state"] == "refresh" and st["data"]["provider"] == nil

      # history_refresh.json names b again.
      g = RisiMe.MLS.group(conv)

      refresh = %{
        example("history_refresh.json")
        | "request_id" => rid,
          "ciphertext" => h_ct(conv, rid),
          "generation" => g.generation,
          "epoch" => g.epoch
      }

      assert {:ok, %{}} = h_push(chan_a, "history:refresh", refresh)

      # history_respond.json (b accepts) → event_history_status.json; the old phone's prompt
      # closes (event_history_request_closed.json).
      assert {:ok, %{}} =
               h_push(chan_b, "history:respond", %{
                 example("history_respond.json")
                 | "request_id" => rid
               })

      st = h_event(a, "history_status")
      assert_same_shape(st, example("event_history_status.json"))

      assert_same_shape(
        st["data"]["provider"],
        example("event_history_status.json")["data"]["provider"]
      )

      _ = chan_old

      # blob_upload_history_reply.json, blob_usage_reply_history.json.
      path =
        "/api/v1/blobs?purpose=history&conversation_id=#{URI.encode_www_form(conv)}" <>
          "&request_id=#{rid}&client_blob_id=#{Ecto.UUID.generate()}"

      conn =
        Phoenix.ConnTest.build_conn()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> b.token)
        |> Plug.Conn.put_req_header("x-device-id", ctx.b_dev)
        |> Plug.Conn.put_req_header("content-type", "application/octet-stream")
        |> Plug.Conn.put_req_header("content-length", "64")
        |> Phoenix.ConnTest.dispatch(
          RisiMeWeb.Endpoint,
          :post,
          path,
          :crypto.strong_rand_bytes(64)
        )

      assert conn.status == 201
      assert_same_shape(Jason.decode!(conn.resp_body), example("blob_upload_history_reply.json"))
      {200, usage} = RisiMe.GroupHelpers.api(:get, "/api/v1/blobs/usage", b.token)
      # v1.22 adds `backup` (blob_usage_reply_backup.json, checked in "v1.22").
      assert_same_shape(Map.delete(usage, "backup"), example("blob_usage_reply_history.json"))
      assert_same_shape(usage["history"], example("blob_usage_reply_history.json")["history"])

      # history_deliver.json → event_history_share.json.
      deliver = %{
        example("history_deliver.json")
        | "request_id" => rid,
          "ciphertext" => h_ct(conv, rid),
          "generation" => g.generation,
          "epoch" => g.epoch
      }

      assert {:ok, %{}} = h_push(chan_b, "history:deliver", deliver)
      share = h_event(a, "history_share")
      assert_same_shape(share, example("event_history_share.json"))

      # history_ack.json → done; event_history_request_closed.json to every named user.
      assert {:ok, %{}} =
               h_push(chan_a, "history:ack", %{example("history_ack.json") | "request_id" => rid})

      closed = h_event(b, "history_request_closed")
      assert_same_shape(closed, example("event_history_request_closed.json"))
      assert closed["data"]["reason"] == "done"

      # history_cancel.json on a new request.
      Repo.update_all("history_requests",
        set: [created_at: DateTime.add(DateTime.utc_now(), -2, :day)]
      )

      {rid2, _} = h_request(ctx, chan_a)

      assert {:ok, %{}} =
               h_push(chan_a, "history:cancel", %{
                 example("history_cancel.json")
                 | "request_id" => rid2
               })

      assert Repo.get!(RisiMe.History.Request, rid2).state == "cancelled"
    end

    test "the MLS envelopes and bundle lines follow §17.6/§17.7" do
      req = example("history_request_payload.json")
      assert req["v"] == 1 and req["type"] == "history_request" and req["request_id"] =~ @uuid
      assert req["hpke"]["kem"] == "x25519"
      assert byte_size(Base.decode64!(req["hpke"]["pk"])) == 32
      assert_same_shape(req["range"], example("history_request_push.json")["range"])

      share = example("history_share_payload.json")
      assert share["v"] == 1 and share["type"] == "history_share"
      assert 1 <= share["part"] and share["part"] <= share["parts"] and share["parts"] <= 20

      assert share["enc"]["alg"] == "A256GCM-S64K" and
               share["enc"]["label"] == "risime-history-v1"

      assert byte_size(Base.decode64!(share["enc"]["hpke_enc"])) == 32
      assert byte_size(Base.decode64!(share["enc"]["sealed_key"])) == 48
      assert byte_size(Base.decode64!(share["blob"]["sha256"])) == 32
      assert share["blob"]["size"] <= RisiMe.Blobs.max_bytes("history")

      assert share["blob"] ==
               Map.take(example("blob_upload_history_reply.json"), ~w(blob_id size sha256))

      header = example("history_bundle_header.json")
      assert header["v"] == 1 and header["type"] == "history_bundle"
      [u, d] = String.split(header["provider"], "/")
      assert u =~ @uuid and d =~ @uuid
      assert header["request_id"] == share["request_id"] and header["count"] == share["count"]

      entry = example("history_bundle_entry.json")
      assert keys(entry) == ~w(client_msg_id from from_device message_id payload server_ts)
      assert entry["message_id"] =~ @timeuuid and entry["server_ts"] =~ @ts
      assert entry["payload"]["type"] in ~w(text image reaction call_end)

      # The 'H' authenticated data: 18 bytes, one canonical form.
      rid = req["request_id"]
      aad = Wire.history_aad(rid)
      assert byte_size(aad) == 18 and Wire.history_aad?(aad, rid)
      refute Wire.history_aad?(binary_part(aad, 0, 17), rid)
      refute Wire.history_aad?(aad <> <<0>>, rid)
      refute Wire.history_aad?(<<1, ?D>> <> binary_part(aad, 2, 16), rid)
      refute Wire.history_aad?(<<2, ?H>> <> binary_part(aad, 2, 16), rid)
    end
  end

  describe "v1.13" do
    setup :with_attestation_key

    @call_types ~w(call_offer call_ringing call_answer call_accepted call_ice call_busy call_cancel
                   call_end)
    @end_reasons ~w(hangup cancelled timeout declined busy failed)

    defp c_device(user, caps) do
      dev = Ecto.UUID.generate()

      {:ok, _} =
        RisiMe.Devices.register(user.user.id, dev, %{
          "platform" => "android",
          "mls" => %{"signature_key" => b64(), "capabilities" => caps}
        })

      :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0")
      dev
    end

    defp c_chan(user, dev) do
      {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
      chan
    end

    defp c_push(chan, payload) do
      ref = push(chan, "call:signal", payload)

      receive do
        %Phoenix.Socket.Reply{ref: ^ref, status: st, payload: p} -> {st, wire(p)}
      after
        2000 -> flunk("no reply to call:signal")
      end
    end

    test "the call envelopes follow the §16.2 rules (they travel inside MLS)" do
      for name <- @checked_v1_13,
          String.ends_with?(name, "_payload.json") or name =~ "_payload_" do
        ex = example(name)
        assert ex["v"] == 1 and ex["type"] in @call_types, name
        assert ex["call_id"] =~ @uuid, name
        assert byte_size(Jason.encode!(ex)) <= 20_480, name
      end

      for name <- ~w(call_offer_payload.json call_offer_payload_bad.json) do
        ex = example(name)
        assert ex["media"] == "audio" and ex["restart"] == false and ex["sent_at"] =~ @ts
        assert length(Regex.scan(~r/a=fingerprint:sha-256 /, ex["sdp"])) == 1
        assert ex["sdp"] =~ "a=setup:actpass" and ex["sdp"] =~ "opus/48000/2"
        refute ex["sdp"] =~ "a=crypto"
      end

      # The bad offer carries the audio-level header extension (must be dropped); the good one not.
      refute example("call_offer_payload.json")["sdp"] =~ "ssrc-audio-level"
      assert example("call_offer_payload_bad.json")["sdp"] =~ "ssrc-audio-level"

      assert example("call_answer_payload.json")["sdp"] =~ "a=setup:active"
      assert example("call_answer_payload.json")["to_device"] =~ @uuid
      assert example("call_accepted_payload.json")["device_id"] =~ @uuid
      assert example("call_cancel_payload.json")["reason"] == "glare"

      ice = example("call_ice_payload.json")
      assert length(ice["candidates"]) <= 20 and is_boolean(ice["done"])

      for c <- ice["candidates"],
          do:
            assert(
              String.starts_with?(c["candidate"], "candidate:") and
                byte_size(c["candidate"]) <= 512
            )

      done = example("call_end_payload.json")
      assert done["reason"] in @end_reasons and done["connected_at"] =~ @ts
      assert is_integer(done["duration_s"])
      missed = example("call_end_missed_payload.json")
      assert missed["reason"] in @end_reasons
      assert {missed["connected_at"], missed["duration_s"]} == {nil, nil}
    end

    test "call_signal_push.json, call_signal_reply.json, call_signal_event.json, error_calls_not_ready.json",
         %{a: a, b: b} do
      caps = ~w(groups images deletes calls)
      a_dev = c_device(a, caps)
      _b_dev = c_device(b, caps)
      e2ee_group!(a, b)
      chan = c_chan(a, a_dev)

      ex = example("call_signal_push.json")
      {:ok, reply} = c_push(chan, %{ex | "to" => b.user.id})
      assert keys(reply) == keys(example("call_signal_reply.json"))
      assert_same_shape(reply, example("call_signal_reply.json"))

      # The sender copy on a's calls socket (b's sockets here have no device id, so no `calls`).
      topic_a = "inbox:" <> a.user.id

      assert_receive %Phoenix.Socket.Message{
        topic: ^topic_a,
        event: "event",
        payload: %{kind: "call_signal"} = event
      }

      # v1.18 §19.2: the event now always carries `media` (call_signal_event_video.json).
      assert event.data["media"] == "audio"

      assert_same_shape(
        update_in(wire(event)["data"], &Map.delete(&1, "media")),
        example("call_signal_event.json")
      )

      assert event.data["call_id"] == ex["call_id"] and event.data["ring"] == true

      # A friend in an e2ee DM without any calls device: ring refused.
      c = logged_in_user()
      befriend!(a, c)
      _ = c_device(c, ~w(groups images deletes))
      e2ee_group!(a, c)

      assert {:error, err} =
               c_push(chan, %{ex | "to" => c.user.id, "client_msg_id" => Uniq.UUID.uuid4()})

      assert err == example("error_calls_not_ready.json")
    end

    test "device_put_calls.json, mls_group_calls_ready.json, push_call.json", %{a: a, b: b} do
      a_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        RisiMe.GroupHelpers.api(
          :put,
          "/api/v1/me/devices/#{a_dev}",
          a.token,
          example("device_put_calls.json")
        )

      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")

      assert %{capabilities: ["groups", "images", "deletes", "calls"]} =
               Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)

      _ = c_device(b, ~w(groups images deletes calls))
      tablet = c_device(b, ~w(groups images deletes))
      RisiMe.GroupHelpers.clear_legacy!()
      conv = e2ee_group!(a, b)

      {200, view} = RisiMe.GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", a.token)
      ex = example("mls_group_calls_ready.json")
      # v1.18 adds video_ready / missing_video (mls_group_video_ready.json).
      assert keys(Map.drop(view, ~w(video_ready missing_video))) == keys(ex)

      assert_same_shape(
        Map.drop(view, ~w(missing devices video_ready missing_video)),
        Map.drop(ex, ~w(missing devices))
      )

      assert view["calls_ready"] == true
      assert view["missing_calls"] == [%{"user_id" => b.user.id, "device_id" => tablet}]
      assert_same_shape(hd(view["missing_calls"]), hd(ex["missing_calls"]))

      assert RisiMe.Push.call_payload() == example("push_call.json")
    end

    test "calls_turn_reply.json and error_calls_unavailable.json", %{a: a} do
      on_exit(fn -> Application.delete_env(:risime, :turn) end)
      Application.put_env(:risime, :turn, [])

      assert {503, example("error_calls_unavailable.json")} ==
               RisiMe.GroupHelpers.api(:get, "/api/v1/calls/turn", a.token)

      Application.put_env(:risime, :turn,
        secret: String.duplicate("s", 48),
        urls: [
          "stun:risime.risicloud.ai:3478",
          "turn:risime.risicloud.ai:3478?transport=udp",
          "turn:risime.risicloud.ai:3478?transport=tcp"
        ]
      )

      ex = example("calls_turn_reply.json")
      {200, reply} = RisiMe.GroupHelpers.api(:get, "/api/v1/calls/turn", a.token)
      assert_same_shape(reply, ex)
      assert reply["ttl"] == ex["ttl"]
      assert Enum.map(reply["ice_servers"], &keys/1) == Enum.map(ex["ice_servers"], &keys/1)

      assert Enum.map(reply["ice_servers"], & &1["urls"]) ==
               Enum.map(ex["ice_servers"], & &1["urls"])

      assert hd(tl(reply["ice_servers"]))["username"] =~ ~r/^\d+:[0-9a-f]{16}$/
    end
  end

  describe "v1.17" do
    setup :with_attestation_key

    # The §18.1 strict validation (what every client applies; the server never sees it).
    defp valid_profile_photo?(%{"v" => 1, "type" => "profile_photo", "ver" => ver} = e)
         when is_integer(ver) and ver >= 1 do
      now_ms = System.os_time(:millisecond)

      ver <= now_ms + 86_400_000 * 365 * 2 and
        case e["photo"] do
          nil ->
            Map.has_key?(e, "photo")

          %{} = p ->
            valid_image_ref?(p) and p["blob"]["size"] <= 512 * 1024 and
              p["mime"] == "image/jpeg" and p["w"] == p["h"] and p["w"] in 64..512

          _ ->
            false
        end and byte_size(Jason.encode!(e)) < 1024
    end

    defp valid_profile_photo?(_), do: false

    test "profile_photo envelopes follow §18.1; the bad one (1024 px) is dropped" do
      assert valid_profile_photo?(example("profile_photo_payload.json"))
      assert valid_profile_photo?(example("profile_photo_payload_removed.json"))
      refute valid_profile_photo?(example("profile_photo_payload_bad.json"))
    end

    test "blob_upload_avatar_reply.json is what POST /blobs?purpose=avatar returns", %{a: a} do
      dir = Path.join(System.tmp_dir!(), "risime-ex-v117-#{System.unique_integer([:positive])}")
      prev = Application.get_env(:risime, :blob_dir)
      Application.put_env(:risime, :blob_dir, dir)

      on_exit(fn ->
        Application.put_env(:risime, :blob_dir, prev)
        File.rm_rf(dir)
      end)

      ex = example("blob_upload_avatar_reply.json")
      bytes = :crypto.strong_rand_bytes(ex["size"])

      {201, reply} =
        RisiMe.GroupHelpers.api_raw(
          "/api/v1/blobs?purpose=avatar&client_blob_id=#{Ecto.UUID.generate()}",
          a.token,
          bytes
        )

      assert keys(reply) == keys(ex)
      assert_same_shape(reply, ex)
      assert reply["expires_at"] == nil and ex["expires_at"] == nil
      assert reply["size"] == ex["size"]
    end

    test "msg_send_silent.json is accepted; the event is event_message_silent.json", %{
      a: a,
      b: b
    } do
      a_dev = RisiMe.MLSHelpers.mls_device!(a)
      _ = RisiMe.MLSHelpers.mls_device!(b)
      e2ee_group!(a, b)
      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})

      ex = example("msg_send_silent.json")
      assert ex["silent"] == true
      ref = push(chan, "msg:send", %{ex | "to" => b.user.id})
      assert_reply ref, :ok, %{message_id: mid}

      topic = "inbox:" <> a.user.id

      assert_receive %Phoenix.Socket.Message{
        topic: ^topic,
        event: "event",
        payload: %{kind: "message", event_id: ^mid} = event
      }

      ev = example("event_message_silent.json")
      assert keys(wire(event)) == keys(ev)
      assert_same_shape(wire(event)["data"], ev["data"])
      assert event.data["silent"] == true

      # Join replays it with the flag.
      {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})

      {:ok, %{events: events}, _} =
        subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})

      assert [replayed] = Enum.filter(events, &(&1.event_id == mid))
      assert_same_shape(wire(replayed)["data"], ev["data"])
    end
  end

  describe "v1.18" do
    setup :with_attestation_key

    defp v_dev(user, caps) do
      dev = Ecto.UUID.generate()

      {:ok, _} =
        RisiMe.Devices.register(user.user.id, dev, %{
          "platform" => "android",
          "mls" => %{"signature_key" => b64(), "capabilities" => caps}
        })

      :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0")
      dev
    end

    # The §19.4 checks a receiver applies to a video offer (a subset: m-lines, BUNDLE,
    # fingerprints, codecs, simulcast, b=AS, denylisted extensions).
    defp video_sdp_ok?(sdp) do
      lines = String.split(sdp, "\r\n", trim: true)
      mlines = Enum.filter(lines, &String.starts_with?(&1, "m="))
      fps = for l <- lines, String.starts_with?(l, "a=fingerprint:"), do: l

      deny =
        ~w(urn:ietf:params:rtp-hdrext:ssrc-audio-level urn:ietf:params:rtp-hdrext:csrc-audio-level
                http://www.webrtc.org/experiments/rtp-hdrext/abs-capture-time urn:3gpp:video-orientation)

      byte_size(sdp) <= 16_384 and
        match?(["m=audio " <> _, "m=video " <> _], mlines) and
        Enum.all?(mlines, &String.contains?(&1, "UDP/TLS/RTP/SAVPF")) and
        Enum.any?(lines, &String.starts_with?(&1, "a=group:BUNDLE ")) and
        Enum.count(lines, &(&1 == "a=rtcp-mux")) == 2 and fps != [] and
        length(Enum.uniq(fps)) == 1 and
        hd(fps) =~ ~r/^a=fingerprint:sha-256 ([0-9A-F]{2}:){31}[0-9A-F]{2}$/ and
        String.contains?(sdp, "VP8/90000") and
        not Enum.any?(
          lines,
          &(String.starts_with?(&1, "a=simulcast") or String.starts_with?(&1, "a=rid"))
        ) and
        not Enum.any?(lines, fn l -> Enum.any?(deny, &String.contains?(l, &1)) end) and
        Enum.all?(for("b=AS:" <> n <- lines, do: String.to_integer(n)), &(&1 <= 1500))
    end

    test "the video envelopes follow §19.3/§19.4; the simulcast offer is dropped" do
      offer = example("call_offer_video_payload.json")
      assert offer["type"] == "call_offer" and offer["media"] == "video"
      assert offer["call_id"] =~ @uuid and offer["sent_at"] =~ @ts and offer["restart"] == false
      assert video_sdp_ok?(offer["sdp"])

      bad = example("call_offer_video_payload_bad.json")
      assert bad["media"] == "video"
      refute video_sdp_ok?(bad["sdp"])
      assert bad["sdp"] =~ "a=simulcast"

      m = example("call_media_payload.json")
      assert m["v"] == 1 and m["type"] == "call_media" and is_boolean(m["camera"])
      assert m["call_id"] =~ @uuid and m["to_device"] =~ @uuid

      e = example("call_end_video_payload.json")
      assert e["type"] == "call_end" and e["media"] == "video" and e["call_id"] =~ @uuid
      assert e["reason"] in ~w(hangup cancelled timeout declined busy failed)
      assert e["connected_at"] =~ @ts and is_integer(e["duration_s"])
    end

    test "call_signal_push_video.json → call_signal_event_video.json; error_video_not_ready.json",
         %{a: a, b: b} do
      caps = ~w(groups images deletes calls video)
      a_dev = v_dev(a, caps)
      _ = v_dev(b, caps)
      e2ee_group!(a, b)
      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})

      ex = example("call_signal_push_video.json")
      assert ex["media"] == "video"
      ref = push(chan, "call:signal", %{ex | "to" => b.user.id})
      assert_reply ref, :ok, reply
      assert keys(wire(reply)) == keys(example("call_signal_reply.json"))

      topic = "inbox:" <> a.user.id

      assert_receive %Phoenix.Socket.Message{
        topic: ^topic,
        event: "event",
        payload: %{kind: "call_signal"} = event
      }

      ev = example("call_signal_event_video.json")
      assert keys(wire(event)) == keys(ev)
      assert keys(wire(event)["data"]) == keys(ev["data"])
      assert_same_shape(wire(event), ev)
      assert event.data["media"] == "video"

      c = logged_in_user()
      befriend!(a, c)
      _ = v_dev(c, ~w(groups images deletes calls))
      e2ee_group!(a, c)

      ref =
        push(chan, "call:signal", %{ex | "to" => c.user.id, "client_msg_id" => Uniq.UUID.uuid4()})

      assert_reply ref, :error, err
      assert wire(err) == example("error_video_not_ready.json")
    end

    test "device_put_video.json and mls_group_video_ready.json", %{a: a, b: b} do
      a_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        RisiMe.GroupHelpers.api(
          :put,
          "/api/v1/me/devices/#{a_dev}",
          a.token,
          example("device_put_video.json")
        )

      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")

      assert %{capabilities: ["groups", "images", "deletes", "calls", "video"]} =
               Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)

      b_phone = v_dev(b, ~w(groups images deletes calls video))
      tablet = v_dev(b, ~w(groups images deletes))
      # The phone is still in use (seen after the tablet registered).
      :ok = RisiMe.MLS.record_instance(b.user.id, b_phone, nil, "0.3.0")
      RisiMe.GroupHelpers.clear_legacy!()
      conv = e2ee_group!(a, b)

      {200, view} = RisiMe.GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", a.token)
      ex = example("mls_group_video_ready.json")
      assert keys(view) == keys(ex)
      assert_same_shape(Map.drop(view, ~w(missing devices)), Map.drop(ex, ~w(missing devices)))
      assert view["video_ready"] == true and view["calls_ready"] == true
      assert view["missing_video"] == [%{"user_id" => b.user.id, "device_id" => tablet}]
      assert_same_shape(hd(view["missing_video"]), hd(ex["missing_video"]))
    end
  end

  describe "v1.19" do
    setup :with_attestation_key
    setup :fake_livekit

    defp fake_livekit(ctx), do: RisiMe.FakeLiveKit.setup(ctx)

    defp gc_dev(user, caps \\ ~w(groups images deletes calls video group_calls)) do
      dev = Ecto.UUID.generate()

      {:ok, _} =
        RisiMe.Devices.register(user.user.id, dev, %{
          "platform" => "android",
          "mls" => %{"signature_key" => b64(), "capabilities" => caps}
        })

      :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0")
      dev
    end

    defp gc_group(a, a_dev, others) do
      alias RisiMe.GroupHelpers, as: G

      body = %{
        "client_group_id" => Ecto.UUID.generate(),
        "member_ids" => Enum.map(others, & &1.user.id)
      }

      {201, %{"group" => %{"id" => id}}} = G.api(:post, "/api/v1/groups", a.token, body, a_dev)

      {200, _} =
        G.api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          G.create_commit([a.user.id | Enum.map(others, & &1.user.id)], {a.user.id, a_dev}),
          a_dev
        )

      id
    end

    test "the group-call envelopes follow §20.3/§20.4 (they travel inside MLS)" do
      offer = example("call_offer_sfu_payload.json")
      assert offer["v"] == 1 and offer["type"] == "call_offer" and offer["mode"] == "sfu"
      assert offer["media"] in ~w(audio video) and offer["call_id"] =~ @uuid
      assert offer["sent_at"] =~ @ts
      refute Map.has_key?(offer, "sdp") or Map.has_key?(offer, "restart")

      m = example("call_member_payload.json")
      assert m["v"] == 1 and m["type"] == "call_member" and m["call_id"] =~ @uuid
      assert m["state"] in ~w(joined declined)

      started = example("group_call_started_payload.json")
      assert started["type"] == "group_call" and started["state"] == "started"
      assert started["media"] in ~w(audio video) and started["call_id"] =~ @uuid

      ended = example("group_call_ended_payload.json")
      assert ended["type"] == "group_call" and ended["state"] == "ended"
      assert ended["reason"] in ~w(hangup timeout) and ended["connected_at"] =~ @ts
      assert is_integer(ended["duration_s"])
      assert ended["call_id"] == started["call_id"]
    end

    test "livekit_token_claims.json is exactly what the server mints; calls_room_reply.json carries it" do
      ex = example("livekit_token_claims.json")

      ours =
        RisiMe.Calls.LiveKit.participant_claims(
          ex["iss"],
          ex["sub"],
          ex["video"]["room"],
          "audio",
          ex["nbf"]
        )

      assert ours == ex
      assert ex["exp"] - ex["nbf"] == 600

      reply = example("calls_room_reply.json")
      [_, payload, _] = String.split(reply["token"], ".")
      assert payload |> Base.url_decode64!(padding: false) |> Jason.decode!() == ex
      assert reply["room"] == ex["video"]["room"] and byte_size(reply["room"]) == 22
      assert reply["identity"] == ex["sub"]
      # (The example's `expires_at` isn't the token's `exp`; the server's always is: see
      # group_calls_v119_test.exs.)
      assert reply["expires_at"] =~ @ts
    end

    test "calls_room_request.json → calls_room_reply.json; status, call_ended, call_full", %{
      a: a,
      b: b
    } do
      alias RisiMe.GroupHelpers, as: G
      a_dev = gc_dev(a)
      b_dev = gc_dev(b)
      id = gc_group(a, a_dev, [b])

      ex = example("calls_room_request.json")
      body = %{ex | "conversation_id" => id}
      {200, reply} = G.api(:post, "/api/v1/calls/rooms", a.token, body, a_dev)
      rex = example("calls_room_reply.json")
      # v1.23 §23.6 added `media` (checked in the "v1.23" describe).
      assert reply["media"] == "audio"
      reply = Map.delete(reply, "media")
      assert keys(reply) == keys(rex)
      assert_same_shape(Map.drop(reply, ["identity"]), Map.drop(rex, ["identity"]))
      assert reply["url"] == RisiMe.FakeLiveKit.config()[:url]
      assert reply["max_participants"] == rex["max_participants"]

      {200, st} =
        G.api(:post, "/api/v1/calls/rooms", b.token, %{body | "action" => "status"}, b_dev)

      sex = example("calls_room_status_reply.json")
      st = Map.delete(st, "media")
      assert keys(st) == keys(sex)
      assert_same_shape(st, sex)

      assert {404, example("error_call_ended.json")} ==
               G.api(
                 :post,
                 "/api/v1/calls/rooms",
                 b.token,
                 %{body | "action" => "join", "call_id" => Ecto.UUID.generate()},
                 b_dev
               )

      for n <- 1..32, do: RisiMe.FakeLiveKit.join(reply["room"], "u/#{n}")

      assert {409, example("error_call_full.json")} ==
               G.api(:post, "/api/v1/calls/rooms", b.token, %{body | "action" => "join"}, b_dev)
    end

    test "call_signal_push_group.json → call_signal_event_group.json", %{a: a, b: b} do
      a_dev = gc_dev(a)
      _ = gc_dev(b)
      id = gc_group(a, a_dev, [b])
      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})

      ex = example("call_signal_push_group.json")
      refute Map.has_key?(ex, "to")
      ref = push(chan, "call:signal", %{ex | "conversation_id" => id, "epoch" => 1})
      assert_reply ref, :ok, reply
      assert keys(wire(reply)) == keys(example("call_signal_reply.json"))

      topic = "inbox:" <> a.user.id

      assert_receive %Phoenix.Socket.Message{
        topic: ^topic,
        event: "event",
        payload: %{kind: "call_signal"} = event
      }

      ev = example("call_signal_event_group.json")
      assert keys(wire(event)) == keys(ev)
      assert keys(wire(event)["data"]) == keys(ev["data"])
      assert_same_shape(wire(event), ev)
      assert event.data["conversation_id"] == id
    end

    test "device_put_group_calls.json and mls_group_group_calls_ready.json", %{a: a, b: b} do
      alias RisiMe.GroupHelpers, as: G
      a_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        G.api(
          :put,
          "/api/v1/me/devices/#{a_dev}",
          a.token,
          example("device_put_group_calls.json")
        )

      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")

      assert %{capabilities: ~w(groups images deletes calls video group_calls)} =
               Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)

      b_phone = gc_dev(b)
      tablet = gc_dev(b, ~w(groups images deletes calls video))
      :ok = RisiMe.MLS.record_instance(b.user.id, b_phone, nil, "0.3.0")
      G.clear_legacy!()
      id = gc_group(a, a_dev, [b])

      {200, view} = G.api(:get, "/api/v1/mls/groups/#{id}", a.token)
      ex = example("mls_group_group_calls_ready.json")
      # The deletes readiness (v1.12) is in every view; the example leaves it out.
      view = Map.drop(view, ~w(deletes_ready missing_deletes))
      assert keys(view) == keys(ex)
      assert_same_shape(Map.drop(view, ~w(missing devices)), Map.drop(ex, ~w(missing devices)))
      assert view["group_calls_ready"] == true
      assert view["missing_group_calls"] == [%{"user_id" => b.user.id, "device_id" => tablet}]
      assert_same_shape(hd(view["missing_group_calls"]), hd(ex["missing_group_calls"]))
    end
  end

  defp atomize(map), do: Map.new(map, fn {k, v} -> {String.to_existing_atom(k), v} end)

  ## v1.20 (§21): open sign-up.

  describe "v1.20" do
    setup do
      previous = Application.get_env(:risime, :signup)

      Application.put_env(:risime, :signup,
        open: true,
        per_ip_hour: 10_000,
        per_sub_day: 10_000,
        global_per_day: 10_000
      )

      RisiMe.Auth.clear_cache()

      on_exit(fn ->
        if previous,
          do: Application.put_env(:risime, :signup, previous),
          else: Application.delete_env(:risime, :signup)
      end)
    end

    defp signup_token,
      do:
        RisiMe.OIDCHelpers.access_token(
          "v120-#{System.unique_integer([:positive])}@example.org",
          %{
            "sub" => Ecto.UUID.generate(),
            "name" => "Test User N"
          }
        )

    test "auth_config_v120.json" do
      Application.put_env(:risime, :dev_local_auth, false)
      on_exit(fn -> Application.put_env(:risime, :dev_local_auth, true) end)
      # v1.22 added `backup` (checked in auth_config_v122.json).
      {200, ours} = get_json("/api/v1/auth/config")
      assert Map.delete(ours, "backup") == example("auth_config_v120.json")
    end

    test "signup_request.json, signup_reply.json, error_signup_required.json" do
      token = signup_token()
      assert get_json("/api/v1/me", token) == {403, example("error_signup_required.json")}

      body = %{example("signup_request.json") | "phone" => unique_phone()}
      {200, ours} = req_json(:post, "/api/v1/auth/signup", token, body)
      ex = example("signup_reply.json")
      assert_same_shape(ours, ex)
      assert ours["user"]["display_name"] == ex["user"]["display_name"]

      assert Map.take(ours["user"], ~w(company phone_verified phone_confirmed vouched_by)) ==
               Map.take(ex["user"], ~w(company phone_verified phone_confirmed vouched_by))
    end

    test "error_signup_closed.json, error_phone_taken.json, error_bad_request.json, error_signup_rate_limited.json",
         %{a: a} do
      body = example("signup_request.json")

      assert req_json(:post, "/api/v1/auth/signup", signup_token(), %{
               body
               | "phone" => a.user.phone
             }) ==
               {409, example("error_phone_taken.json")}

      assert req_json(:post, "/api/v1/auth/signup", signup_token(), %{body | "phone" => "x"}) ==
               {400, example("error_bad_request.json")}

      Application.put_env(:risime, :signup, open: true, per_sub_day: 0)

      assert req_json(:post, "/api/v1/auth/signup", signup_token(), body) ==
               {429, example("error_signup_rate_limited.json")}

      Application.put_env(:risime, :signup, open: false)

      assert req_json(:post, "/api/v1/auth/signup", signup_token(), body) ==
               {403, example("error_signup_closed.json")}
    end

    test "friends_reply_v120.json and signal_friend_v120.json", %{a: a} do
      token = signup_token()
      body = %{example("signup_request.json") | "phone" => unique_phone()}
      {200, %{"user" => n}} = req_json(:post, "/api/v1/auth/signup", token, body)

      Phoenix.PubSub.subscribe(RisiMe.PubSub, Messaging.topic(a.user.id))
      {202, _} = req_json(:post, "/api/v1/friends/requests", token, %{"phone" => a.user.phone})
      assert_receive {:signal, %{kind: "friend"} = signal}
      ex_signal = example("signal_friend_v120.json")
      assert_same_shape(wire(signal), ex_signal)
      assert wire(signal)["data"]["user"]["phone_confirmed"] == false
      assert wire(signal)["data"]["user"]["user_id"] == n["id"]

      {200, ours} = req_json(:get, "/api/v1/friends", a.token)
      ex = example("friends_reply_v120.json")
      assert keys(ours) == keys(ex)
      for f <- ours["friends"], do: assert_same_shape(f, hd(ex["friends"]))
      assert [incoming] = ours["incoming"]
      assert_same_shape(incoming, hd(ex["incoming"]))
      assert incoming["phone_confirmed"] == false
      assert Enum.all?(ours["friends"], &(&1["phone_confirmed"] == true))
    end
  end

  ## v1.21 (§12.12)

  describe "v1.21" do
    setup :with_attestation_key

    defp v121_device!(user, caps \\ ["groups", "member_devices"]) do
      dev = Ecto.UUID.generate()

      {:ok, _} =
        RisiMe.Devices.register(user.user.id, dev, %{
          "platform" => "android",
          "mls" => %{
            "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
            "capabilities" => caps
          }
        })

      :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0")
      dev
    end

    # The device was last seen `hours` ago, before its user's newer device (§12.1).
    defp v121_supersede!(dev, hours) do
      past = DateTime.add(DateTime.utc_now(), -hours * 3600, :second)

      Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^dev),
        set: [last_seen_at: past, inserted_at: DateTime.add(past, -60, :second)]
      )

      Repo.update_all(from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
        set: [last_seen_at: past]
      )
    end

    defp v121_join!(user, dev) do
      {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
      {:ok, _, _} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    end

    defp v121_api(method, path, token, body, device),
      do: RisiMe.GroupHelpers.api(method, path, token, body, device)

    defp v121_ref(u, d), do: %{"user_id" => u, "device_id" => d}

    test "group_rejoin_reply_v121.json, error_rejoin_pending.json, event_group_op_cleanup.json",
         %{a: a, b: b} do
      import RisiMe.GroupHelpers, only: [create_commit: 2, clear_legacy!: 0]
      clear_legacy!()
      a1 = v121_device!(a)
      b1 = v121_device!(b)

      {201, %{"group" => %{"id" => id}}} =
        v121_api(
          :post,
          "/api/v1/groups",
          a.token,
          %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]},
          a1
        )

      {200, _} =
        v121_api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          create_commit([a.user.id, b.user.id], {a.user.id, a1}),
          a1
        )

      # The only admin reinstalls; b (a member) is online and is named.
      v121_join!(b, b1)
      a2 = v121_device!(a)
      v121_supersede!(a1, 1)

      {202, ours} = v121_api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2)
      ex = example("group_rejoin_reply_v121.json")
      assert keys(ours) == keys(ex)
      assert_group(ours["group"], ex["group"])
      assert_same_shape(ours["op"], ex["op"])
      # Two members here (the example has three): b is the one candidate.
      assert ours["candidates"] == 1 and ours["exhausted"] == false

      # error_rejoin_pending.json: the only admin's new device can't reset.
      {409, err} =
        v121_api(:post, "/api/v1/mls/groups/#{id}/reset", a.token, %{"generation" => 1}, a2)

      assert_error(err, "error_rejoin_pending.json", ~w(op_id candidates))
      assert err["error"]["op_id"] == ours["op"]["op_id"] and err["error"]["candidates"] == 1

      # b re-adds a2; a's old phone becomes a stale leaf; a2 is named for the cleanup op.
      {200, _} =
        v121_api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          b.token,
          %{
            "generation" => 1,
            "epoch" => RisiMe.Groups.epoch(id),
            "commit" => b64(),
            "welcome" => b64(),
            "added" => [v121_ref(a.user.id, a2)],
            "removed" => [],
            "op_id" => ours["op"]["op_id"],
            "meta_changed" => false
          },
          b1
        )

      v121_supersede!(a1, 25)
      v121_join!(a, a2)
      %{cleanup_ops: 1} = RisiMe.Workers.StaleLeaves.sweep()
      ev = RisiMe.GroupHelpers.last_event(a.user.id, "group_op")
      assert_same_shape(ev, example("event_group_op_cleanup.json"))
      assert ev["data"]["op"]["added"] == [] and ev["data"]["op"]["type"] == "devices"
      assert ev["data"]["op"]["removed"] == [v121_ref(a.user.id, a1)]
      assert ev["data"]["op"]["committer"] == v121_ref(a.user.id, a2)
    end

    test "mls_dm_rejoin_reply.json, mls_dm_rejoin_reply_v121.json, event_mls_dm_op.json, mls_commit_request_dm_op.json",
         %{a: a, b: b} do
      import RisiMe.GroupHelpers, only: [clear_legacy!: 0]
      clear_legacy!()
      a1 = v121_device!(a)
      b1 = v121_device!(b)
      conv = Messaging.conversation_id(a.user.id, b.user.id)

      {200, _} =
        v121_api(
          :post,
          "/api/v1/mls/groups/#{conv}/commit",
          a.token,
          %{
            "generation" => 1,
            "epoch" => 0,
            "commit" => b64(),
            "welcome" => b64(),
            "added" => [v121_ref(b.user.id, b1)]
          },
          a1
        )

      a2 = v121_device!(a)
      v121_supersede!(a1, 1)

      # The peer is offline: the op waits (committer null).
      {202, ours} = v121_api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, a2)
      v121 = example("mls_dm_rejoin_reply_v121.json")
      v116 = example("mls_dm_rejoin_reply.json")
      assert keys(ours) == keys(v121)
      assert keys(Map.delete(ours, "exhausted")) == keys(v116)
      assert_same_shape(ours, v121)
      assert_same_shape(Map.delete(ours, "exhausted"), v116)
      assert ours["candidates"] == 1 and ours["exhausted"] == false
      assert ours["op"]["added"] == [v121_ref(a.user.id, a2)]
      assert ours["op"]["removed"] == [v121_ref(a.user.id, a1)]

      # b opens the app: named; the mls_dm_op event.
      v121_join!(b, b1)
      ev = RisiMe.GroupHelpers.last_event(b.user.id, "mls_dm_op")
      assert_same_shape(ev, example("event_mls_dm_op.json"))
      assert ev["data"]["op"]["committer"] == v121_ref(b.user.id, b1)

      # mls_commit_request_dm_op.json is accepted: b adds a2 with the op id.
      body = example("mls_commit_request_dm_op.json")

      body = %{
        body
        | "epoch" => 1,
          "added" => [v121_ref(a.user.id, a2)],
          "op_id" => ours["op"]["op_id"]
      }

      assert {200, %{"epoch" => 2}} =
               v121_api(:post, "/api/v1/mls/groups/#{conv}/commit", b.token, body, b1)
    end
  end

  ## v1.22 (§22)

  describe "v1.22" do
    setup :with_attestation_key

    setup do
      dir = Path.join(System.tmp_dir!(), "risime-ex-v122-#{System.unique_integer([:positive])}")
      prev = Application.get_env(:risime, :blob_dir)
      Application.put_env(:risime, :blob_dir, dir)

      on_exit(fn ->
        Application.put_env(:risime, :blob_dir, prev)
        Application.delete_env(:risime, :backups)
        File.rm_rf(dir)
      end)
    end

    defp bk_api(method, path, token, body \\ nil, device \\ nil),
      do: RisiMe.GroupHelpers.api(method, path, token, body, device)

    defp bk_upload(token, device, params, bytes) do
      conn =
        http()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
        |> Plug.Conn.put_req_header("x-device-id", device)
        |> Plug.Conn.put_req_header("content-type", "application/octet-stream")
        |> Plug.Conn.put_req_header("content-length", Integer.to_string(byte_size(bytes)))
        |> Phoenix.ConnTest.dispatch(
          RisiMeWeb.Endpoint,
          :post,
          "/api/v1/blobs?" <> URI.encode_query(params),
          bytes
        )

      {conn.status, Jason.decode!(conn.resp_body)}
    end

    defp bk_err(name, dynamic \\ []) do
      ex = example(name)

      fn {_status, ours} ->
        assert_same_shape(ours, ex)
        assert Map.drop(ours["error"], dynamic) == Map.drop(ex["error"], dynamic)
      end
    end

    test "auth_config_v122.json and error_backup_unavailable.json", %{a: a} do
      # error_backup_unavailable.json: the switch off stops the writes.
      Application.put_env(:risime, :backups, false)
      dev = mls_device!(a)

      {503, _} =
        err = bk_api(:put, "/api/v1/backup_key", a.token, example("backup_key_put.json"), dev)

      bk_err("error_backup_unavailable.json").(err)
      {200, off} = get_json("/api/v1/auth/config")
      assert off["backup"] == "off"
      Application.delete_env(:risime, :backups)

      Application.put_env(:risime, :dev_local_auth, false)
      Application.put_env(:risime, :signup, open: true)
      RisiMe.Auth.clear_cache()

      on_exit(fn ->
        Application.put_env(:risime, :dev_local_auth, true)
        Application.delete_env(:risime, :signup)
      end)

      assert get_json("/api/v1/auth/config") == {200, example("auth_config_v122.json")}
    end

    # A device signed in with a named token (the Backup's `device_name`).
    defp named_device!(user, name) do
      dev = mls_device!(user)

      token =
        Repo.insert!(%RisiMe.Accounts.UserToken{
          user_id: user.user.id,
          token_hash: :crypto.strong_rand_bytes(32),
          device_name: name
        })

      Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^dev),
        set: [user_token_id: token.id]
      )

      dev
    end

    test "every §22.3 REST example against the server's real payloads", %{a: a} do
      dev = named_device!(a, "Pixel 8")

      # error_no_backup_key.json (GET 404; a commit before a key 409).
      {404, _} = err = bk_api(:get, "/api/v1/backup_key", a.token)
      bk_err("error_no_backup_key.json").(err)

      # backup_key_put.json → backup_key_reply.json
      put = example("backup_key_put.json")
      {200, ours} = bk_api(:put, "/api/v1/backup_key", a.token, put, dev)
      ex = example("backup_key_reply.json")
      assert_same_shape(ours, ex)

      assert Map.delete(ours["backup_key"], "updated_at") ==
               Map.delete(ex["backup_key"], "updated_at")

      assert {200, ^ours} = bk_api(:get, "/api/v1/backup_key", a.token)

      # blob_upload_backup_reply.json: two parts of one backup.
      req = example("backup_create_request.json")
      bid = Ecto.UUID.generate()

      parts =
        for n <- [300, 120] do
          params = %{
            "purpose" => "backup",
            "backup_id" => bid,
            "client_blob_id" => Ecto.UUID.generate()
          }

          {201, up} = bk_upload(a.token, dev, params, :crypto.strong_rand_bytes(n))
          assert_same_shape(up, example("blob_upload_backup_reply.json"))
          Map.take(up, ~w(blob_id size sha256))
        end

      # backup_create_request.json → backup_create_reply.json (and the replay).
      body = %{req | "backup_id" => bid, "parts" => parts, "size" => 420}
      {201, created} = bk_api(:post, "/api/v1/backups", a.token, body, dev)
      ex = example("backup_create_reply.json")
      assert_same_shape(created, ex)
      assert keys(created["backup"]) == keys(ex["backup"])
      for p <- created["backup"]["parts"], do: assert_same_shape(p, hd(ex["backup"]["parts"]))

      assert Map.take(created["backup"], ~w(created_at schema app_version bk_id current)) ==
               Map.take(ex["backup"], ~w(created_at schema app_version bk_id current))

      assert {200, ^created} = bk_api(:post, "/api/v1/backups", a.token, body, dev)

      # backups_reply.json: two more make one replaced.
      for _ <- 1..2 do
        nbid = Ecto.UUID.generate()

        params = %{
          "purpose" => "backup",
          "backup_id" => nbid,
          "client_blob_id" => Ecto.UUID.generate()
        }

        {201, up} = bk_upload(a.token, dev, params, :crypto.strong_rand_bytes(10))
        part = Map.take(up, ~w(blob_id size sha256))
        b = %{req | "backup_id" => nbid, "parts" => [part], "size" => 10}
        {201, _} = bk_api(:post, "/api/v1/backups", a.token, b, dev)
      end

      {200, list} = bk_api(:get, "/api/v1/backups", a.token)
      ex = example("backups_reply.json")
      assert keys(list) == keys(ex)
      assert_same_shape(list["quota"], ex["quota"])
      assert list["quota"]["limit"] == ex["quota"]["limit"] and list["key"] == true

      assert Enum.map(list["backups"], & &1["current"]) ==
               Enum.map(ex["backups"], & &1["current"])

      for {o, e} <- Enum.zip(list["backups"], ex["backups"]) do
        assert keys(o) == keys(e)
        assert_same_shape(Map.delete(o, "parts"), Map.delete(e, "parts"))
      end

      # blob_usage_reply_backup.json
      {200, usage} = bk_api(:get, "/api/v1/blobs/usage", a.token)
      ex = example("blob_usage_reply_backup.json")
      assert_same_shape(usage, ex)
      assert usage["backup"] == %{"used" => 20, "limit" => ex["backup"]["limit"]}

      # error_backup_key_conflict.json: another BK while backups exist.
      other = %{put | "bk_id" => Base.encode64(:crypto.strong_rand_bytes(8))}
      err = bk_api(:put, "/api/v1/backup_key", a.token, other, dev)
      assert {409, _} = err
      bk_err("error_backup_key_conflict.json").(err)

      # error_backup_device_mismatch.json: a second, live phone without replace_device.
      dev2 = named_device!(a, "Galaxy A54")
      :ok = RisiMe.MLS.record_instance(a.user.id, dev, nil, "0.3.0")
      nbid = Ecto.UUID.generate()

      params = %{
        "purpose" => "backup",
        "backup_id" => nbid,
        "client_blob_id" => Ecto.UUID.generate()
      }

      {201, up} = bk_upload(a.token, dev2, params, "abc")

      b = %{
        req
        | "backup_id" => nbid,
          "parts" => [Map.take(up, ~w(blob_id size sha256))],
          "size" => 3
      }

      err = bk_api(:post, "/api/v1/backups", a.token, b, dev2)
      assert {409, _} = err
      bk_err("error_backup_device_mismatch.json", ~w(device_id device_name)).(err)
      assert elem(err, 1)["error"]["device_id"] == dev
    end

    test "backup_file_header.json (§22.4)" do
      h = example("backup_file_header.json")

      assert keys(h) ==
               Enum.sort(
                 ~w(v schema backup_id user_id created_at app_version bk_id dek stream key)
               )

      assert h["v"] == 1 and h["schema"] == 1
      assert h["backup_id"] =~ @uuid and h["user_id"] =~ @uuid and h["created_at"] =~ @ts
      assert b64_len(h["bk_id"], 8)
      assert h["dek"]["alg"] == "A256GCM"
      assert b64_len(h["dek"]["nonce"], 12) and b64_len(h["dek"]["wrapped"], 48)

      assert h["stream"] == %{
               "alg" => "A256GCM-STREAM64K",
               "compression" => "deflate",
               "pad" => "padme"
             }

      # The key record at backup time passes the server's BackupKey checks, for the same BK.
      assert RisiMe.Backups.valid_key?(h["key"]) and h["key"]["bk_id"] == h["bk_id"]
      assert h["key"]["updated_at"] =~ @ts
      refute Map.has_key?(h["key"], "device_id")
      assert byte_size(Jason.encode!(h)) <= 65_536
    end

    test "the bundle lines (§22.5)" do
      h = example("backup_bundle_header.json")

      assert keys(h) ==
               Enum.sort(~w(v type origin schema backup_id user_id created_at app_version counts))

      assert {h["v"], h["type"], h["origin"], h["schema"]} == {1, "backup", "backup", 1}
      assert keys(h["counts"]) == Enum.sort(~w(conversations messages tombstones contacts))

      c = example("backup_entry_conversation.json")

      assert keys(c) == Enum.sort(~w(type conversation_id kind e2ee peer group chat))
      assert c["type"] == "conversation" and c["kind"] in ["dm", "group"]
      assert keys(c["group"]) == Enum.sort(~w(name icon admins created_by state))
      assert c["group"]["state"] in ~w(active left removed)

      assert keys(c["chat"]) ==
               Enum.sort(~w(cleared_upto hidden muted_until pinned archived last_read))

      m = example("backup_entry_message.json")

      assert keys(m) ==
               Enum.sort(
                 ~w(type conversation_id message_id client_msg_id from from_device server_ts payload origin shared_by status)
               )

      assert m["message_id"] =~ @timeuuid and m["server_ts"] =~ @ts
      assert m["origin"] in [nil, "shared", "own_device", "backup"]
      assert m["status"] in ["sent", "delivered", "read", nil]

      t = example("backup_entry_tombstone.json")

      assert keys(t) ==
               Enum.sort(~w(type conversation_id message_id from server_ts scope hidden))

      assert t["scope"] in ["everyone", "me"] and is_boolean(t["hidden"])

      g = example("backup_entry_group_event.json")

      assert keys(g) ==
               Enum.sort(
                 ~w(type conversation_id event_id generation action actor targets role server_ts)
               )

      assert g["event_id"] =~ @timeuuid and is_list(g["targets"])

      k = example("backup_entry_contact.json")
      assert keys(k) == Enum.sort(~w(type user_id display_name phone))
      assert k["type"] == "contact" and k["user_id"] =~ @uuid

      # Never in a backup: no MLS state, device id, cursor or tokens on any line.
      for line <- [h, c, m, t, g, k], key <- Map.keys(line) do
        refute key in ~w(device_id cursor mls_state token push_token bk)
      end
    end
  end

  describe "v1.23" do
    setup :with_attestation_key
    setup :fake_livekit

    defp sdp_lines(env), do: String.split(env["sdp"], "\r\n", trim: true)

    defp sdp_values(env, prefix),
      do: for(l <- sdp_lines(env), String.starts_with?(l, prefix), do: l)

    # §23.2 strict validation of a 1:1 `call_switch`.
    defp switch_ok?(%{"v" => 1, "type" => "call_switch"} = e) do
      Map.keys(e) -- ~w(v type call_id to_device seq action source) == [] and
        is_binary(e["call_id"]) and e["call_id"] =~ @uuid and
        is_binary(e["to_device"]) and e["to_device"] =~ @uuid and
        is_integer(e["seq"]) and e["seq"] in 1..65_535 and
        e["action"] in ~w(request accept decline cancel voice) and
        if(e["action"] == "request",
          do: e["source"] in ~w(camera screen),
          else: not Map.has_key?(e, "source")
        )
    end

    defp switch_ok?(_), do: false

    test "1:1 envelopes: features, call_switch, renegotiate offer/answer, call_media video" do
      for name <- ~w(call_offer_features_payload.json call_answer_features_payload.json) do
        f = example(name)["features"]
        assert is_list(f) and length(f) <= 8
        assert Enum.all?(f, &(is_binary(&1) and byte_size(&1) <= 32))
        assert f == ~w(switch screen)
      end

      offer = example("call_offer_features_payload.json")
      assert offer["restart"] == false and offer["media"] == "audio"
      refute Map.has_key?(offer, "renegotiate")

      req = example("call_switch_request_payload.json")
      acc = example("call_switch_accept_payload.json")
      voice = example("call_switch_voice_payload.json")
      assert Enum.all?([req, acc, voice], &switch_ok?/1)
      assert req["action"] == "request" and req["source"] == "camera" and req["seq"] == 1
      assert acc["action"] == "accept" and acc["seq"] == req["seq"]
      assert voice["action"] == "voice" and voice["seq"] == 2
      refute switch_ok?(Map.delete(req, "source"))
      refute switch_ok?(Map.put(acc, "source", "camera"))
      refute switch_ok?(%{req | "seq" => 0})
      refute switch_ok?(%{req | "action" => "video"})

      # The re-offer (§23.3): renegotiate, never restart; same fingerprint, ICE credentials and
      # setup as the call's first SDPs; BUNDLE 0 1, audio mid 0 first, video mid 1, sendrecv.
      re = example("call_offer_renegotiate_payload.json")
      first = example("call_offer_payload.json")
      assert re["renegotiate"] == true and re["restart"] == false and re["media"] == "audio"
      assert re["call_id"] == first["call_id"] and re["to_device"] =~ @uuid
      assert re["sent_at"] =~ @ts

      ans = example("call_answer_renegotiate_payload.json")
      first_ans = example("call_answer_features_payload.json")

      for {r, f, setup} <- [{re, first, "a=setup:actpass"}, {ans, first_ans, "a=setup:active"}] do
        assert sdp_values(r, "a=group:BUNDLE") == ["a=group:BUNDLE 0 1"]

        assert [{"m=audio " <> _, 0}, {"m=video " <> _, 1}] =
                 r |> sdp_values("m=") |> Enum.with_index()

        assert sdp_values(r, "a=mid:") == ["a=mid:0", "a=mid:1"]
        assert Enum.uniq(sdp_values(r, "a=fingerprint:")) == sdp_values(f, "a=fingerprint:")
        assert Enum.uniq(sdp_values(r, "a=ice-ufrag:")) == sdp_values(f, "a=ice-ufrag:")
        assert Enum.uniq(sdp_values(r, "a=ice-pwd:")) == sdp_values(f, "a=ice-pwd:")
        assert Enum.uniq(sdp_values(r, "a=setup:")) == [setup]
        assert sdp_values(r, "a=sendrecv") == ["a=sendrecv", "a=sendrecv"]
        refute r["sdp"] =~ "a=simulcast" or r["sdp"] =~ "ssrc-audio-level"
      end

      # The bad re-offer carries another fingerprint than the call's first offer → `failed`.
      bad = example("call_offer_renegotiate_payload_bad.json")
      assert bad["call_id"] == first["call_id"] and bad["renegotiate"] == true

      assert sdp_values(bad, "a=fingerprint:") |> Enum.uniq() !=
               sdp_values(first, "a=fingerprint:")

      m = example("call_media_screen_payload.json")
      assert m["video"] in ~w(off camera screen) and m["camera"] == (m["video"] == "camera")
      assert m["call_id"] =~ @uuid and m["to_device"] =~ @uuid
    end

    test "group envelopes: call_switch and call_media (no to_device)" do
      s = example("call_switch_group_payload.json")
      assert keys(s) == Enum.sort(~w(v type call_id seq action source))
      assert s["call_id"] =~ @uuid and s["seq"] in 1..65_535
      assert s["action"] == "video" and s["source"] in ~w(camera screen)

      m = example("call_media_group_payload.json")
      assert keys(m) == Enum.sort(~w(v type call_id video))
      assert m["call_id"] =~ @uuid and m["video"] in ~w(off camera screen)
    end

    test "device_put_call_switch.json keeps call_switch and screen_share", %{a: a} do
      alias RisiMe.GroupHelpers, as: G
      dev = Ecto.UUID.generate()
      ex = example("device_put_call_switch.json")

      {200, %{"attestation" => _}} = G.api(:put, "/api/v1/me/devices/#{dev}", a.token, ex)

      d = Repo.get_by(RisiMe.Devices.Device, device_id: dev)
      assert d.capabilities == ex["mls"]["capabilities"]
      assert %{call_switch: true, screen_share: true} = RisiMe.Devices.call_caps(d)
    end

    test "livekit_token_claims_v123.json and livekit_update_participant.json are what the server makes" do
      ex = example("livekit_token_claims_v123.json")

      assert RisiMe.Calls.LiveKit.participant_claims(
               ex["iss"],
               ex["sub"],
               ex["video"]["room"],
               "video",
               ex["nbf"],
               true
             ) == ex

      reply = example("calls_room_upgrade_reply.json")
      [_, payload, _] = String.split(reply["token"], ".")
      assert payload |> Base.url_decode64!(padding: false) |> Jason.decode!() == ex
      assert reply["identity"] == ex["sub"] and reply["room"] == ex["video"]["room"]
      assert reply["media"] == "video" and reply["max_participants"] == 8

      up = example("livekit_update_participant.json")
      assert RisiMe.Calls.LiveKit.video_permission(true) == up["permission"]
      assert byte_size(up["room"]) == 22
      assert [_, _] = String.split(up["identity"], "/")
    end

    test "calls_room_upgrade_request.json → calls_room_upgrade_reply.json; status; the errors", %{
      a: a,
      b: b
    } do
      alias RisiMe.GroupHelpers, as: G
      alias RisiMe.FakeLiveKit
      caps = ~w(groups images deletes calls video group_calls call_switch screen_share)
      a_dev = gc_dev(a, caps)
      b_dev = gc_dev(b, caps)
      id = gc_group(a, a_dev, [b])
      rooms = fn user, dev, body -> G.api(:post, "/api/v1/calls/rooms", user.token, body, dev) end

      ex = example("calls_room_upgrade_request.json")
      body = %{ex | "conversation_id" => id}
      {200, %{"room" => room}} = rooms.(a, a_dev, %{body | "action" => "start"})
      FakeLiveKit.join(room, "#{a.user.id}/#{a_dev}")

      # b holds no seat: not_in_call.
      assert {409, example("error_not_in_call.json")} == rooms.(b, b_dev, body)

      # Too many: 7 more present.
      for n <- 1..8, do: FakeLiveKit.join(room, "u/#{n}")
      assert {409, example("error_too_many_for_video.json")} == rooms.(a, a_dev, body)
      for n <- 1..8, do: :ok = FakeLiveKit.remove_participant(nil, room, "u/#{n}")

      {200, reply} = rooms.(a, a_dev, body)
      rex = example("calls_room_upgrade_reply.json")
      assert keys(reply) == keys(rex)
      assert_same_shape(Map.drop(reply, ["identity"]), Map.drop(rex, ["identity"]))
      assert reply["media"] == "video" and reply["max_participants"] == 8

      {:ok, claims} =
        RisiMe.Calls.LiveKit.verify(reply["token"], FakeLiveKit.config()[:api_secret])

      assert keys(claims) == keys(example("livekit_token_claims_v123.json"))
      assert claims["video"]["canPublishSources"] == ~w(microphone camera screen_share)

      assert [{:update_participant, [^room, _, perm]}] =
               for(
                 {:update_participant, _} = c <- FakeLiveKit.calls(),
                 do: c
               )
               |> Enum.take(-1)

      assert perm == example("livekit_update_participant.json")["permission"]

      {200, st} = rooms.(b, b_dev, %{body | "action" => "status"})
      sex = example("calls_room_status_reply_v123.json")
      assert keys(st) == keys(sex)
      assert_same_shape(st, sex)
      assert st["media"] == "video" and st["max_participants"] == 8
    end
  end

  describe "v1.24" do
    @t24_id ~r/^(grp|dm):/

    defp ts24?(v), do: is_binary(v) and v =~ @ts

    defp chat24_ok?(c) do
      assert c["chat_id"] =~ @t24_id
      assert c["kind"] in ~w(dm group)
      assert is_binary(c["private"]["conversation_id"])
      o = c["official"]
      assert o["state"] in ~w(none on off)
      assert is_nil(o["conversation_id"]) or o["conversation_id"] =~ ~r/^grp:/
      assert is_nil(o["changed_by"]) or o["changed_by"] =~ @uuid
      assert is_nil(o["changed_at"]) or ts24?(o["changed_at"])
      assert is_boolean(c["official_ready"]) and is_list(c["missing"])
      assert is_boolean(c["can_toggle"])
    end

    defp member24_ok?(m) do
      assert m["user_id"] =~ @uuid and is_binary(m["display_name"])
      assert m["role"] in ~w(admin member) and m["kind"] in ~w(user agent)
      assert is_binary(m["state"])
      assert is_nil(m["phone"]) or is_binary(m["phone"])
      if m["kind"] == "agent", do: assert(m["phone"] == nil)
    end

    defp group24_ok?(g) do
      assert g["id"] =~ ~r/^grp:/ and is_binary(g["state"])
      assert is_integer(g["generation"]) and g["my_role"] in ~w(admin member)
      Enum.each(g["members"], &member24_ok?/1)
      g
    end

    # The server-produced §24 examples against real responses (server S1–S4).
    test "every server-produced §24 example against the server's real payloads", %{a: a, b: b} do
      alias RisiMe.TabsHelpers, as: T
      import RisiMe.GroupHelpers, only: [api: 5, api: 4, ref: 2, last_event: 2]

      with_attestation_key(%{})
      T.tabs_on!()
      risi = T.risi_on!(10)
      post = fn path, token, body, dev -> api(:post, path, token, body, dev) end

      # auth_config_v124.json
      {200, cfg} = get_json("/api/v1/auth/config")

      assert keys(cfg) -- ["modes", "issuer", "client_id"] ==
               keys(example("auth_config_v124.json")) -- ["modes", "issuer", "client_id"]

      assert cfg["tabs"] == "on"

      # device_put_tabs.json, as it is.
      a_dev = Ecto.UUID.generate()

      {200, _} =
        api(:put, "/api/v1/me/devices/#{a_dev}", a.token, example("device_put_tabs.json"))

      assert "tabs" in Repo.get_by!(RisiMe.Devices.Device, device_id: a_dev).capabilities
      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")
      b_dev = T.tabs_device!(b)
      RisiMe.GroupHelpers.clear_legacy!()

      # error_private_tab.json: a new group naming the agent.
      {403, e} =
        post.(
          "/api/v1/groups",
          a.token,
          %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, risi.user_id]},
          a_dev
        )

      assert e == example("error_private_tab.json")

      {201, %{"group" => %{"id" => private}}} =
        post.(
          "/api/v1/groups",
          a.token,
          %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]},
          a_dev
        )

      {200, _} =
        post.(
          "/api/v1/mls/groups/#{private}/commit",
          a.token,
          RisiMe.GroupHelpers.create_commit([a.user.id, b.user.id], {a.user.id, a_dev}),
          a_dev
        )

      group_shape = fn ours, ex ->
        assert keys(ours) == keys(ex)
        assert_same_shape(Map.drop(ours, ~w(members pending)), Map.drop(ex, ~w(members pending)))
        for m <- ours["members"], do: assert(keys(m) == keys(hd(ex["members"])))
      end

      # chat_official_create.json → chat_official_create_reply.json
      {201, %{"group" => g}} =
        post.(
          "/api/v1/chats/#{private}/official",
          a.token,
          example("chat_official_create.json"),
          a_dev
        )

      group_shape.(g, example("chat_official_create_reply.json")["group"])
      official = g["id"]

      added =
        for %{user_id: u, device_id: d} <-
              RisiMe.Groups.groups_devices([a.user.id, b.user.id, risi.user_id], "tabs"),
            d != a_dev,
            do: ref(u, d)

      {200, _} =
        post.(
          "/api/v1/mls/groups/#{official}/commit",
          a.token,
          RisiMe.GroupHelpers.create_commit([], {a.user.id, a_dev}, %{"added" => added}),
          a_dev
        )

      # event_group_created_official.json, event_chat_official_created.json
      ev = last_event(b.user.id, "group_event")
      ex = example("event_group_created_official.json")

      assert_same_shape(
        Map.update!(ev, "data", &Map.drop(&1, ~w(members targets))),
        Map.update!(ex, "data", &Map.drop(&1, ~w(members targets)))
      )

      assert_same_shape(
        last_event(b.user.id, "chat_event"),
        example("event_chat_official_created.json")
      )

      # group_reply_v124.json
      {200, %{"group" => g}} = api(:get, "/api/v1/groups/#{official}", b.token, nil, b_dev)
      group_shape.(g, example("group_reply_v124.json")["group"])

      # chat_reply.json
      {200, %{"chat" => chat}} = api(:get, "/api/v1/chats/#{private}", a.token, nil, a_dev)
      assert_same_shape(chat, example("chat_reply.json")["chat"])

      # chat_patch_official.json → event_chat_official_off.json; then error_official_off.json.
      {200, %{"chat" => chat}} =
        api(
          :patch,
          "/api/v1/chats/#{private}",
          a.token,
          example("chat_patch_official.json"),
          a_dev
        )

      ex_off =
        Enum.find(example("chats_reply.json")["chats"], &(&1["official"]["state"] == "off"))

      assert_same_shape(chat, ex_off)

      assert_same_shape(
        last_event(b.user.id, "chat_event"),
        example("event_chat_official_off.json")
      )

      assert {:error, :official_off} =
               Messaging.send(
                 a.user.id,
                 %{
                   "client_msg_id" => Ecto.UUID.generate(),
                   "conversation_id" => official,
                   "ciphertext" => b64(),
                   "generation" => 1,
                   "epoch" => 1
                 },
                 device_id: a_dev
               )

      assert %{"reason" => "official_off"} == example("error_official_off.json")

      # chats_reply.json
      {200, %{"chats" => [c]}} = api(:get, "/api/v1/chats", a.token, nil, a_dev)
      assert_same_shape(c, ex_off)

      # event_group_agent_removed.json: a member commits the agent removal.
      [op] = Enum.filter(RisiMe.Groups.Ops.list(official), &(&1.type == "remove"))

      {200, _} =
        post.(
          "/api/v1/mls/groups/#{official}/commit",
          b.token,
          %{
            "generation" => 1,
            "epoch" => 1,
            "commit" => b64(),
            "op_id" => op.op_id,
            "added" => [],
            "removed" => [ref(risi.user_id, risi.device_id)]
          },
          b_dev
        )

      assert_same_shape(
        last_event(b.user.id, "group_event"),
        example("event_group_agent_removed.json")
      )

      # On again → event_chat_official_on.json; the admin's add → event_group_agent_added.json.
      {200, _} = api(:patch, "/api/v1/chats/#{private}", a.token, %{"official" => "on"}, a_dev)

      assert_same_shape(
        last_event(b.user.id, "chat_event"),
        example("event_chat_official_on.json")
      )

      [add] = Enum.filter(RisiMe.Groups.Ops.list(official), &(&1.type == "add"))

      {200, _} =
        post.(
          "/api/v1/mls/groups/#{official}/commit",
          a.token,
          %{
            "generation" => 1,
            "epoch" => 2,
            "commit" => b64(),
            "welcome" => b64(),
            "op_id" => add.op_id,
            "added" => [ref(risi.user_id, risi.device_id)],
            "removed" => []
          },
          a_dev
        )

      ev = last_event(b.user.id, "group_event")
      ex = example("event_group_agent_added.json")

      assert_same_shape(
        Map.update!(ev, "data", &Map.drop(&1, ["members"])),
        Map.update!(ex, "data", &Map.drop(&1, ["members"]))
      )

      assert Enum.map(ev["data"]["members"], &keys/1) == Enum.map(ex["data"]["members"], &keys/1)
    end

    test "auth_config_v124.json: tabs flag" do
      ex = example("auth_config_v124.json")
      assert ex["tabs"] in ~w(on off)
      assert ex["modes"] == ["oidc"] and is_binary(ex["issuer"])
      assert ex["signup"] in ~w(open closed) and ex["backup"] in ~w(on off)
    end

    test "chat_reply.json and chats_reply.json: Chat shape" do
      chat24_ok?(example("chat_reply.json")["chat"])
      chats = example("chats_reply.json")["chats"]
      assert chats != []
      Enum.each(chats, &chat24_ok?/1)
      assert Enum.any?(chats, &(&1["official"]["state"] == "off"))
    end

    test "group_reply_v124.json and chat_official_create_reply.json: Group v124 shape" do
      g = group24_ok?(example("group_reply_v124.json")["group"])
      assert g["tab"] in ~w(private official) and g["chat_kind"] in ~w(dm group)
      assert g["chat_id"] =~ @t24_id and is_list(g["agents"])
      agents = for m <- g["members"], m["kind"] == "agent", do: m["user_id"]
      assert Enum.sort(g["agents"]) == Enum.sort(agents)
      refute Enum.any?(g["members"], &(&1["kind"] == "agent" and &1["role"] == "admin"))

      c = group24_ok?(example("chat_official_create_reply.json")["group"])
      assert is_binary(c["state"])
    end

    test "chat_official_create.json and chat_patch_official.json: requests" do
      assert example("chat_official_create.json") == %{}
      assert example("chat_patch_official.json") == %{"official" => "off"}
    end

    test "errors: official_off and private_tab" do
      assert example("error_official_off.json") == %{"reason" => "official_off"}
      e = example("error_private_tab.json")["error"]
      assert e["code"] == "private_tab" and is_binary(e["message"])
    end

    test "chat_event examples" do
      for {n, action} <- [
            {"event_chat_official_created.json", "official_created"},
            {"event_chat_official_off.json", "official_off"},
            {"event_chat_official_on.json", "official_on"}
          ] do
        ex = example(n)
        assert ex["event_id"] =~ @timeuuid and ex["kind"] == "chat_event"
        d = ex["data"]
        assert keys(d) == ~w(action actor chat_id official_conversation_id server_ts)
        assert d["action"] == action and d["actor"] =~ @uuid
        assert d["chat_id"] =~ @t24_id and d["official_conversation_id"] =~ ~r/^grp:/
        assert ts24?(d["server_ts"])
      end
    end

    test "group_event examples carry chat_id, tab, chat_kind" do
      for {n, action} <- [
            {"event_group_agent_added.json", "added"},
            {"event_group_agent_removed.json", "removed"},
            {"event_group_created_official.json", "created"}
          ] do
        ex = example(n)
        assert ex["event_id"] =~ @timeuuid and ex["kind"] == "group_event"
        d = ex["data"]
        assert d["action"] == action and d["group_id"] =~ ~r/^grp:/
        assert d["tab"] == "official" and d["chat_kind"] in ~w(dm group)
        assert d["chat_id"] =~ @t24_id
        assert is_integer(d["epoch"]) and ts24?(d["server_ts"])
        assert is_list(d["targets"]) and Enum.all?(d["targets"], &(&1 =~ @uuid))
        if is_list(d["members"]), do: Enum.each(d["members"], &member24_ok?/1)
      end
    end

    test "device_put_tabs.json: tabs capability" do
      ex = example("device_put_tabs.json")
      assert "tabs" in ex["mls"]["capabilities"]
      assert is_binary(ex["mls"]["signature_key"]) and ex["platform"] == "android"
    end

    test "group_meta_official.json, backup header and conversation entry (client formats)" do
      m = example("group_meta_official.json")
      assert m["v"] == 1 and m["tab"] == "official" and m["chat_id"] =~ @t24_id
      assert is_list(m["admins"]) and is_list(m["agents"])
      assert m["agents"] -- m["admins"] == m["agents"]

      h = example("backup_bundle_header_v124.json")
      assert h["type"] == "backup" and h["schema"] == 2 and h["backup_id"] =~ @uuid

      assert Enum.all?(
               ~w(conversations messages tombstones contacts),
               &is_integer(h["counts"][&1])
             )

      e = example("backup_entry_conversation_v124.json")
      assert e["type"] == "conversation" and e["tab"] in ~w(private official)
      assert e["chat_id"] =~ @t24_id and e["chat_kind"] in ~w(dm group)
    end

    test "Risi REST replies and feedback" do
      [c] = example("risi_commitments_reply.json")["commitments"]
      assert c["commitment_id"] =~ @uuid and c["chat_id"] =~ @t24_id
      assert is_binary(c["state"]) and c["owner"] =~ @uuid and is_list(c["counterpart"])
      assert ts24?(c["created_at"]) and ts24?(c["updated_at"])

      facts = example("risi_facts_reply.json")["facts"]
      assert facts != []

      for f <- facts,
          do: assert(f["fact_id"] =~ @uuid and is_binary(f["kind"]) and is_binary(f["text"]))

      fb = example("risi_feedback.json")
      assert fb["call_ref"] =~ @uuid and fb["rating"] in ~w(up down) and is_binary(fb["reason"])
    end

    test "Risi envelopes (inside MLS): structure" do
      names =
        for n <- @files, String.starts_with?(n, "envelope_risi_"), n in @checked_v1_24, do: n

      kinds =
        for n <- names do
          e = example(n)
          assert e["v"] == 1

          case e["type"] do
            "text" ->
              assert is_binary(e["body"])
              r = e["risi"]
              assert r["v"] == 1 and is_binary(r["kind"])

              if Map.has_key?(r, "call_ref"),
                do: assert(is_nil(r["call_ref"]) or r["call_ref"] =~ @uuid)

              if Map.has_key?(r, "notify"), do: assert(Enum.all?(r["notify"], &(&1 =~ @uuid)))
              r["kind"]

            "risi_request" ->
              assert e["request_id"] =~ @uuid and is_binary(e["action"])
              nil

            "risi_action" ->
              assert e["target"] =~ @uuid and is_binary(e["action"])
              nil
          end
        end

      assert Enum.sort(Enum.reject(kinds, &is_nil/1)) ==
               Enum.sort(~w(answer commitment commitment_update digest error escalation offer
                            reminder report summary))
    end
  end

  describe "v1.25" do
    @step_statuses ~w(running ok failed timeout no_permission declined denied skipped)

    defp ts25?(v), do: is_binary(v) and v =~ @ts

    defp risi_chat25_ok?(c) do
      assert c["chat_id"] =~ ~r/^grp:/ and c["kind"] == "risi"
      assert c["private"] == nil and c["can_toggle"] == false
      o = c["official"]
      assert o["state"] in ~w(none on) and o["conversation_id"] == c["chat_id"]
      assert o["changed_by"] == nil and o["changed_at"] == nil
      assert is_boolean(c["official_ready"]) and is_list(c["missing"])
    end

    test "auth_config_v125.json: risi_tools flag" do
      ex = example("auth_config_v125.json")
      assert ex["risi_tools"] in ~w(on off) and ex["tabs"] in ~w(on off)
      assert keys(ex) -- ["risi_tools"] == keys(example("auth_config_v124.json"))
    end

    test "device_put_risi_tools.json: risi_tools with groups and tabs" do
      caps = example("device_put_risi_tools.json")["mls"]["capabilities"]
      assert "risi_tools" in caps and "tabs" in caps and "groups" in caps
    end

    test "auth_config_v125.json and device_put_risi_tools.json against the server (S9)",
         %{a: a} do
      with_attestation_key(%{})
      RisiMe.TabsHelpers.tabs_on!()
      RisiMe.TabsHelpers.risi_tools_on!()
      {200, cfg} = get_json("/api/v1/auth/config")

      assert keys(cfg) -- ["modes", "issuer", "client_id"] ==
               keys(example("auth_config_v125.json")) -- ["modes", "issuer", "client_id"]

      assert cfg["risi_tools"] == "on"

      dev = Ecto.UUID.generate()

      {200, _} =
        RisiMe.GroupHelpers.api(
          :put,
          "/api/v1/me/devices/#{dev}",
          a.token,
          example("device_put_risi_tools.json")
        )

      assert RisiMe.Devices.risi_tools_device?(a.user.id, dev)
    end

    test "chat_reply_risi.json and risi_chat_create_reply.json: the Risi chat" do
      c = example("chat_reply_risi.json")["chat"]
      risi_chat25_ok?(c)
      assert c["official"]["state"] == "on"

      %{"chat" => c, "group" => g} = example("risi_chat_create_reply.json")
      risi_chat25_ok?(c)
      assert c["official"]["state"] == "none"
      assert g["id"] == c["chat_id"] and g["chat_id"] == g["id"]
      assert g["state"] == "creating" and g["epoch"] == 0 and g["my_role"] == "admin"
      assert g["tab"] == "official" and g["chat_kind"] == "risi"

      assert [%{"kind" => "user", "role" => "admin"}, %{"kind" => "agent", "role" => "member"}] =
               g["members"]

      assert g["agents"] == for(m <- g["members"], m["kind"] == "agent", do: m["user_id"])
      assert ts25?(g["created_at"]) and g["pending"] == []
    end

    test "risi_facts_reply_v125.json: notes" do
      facts = example("risi_facts_reply_v125.json")["facts"]
      assert Enum.any?(facts, &(&1["kind"] == "note"))

      for f <- facts do
        assert keys(f) == ~w(chat_id created_at fact_id kind text)
        assert f["fact_id"] =~ @uuid and f["chat_id"] =~ ~r/^(grp|dm):/
        assert ts25?(f["created_at"]) and is_binary(f["text"])
      end
    end

    test "error_tool_call_expired.json" do
      e = example("error_tool_call_expired.json")["error"]
      assert e["code"] == "tool_call_expired" and is_binary(e["message"])
    end

    test "event_risi_tool_call_*.json: the per-device tool call" do
      for {n, tool, arg_keys} <- [
            {"event_risi_tool_call_calendar_check.json", "calendar_check", ~w(from to)},
            {"event_risi_tool_call_calendar_add.json", "calendar_add",
             ~w(all_day end start title write_id)}
          ] do
        ex = example(n)
        assert ex["event_id"] =~ @timeuuid and ex["kind"] == "risi_tool_call"
        d = ex["data"]

        assert keys(d) ==
                 ~w(args conversation_id device_id expires_at request_id server_ts to_devices
                    tool tool_call_id turn_id)

        assert d["tool"] == tool and keys(d["args"]) == arg_keys
        assert d["to_devices"] == [d["device_id"]]

        for k <- ~w(tool_call_id turn_id request_id device_id), do: assert(d[k] =~ @uuid)
        assert d["conversation_id"] =~ ~r/^grp:/
        assert ts25?(d["expires_at"]) and ts25?(d["server_ts"])
        {:ok, exp, _} = DateTime.from_iso8601(d["expires_at"])
        {:ok, at, _} = DateTime.from_iso8601(d["server_ts"])
        assert DateTime.diff(exp, at) in 1..120
      end
    end

    test "risi_tool_result_*.json: client tool results" do
      %{"status" => "ok", "result" => %{"blocks" => blocks}} =
        example("risi_tool_result_calendar_check.json")

      assert blocks != []

      for b <- blocks do
        assert keys(b) == ~w(all_day busy end start)
        assert ts25?(b["start"]) and ts25?(b["end"]) and is_boolean(b["busy"])
      end

      assert %{"status" => "ok", "result" => %{"event_id" => id}} =
               example("risi_tool_result_calendar_add.json")

      assert is_binary(id)
    end

    test "signal_risi_progress.json" do
      %{"kind" => "risi_progress", "data" => d} = example("signal_risi_progress.json")

      assert keys(d) ==
               ~w(conversation_id position request_id seq server_ts state step)

      assert d["state"] in ~w(queued working step waiting_confirm done)
      assert d["request_id"] =~ @uuid and is_integer(d["seq"]) and ts25?(d["server_ts"])
      assert d["position"] == nil or is_integer(d["position"])
      if d["state"] == "step", do: assert(d["step"]["status"] in @step_statuses)
      assert is_integer(d["step"]["n"]) and is_binary(d["step"]["tool"])
    end

    test "Risi v1.25 envelopes (inside MLS): structure" do
      a = example("envelope_risi_answer_v2.json")["risi"]
      assert a["kind"] == "answer" and a["turn_ref"] =~ @uuid
      assert Enum.all?(a["steps"], &(&1["status"] in @step_statuses and is_binary(&1["tool"])))
      assert length(a["next_steps"]) <= 3
      assert a["local_search"]["text"] |> is_binary()

      assert Enum.sort(Enum.map(a["sources"], & &1["type"])) ==
               ~w(calendar link message note)

      for s <- a["sources"] do
        case s["type"] do
          "message" -> assert s["message_id"] =~ @timeuuid and s["conversation_id"] =~ ~r/^grp:/
          "calendar" -> assert ts25?(s["start"]) and is_boolean(s["busy"])
          "note" -> assert s["fact_id"] =~ @uuid
          "link" -> assert String.starts_with?(s["url"], "https://")
        end
      end

      c = example("envelope_risi_confirm.json")["risi"]
      assert c["kind"] == "confirm" and c["write_id"] =~ @uuid
      assert c["tool"] in ~w(set_reminder calendar_add ask_risiwork)
      assert c["buttons"] in [~w(add cancel), ~w(allow cancel)]
      assert [asker] = c["for"]
      assert asker =~ @uuid and ts25?(c["expires_at"]) and ts25?(c["when"]["start"])
      assert is_binary(c["summary"]) and is_binary(c["text"]) and is_boolean(c["when"]["all_day"])

      r = example("envelope_risi_reminder_set.json")["risi"]
      assert r["kind"] == "reminder_set" and r["reminder_id"] =~ @uuid
      assert ts25?(r["when"]) and is_boolean(r["me_too"])
      assert Enum.all?(r["participants"], &(&1 =~ @uuid))

      d = example("envelope_risi_draft.json")["risi"]
      assert d["kind"] == "draft" and d["language"] in ~w(en si ta)
      assert d["target_conversation_id"] =~ ~r/^(grp|dm):/ and is_binary(d["text"])

      for {n, action} <- [
            {"envelope_risi_action_confirm_write.json", "confirm_write"},
            {"envelope_risi_action_me_too.json", "me_too"}
          ] do
        e = example(n)
        assert e["type"] == "risi_action" and e["action"] == action
        assert e["target"] =~ @uuid and e["edit"] == nil
      end
    end
  end

  describe "v1.26" do
    @skill_ids ~w(alarm reminders calendar scheduled_messages email)
    @client_perms ~w(granted denied not_asked not_needed unsupported unknown)
    @undo_kinds ~w(server client manual none)
    @undo_states [nil | ~w(available pending done failed expired)]
    @entry_actions ~w(alarm_set reminder_set reminder_cancelled calendar_added calendar_removed
                      message_scheduled scheduled_cancelled skill_on skill_off skill_ask
                      skill_allowed)

    defp ts26?(v), do: is_binary(v) and v =~ @ts

    defp skill26_ok?(s) do
      assert keys(s) ==
               ~w(available can cannot client description id kind modes permissions state
                  state_changed_at title tools undo where)

      assert s["id"] in @skill_ids and s["kind"] in ~w(builtin partner)
      assert s["where"] in ~w(phone server both)
      assert s["modes"] in [~w(ask), ~w(ask allowed)]
      assert s["undo"] in ~w(full until_fired until_sent manual none)
      assert s["state"] in ~w(off ask allowed) and is_boolean(s["available"])
      assert s["state_changed_at"] == nil or ts26?(s["state_changed_at"])
      assert Enum.all?(s["can"] ++ s["cannot"] ++ s["tools"], &is_binary/1)

      for p <- s["permissions"] do
        assert keys(p) == ~w(label name runtime scope)
        assert p["scope"] in ~w(android oauth risime) and is_boolean(p["runtime"])
      end

      case s["client"] do
        nil ->
          :ok

        c ->
          assert keys(c) == ~w(device_id permission reported_at)
          assert c["device_id"] =~ @uuid and c["permission"] in @client_perms
          assert ts26?(c["reported_at"])
      end
    end

    defp entry26_ok?(e) do
      assert keys(e) ==
               ~w(action at conversation_id device_id entry_id skill_id summary
                  target_conversation_id undo undo_token via)

      assert e["entry_id"] =~ @uuid and e["skill_id"] in @skill_ids
      assert e["action"] in @entry_actions and is_binary(e["summary"]) and ts26?(e["at"])
      assert e["via"] in ~w(confirm allowed calendar_offer settings undo)
      assert keys(e["undo"]) == ~w(hint kind state until)
      assert e["undo"]["kind"] in @undo_kinds and e["undo"]["state"] in @undo_states
      assert e["undo_token"] != nil == (e["undo"]["state"] == "available")
    end

    test "auth_config_v126.json and device_put_risi_skills.json" do
      ex = example("auth_config_v126.json")
      assert ex["risi_skills"] in ~w(on off)
      assert keys(ex) -- ["risi_skills"] == keys(example("auth_config_v125.json"))
      caps = example("device_put_risi_skills.json")["mls"]["capabilities"]
      assert "risi_skills" in caps and "risi_tools" in caps
    end

    test "auth_config_v126.json and device_put_risi_skills.json against the server (S14)",
         %{a: a} do
      with_attestation_key(%{})
      RisiMe.TabsHelpers.tabs_on!()
      RisiMe.TabsHelpers.risi_tools_on!()
      RisiMe.RisiHelpers.skills_on!()
      {200, cfg} = get_json("/api/v1/auth/config")

      assert keys(cfg) -- ["modes", "issuer", "client_id"] ==
               keys(example("auth_config_v126.json")) -- ["modes", "issuer", "client_id"]

      assert cfg["risi_skills"] == "on"
      dev = Ecto.UUID.generate()

      {200, _} =
        RisiMe.GroupHelpers.api(
          :put,
          "/api/v1/me/devices/#{dev}",
          a.token,
          example("device_put_risi_skills.json")
        )

      assert RisiMe.Devices.risi_skills_device?(a.user.id, dev)

      # The server's GET /risi/skills has the example's shape.
      {200, %{"skills" => skills}} =
        RisiMe.GroupHelpers.api(:get, "/api/v1/risi/skills", a.token, nil, dev)

      for {ours, theirs} <- Enum.zip(skills, example("risi_skills_reply.json")["skills"]) do
        skill26_ok?(ours)
        assert keys(ours) == keys(theirs) and ours["id"] == theirs["id"]
      end
    end

    test "risi_skills_reply.json, risi_skills_patch*.json: the registry shape" do
      skills = example("risi_skills_reply.json")["skills"]
      assert Enum.map(skills, & &1["id"]) == @skill_ids
      Enum.each(skills, &skill26_ok?/1)

      %{"changes" => changes} = p = example("risi_skills_patch.json")
      assert is_boolean(p["cancel_pending"]) and length(changes) in 1..10

      for c <- changes do
        assert c["id"] in @skill_ids
        assert Map.has_key?(c, "state") or Map.has_key?(c, "client_permission")
      end

      Enum.each(example("risi_skills_patch_reply.json")["skills"], &skill26_ok?/1)
    end

    test "risi_skill_activity_*.json and risi_skill_undo*.json: entries" do
      for n <- ~w(risi_skill_activity_reply.json risi_skill_activity_scheduled_reply.json) do
        %{"entries" => es, "has_more" => more} = example(n)
        assert is_boolean(more) and es != []
        Enum.each(es, &entry26_ok?/1)
      end

      assert is_binary(example("risi_skill_undo.json")["undo_token"])
      %{"entry" => e} = example("risi_skill_undo_reply.json")
      entry26_ok?(e)
      assert e["undo"]["state"] == "pending" and e["undo"]["kind"] == "client"
    end

    test "error_skill_unavailable.json and error_undo_unavailable.json" do
      for {n, code} <- [
            {"error_skill_unavailable.json", "skill_unavailable"},
            {"error_undo_unavailable.json", "undo_unavailable"}
          ] do
        e = example(n)["error"]
        assert e["code"] == code and is_binary(e["message"])
      end
    end

    test "event_risi_tool_call_*.json (v1.26): the new client tools" do
      for {n, tool, arg_keys, undo?} <- [
            {"event_risi_tool_call_set_alarm.json", "set_alarm", ~w(days label time write_id),
             false},
            {"event_risi_tool_call_schedule_message.json", "schedule_message",
             ~w(at conversation_id repeat text write_id), false},
            {"event_risi_tool_call_cancel_scheduled.json", "cancel_scheduled",
             ~w(schedule_id write_id), true},
            {"event_risi_tool_call_calendar_remove.json", "calendar_remove", ~w(target_write_id),
             true}
          ] do
        ex = example(n)
        assert ex["event_id"] =~ @timeuuid and ex["kind"] == "risi_tool_call"
        d = ex["data"]

        assert keys(d) ==
                 ~w(args conversation_id device_id expires_at request_id server_ts to_devices
                    tool tool_call_id turn_id undo_entry_id)

        assert d["tool"] == tool and keys(d["args"]) == arg_keys
        assert d["to_devices"] == [d["device_id"]] and d["device_id"] =~ @uuid

        if undo? do
          assert d["undo_entry_id"] =~ @uuid
          assert d["turn_id"] == nil and d["request_id"] == nil and d["conversation_id"] == nil
        else
          assert d["undo_entry_id"] == nil and d["conversation_id"] =~ ~r/^grp:/
          for k <- ~w(turn_id request_id), do: assert(d[k] =~ @uuid)
        end

        {:ok, exp, _} = DateTime.from_iso8601(d["expires_at"])
        {:ok, at, _} = DateTime.from_iso8601(d["server_ts"])
        assert DateTime.diff(exp, at) in 1..120
      end
    end

    test "risi_tool_result_*.json (v1.26)" do
      assert %{"status" => "ok", "result" => %{"alarm_set" => true}} =
               example("risi_tool_result_set_alarm.json")

      %{"status" => "ok", "result" => %{"schedule_id" => sid}} =
        example("risi_tool_result_schedule_message.json")

      assert sid =~ @uuid

      %{"status" => "ok", "result" => r} = example("risi_tool_result_cancel_scheduled.json")
      assert keys(r) == ~w(cancelled reason) and is_boolean(r["cancelled"])

      %{"status" => "ok", "result" => r} = example("risi_tool_result_calendar_remove.json")
      assert keys(r) == ~w(reason removed) and is_boolean(r["removed"])
    end

    test "Risi v1.26 envelopes (inside MLS): structure" do
      for n <-
            ~w(envelope_risi_confirm_set_alarm.json envelope_risi_confirm_schedule_message.json) do
        c = example(n)["risi"]
        assert c["kind"] == "confirm" and c["write_id"] =~ @uuid
        assert c["tool"] in ~w(set_alarm schedule_message cancel_scheduled)
        assert c["skill_id"] in @skill_ids and is_map(c["args"])
        refute Map.has_key?(c["args"], "write_id")
        assert c["buttons"] == ~w(add cancel) and length(c["for"]) == 1
        assert ts26?(c["expires_at"]) and is_binary(c["summary"])
      end

      o = example("envelope_risi_calendar_offer.json")["risi"]
      assert o["kind"] == "calendar_offer" and o["offer_id"] =~ @uuid
      assert o["buttons"] == ~w(add decline) and length(o["for"]) in 1..8
      assert ts26?(o["start"]) and ts26?(o["end"]) and o["start"] < o["end"]
      assert is_boolean(o["all_day"]) and ts26?(o["expires_at"])
      assert Enum.all?(o["source_message_ids"], &(&1 =~ @timeuuid))
      assert o["reminder_before_min"] == nil or is_integer(o["reminder_before_min"])

      for {n, action, opts} <- [
            {"envelope_risi_action_calendar_accept.json", "calendar_accept",
             %{"reminder" => true}},
            {"envelope_risi_action_calendar_decline.json", "calendar_decline", nil}
          ] do
        e = example(n)
        assert e["type"] == "risi_action" and e["action"] == action
        assert e["target"] =~ @uuid and e["edit"] == nil and e["options"] == opts
      end

      d = example("envelope_risi_skill_done.json")["risi"]
      assert d["kind"] == "skill_done" and d["entry_id"] =~ @uuid
      assert d["skill_id"] in @skill_ids and d["action"] in @entry_actions
      assert d["undo"]["kind"] in @undo_kinds

      n = example("envelope_risi_skill_needed.json")["risi"]
      assert n["kind"] == "skill_needed" and n["skill_id"] in @skill_ids
      assert n["reason"] in ~w(off no_permission unavailable) and is_boolean(n["was_on"])
      assert n["buttons"] == ["open_skills"]
    end
  end

  describe "v1.27" do
    @item_states ~w(proposed confirmed edited declined done cancelled expired)

    defp ts27?(v), do: is_binary(v) and v =~ @ts
    defp uuids27?(l), do: is_list(l) and Enum.all?(l, &(&1 =~ @uuid))

    defp made_by27_ok?(m, model?) do
      assert keys(m) == ~w(also at model provider)
      assert ts27?(m["at"]) and is_binary(m["provider"]) and is_list(m["also"])
      if model?, do: assert(is_binary(m["model"])), else: assert(m["model"] == nil)

      for a <- m["also"] do
        assert keys(a) == ~w(model provider task)
        assert is_binary(a["model"]) and is_binary(a["provider"]) and is_binary(a["task"])
      end
    end

    defp item27_ok?(i) do
      assert keys(i) == ~w(all_day counterpart due due_text item_id owner state text)
      assert i["item_id"] =~ @uuid and i["owner"] =~ @uuid and uuids27?(i["counterpart"])
      assert String.length(i["text"]) in 1..200 and is_boolean(i["all_day"])
      assert i["due"] == nil or ts27?(i["due"])
      assert i["state"] in @item_states
    end

    defp summary27_ok?(r) do
      assert keys(r) ==
               ~w(call_id call_ref chat_id conversation_id duration_s ended_at expires_at for
                  items key_points kind made_by media notify source started_at summary_id v
                  with)

      assert r["kind"] == "discussion_summary" and r["v"] == 1
      assert r["summary_id"] =~ @uuid and r["for"] =~ @uuid and uuids27?(r["with"])
      assert r["conversation_id"] =~ ~r/^grp:/ and r["notify"] == [r["for"]]
      assert ts27?(r["started_at"]) and ts27?(r["ended_at"]) and ts27?(r["expires_at"])
      assert r["started_at"] <= r["ended_at"]
      assert length(r["key_points"]) in 1..8
      assert Enum.all?(r["key_points"], &(String.length(&1) in 1..200))
      assert length(r["items"]) in 1..10
      Enum.each(r["items"], &item27_ok?/1)
      made_by27_ok?(r["made_by"], true)

      case r["source"] do
        "chat" ->
          assert r["call_id"] == nil and r["media"] == nil and r["duration_s"] == nil

        "call" ->
          assert r["call_id"] =~ @uuid and r["media"] in ~w(audio video)
          assert is_integer(r["duration_s"])
      end
    end

    defp card27_ok?(r) do
      assert keys(r) ==
               ~w(call_id call_ref duration_s ended_at items_count kind made_by media notify
                  source started_at summary summary_id v with)

      assert r["kind"] == "discussion_card" and r["notify"] == []
      assert r["summary_id"] =~ @uuid and uuids27?(r["with"])
      assert String.length(r["summary"]) in 1..280 and is_integer(r["items_count"])
      assert r["items_count"] >= 1 and r["source"] in ~w(chat call)
      made_by27_ok?(r["made_by"], true)
    end

    defp update27_ok?(r) do
      assert keys(r) ==
               ~w(all_day by call_ref due item_id kind made_by notify state summary_id text v)

      assert r["kind"] == "item_update" and r["notify"] == [] and r["call_ref"] == nil
      assert r["item_id"] =~ @uuid and r["summary_id"] =~ @uuid and r["by"] =~ @uuid
      assert r["state"] in @item_states and is_boolean(r["all_day"])
      made_by27_ok?(r["made_by"], false)
    end

    defp reminder27_ok?(r) do
      base = ~w(all_day call_ref due item_id kind made_by notify summary_id text v)

      extra =
        case r["kind"] do
          "item_due" -> ~w(buttons moment owner role)
          "item_overdue" -> ~w(buttons overdue_by)
          "item_nudge" -> ~w(overdue_by owner)
        end

      assert keys(r) == Enum.sort(base ++ extra)
      assert r["item_id"] =~ @uuid and r["summary_id"] =~ @uuid and ts27?(r["due"])
      assert r["call_ref"] == nil and length(r["notify"]) == 1
      made_by27_ok?(r["made_by"], false)

      case r["kind"] do
        "item_due" ->
          assert r["moment"] in ~w(before at today) and r["role"] in ~w(owner counterpart)

          if r["role"] == "owner",
            do: assert(r["buttons"] == ~w(done new_date) and r["notify"] == [r["owner"]]),
            else: assert(r["buttons"] == [] and r["moment"] == "today")

        "item_overdue" ->
          assert r["buttons"] == ~w(done new_date) and is_integer(r["overdue_by"])

        "item_nudge" ->
          assert is_integer(r["overdue_by"]) and r["notify"] != [r["owner"]]
      end
    end

    defp commitment27_ok?(c) do
      assert keys(c) ==
               ~w(all_day chat_id commitment_id counterpart created_at due due_text
                  official_conversation_id owner role source state summary_id text updated_at)

      assert c["role"] in ~w(owner counterpart) and c["source"] in ~w(chat call)
      assert c["summary_id"] =~ @uuid and is_boolean(c["all_day"])
      assert c["state"] in ~w(confirmed edited done)
    end

    test "auth_config_v127.json and device_put_risi_ledger.json" do
      ex = example("auth_config_v127.json")
      assert ex["risi_ledger"] in ~w(on off) and ex["risi_transcribe"] in ~w(on off)

      assert keys(ex) -- ["risi_ledger", "risi_transcribe"] ==
               keys(example("auth_config_v126.json"))

      caps = example("device_put_risi_ledger.json")["mls"]["capabilities"]
      assert "risi_ledger" in caps and "risi_tools" in caps
    end

    test "auth_config_v127.json and device_put_risi_ledger.json against the server (S28)",
         %{a: a} do
      with_attestation_key(%{})
      RisiMe.TabsHelpers.tabs_on!()
      RisiMe.TabsHelpers.risi_tools_on!()
      RisiMe.RisiHelpers.skills_on!()
      RisiMe.RisiHelpers.restore_on_exit([:risi_ledger, :risi_transcribe, :risi_speech_ready])

      # Default off: both left out (absent = off), the reply stays v1.26.
      {200, cfg} = get_json("/api/v1/auth/config")
      refute Map.has_key?(cfg, "risi_ledger") or Map.has_key?(cfg, "risi_transcribe")

      # RISI_TRANSCRIBE alone (no ledger, no healthy speech model) never offers transcription.
      Application.put_env(:risime, :risi_transcribe, true)
      {200, cfg} = get_json("/api/v1/auth/config")
      refute Map.has_key?(cfg, "risi_transcribe")

      Application.put_env(:risime, :risi_ledger, true)
      {200, cfg} = get_json("/api/v1/auth/config")
      assert cfg["risi_ledger"] == "on" and not Map.has_key?(cfg, "risi_transcribe")

      Application.put_env(:risime, :risi_speech_ready, true)
      {200, cfg} = get_json("/api/v1/auth/config")

      assert keys(cfg) -- ["modes", "issuer", "client_id"] ==
               keys(example("auth_config_v127.json")) -- ["modes", "issuer", "client_id"]

      assert cfg["risi_ledger"] == "on" and cfg["risi_transcribe"] == "on"

      dev = Ecto.UUID.generate()

      {200, _} =
        RisiMe.GroupHelpers.api(
          :put,
          "/api/v1/me/devices/#{dev}",
          a.token,
          example("device_put_risi_ledger.json")
        )

      assert RisiMe.Devices.risi_ledger_device?(a.user.id, dev)
      assert RisiMe.Devices.any_risi_ledger?(a.user.id)

      # Without risi_tools the capability is dropped.
      old = Ecto.UUID.generate()

      body =
        update_in(
          example("device_put_risi_ledger.json"),
          ["mls", "capabilities"],
          &(&1 -- ["risi_tools"])
        )

      {200, _} = RisiMe.GroupHelpers.api(:put, "/api/v1/me/devices/#{old}", a.token, body)
      refute RisiMe.Devices.risi_ledger_device?(a.user.id, old)
    end

    test "made_by (§27.1): envelope_risi_answer_made_by.json and every v1.27 Risi envelope" do
      r = example("envelope_risi_answer_made_by.json")["risi"]
      assert r["kind"] == "answer" and r["call_ref"] =~ @uuid
      made_by27_ok?(r["made_by"], true)
      assert r["made_by"]["provider"] == "risime" and r["made_by"]["also"] == []

      for n <- @checked_v1_27, String.starts_with?(n, "envelope_risi_"), r = example(n)["risi"] do
        assert Map.has_key?(r, "made_by"), n
        made_by27_ok?(r["made_by"], r["call_ref"] != nil)
      end
    end

    test "discussion_summary (chat and call) and discussion_card: structure" do
      chat = example("envelope_risi_discussion_summary_chat.json")
      assert chat["type"] == "text" and is_binary(chat["body"])
      summary27_ok?(chat["risi"])
      assert chat["risi"]["source"] == "chat"

      call = example("envelope_risi_discussion_summary_call.json")["risi"]
      summary27_ok?(call)
      assert call["source"] == "call"
      assert [%{"task" => "transcribe"}] = call["made_by"]["also"]

      card = example("envelope_risi_discussion_card.json")
      card27_ok?(card["risi"])
      assert card["body"] =~ "Details in your Risi chat"
    end

    test "item_update, item_due (owner, counterpart), item_overdue, item_nudge: structure" do
      update27_ok?(example("envelope_risi_item_update.json")["risi"])

      for n <- ~w(envelope_risi_item_due.json envelope_risi_item_due_counterpart.json
                  envelope_risi_item_overdue.json envelope_risi_item_nudge.json) do
        reminder27_ok?(example(n)["risi"])
      end
    end

    test "envelope_risi_digest_personal.json" do
      r = example("envelope_risi_digest_personal.json")["risi"]

      assert r["kind"] == "digest" and r["scope"] == "personal" and
               r["date"] =~ ~r/^\d{4}-\d\d-\d\d$/

      assert length(r["notify"]) == 1
      made_by27_ok?(r["made_by"], false)

      for i <- r["items"] do
        assert keys(i) == ~w(commitment_id due owner state text)
        assert i["state"] in ~w(confirmed edited)
      end
    end

    test "item actions: envelope_risi_action_item_*.json" do
      for {n, action} <- [
            {"envelope_risi_action_item_confirm.json", "item_confirm"},
            {"envelope_risi_action_item_decline.json", "item_decline"},
            {"envelope_risi_action_item_edit.json", "item_edit"}
          ] do
        e = example(n)
        assert e["type"] == "risi_action" and e["action"] == action and e["target"] =~ @uuid

        if action == "item_edit" do
          assert keys(e["edit"]) == ~w(all_day due text)
          assert String.length(e["edit"]["text"]) in 1..200 and ts27?(e["edit"]["due"])
        else
          assert e["edit"] == nil
        end
      end
    end

    test "risi_commitments_reply_v127.json: the ledger fields" do
      cs = example("risi_commitments_reply_v127.json")["commitments"]
      Enum.each(cs, &commitment27_ok?/1)
      assert Enum.map(cs, & &1["role"]) == ~w(owner counterpart)

      # Only additive over v1.24.
      [old] = example("risi_commitments_reply.json")["commitments"]

      for c <- cs,
          do: assert(keys(c) -- ~w(all_day role source summary_id) == keys(old))
    end

    test "call transcription (§27.7, §27.8): payloads, rooms, signal, token claims" do
      for n <- ~w(call_offer_sfu_risi_payload.json group_call_started_risi_payload.json) do
        e = example(n)
        assert e["risi"] == "listen" and e["call_id"] =~ @uuid
      end

      assert example("call_offer_sfu_risi_payload.json")["mode"] == "sfu"
      assert example("group_call_started_risi_payload.json")["state"] == "started"

      req = example("calls_room_request_risi.json")
      assert req["risi_listen"] == true and req["action"] == "start"
      assert keys(req) -- ["risi_listen"] == keys(example("calls_room_request.json"))

      stop = example("calls_room_risi_stop.json")
      assert stop["action"] == "risi_stop" and stop["conversation_id"] =~ ~r/^grp:/

      reply = example("calls_room_reply_risi.json")
      assert reply["risi"]["state"] in ~w(requested unavailable off)
      assert keys(reply["risi"]) == ~w(reason state)

      status = example("calls_room_status_reply_v127.json")
      assert keys(status) -- ["risi", "media"] == keys(example("calls_room_status_reply.json"))
      assert status["risi"]["state"] in ~w(listening stopped unavailable off)

      assert example("calls_room_risi_stop_reply.json") ==
               %{"risi" => %{"state" => "stopped", "reason" => "stopped"}}

      for n <- ~w(envelope_risi_call_listen.json envelope_risi_call_listen_stopped.json) do
        r = example(n)["risi"]

        assert keys(r) ==
                 ~w(by call_id call_ref kind made_by notify reason since state v)

        assert r["kind"] == "call_listen" and r["state"] in ~w(listening stopped)
        assert r["notify"] == [] and ts27?(r["since"])
        made_by27_ok?(r["made_by"], false)

        if r["state"] == "stopped",
          do: assert(r["by"] =~ @uuid and r["reason"] == "stopped"),
          else: assert(r["by"] == nil and r["reason"] == nil)
      end

      %{"kind" => "call_risi", "data" => d} = example("signal_call_risi.json")

      assert keys(d) == ~w(by call_id conversation_id reason server_ts state)
      assert d["state"] in ~w(listening stopped unavailable) and ts27?(d["server_ts"])

      claims = example("livekit_token_claims_risi.json")
      v = claims["video"]
      assert v["canPublish"] == false and v["canPublishSources"] == []
      assert v["canSubscribe"] == true and v["canPublishData"] == false
      assert v["hidden"] == false and v["recorder"] == false
      assert keys(claims) == keys(example("livekit_token_claims.json"))
    end
  end

  describe "v1.28" do
    @dir28 ~w(i_promised promised_to_me others)

    defp calendar_ref28?(nil), do: true

    defp calendar_ref28?(c) do
      keys(c) == ~w(account name) and String.length(c["name"]) in 1..100 and
        (c["account"] == nil or is_binary(c["account"]))
    end

    test "the action card and the offer card (§28.4, §28.5)" do
      card = example("envelope_risi_confirm_calendar_add.json")["risi"]
      offer = example("envelope_risi_confirm_offer.json")["risi"]
      v126 = example("envelope_risi_confirm_set_alarm.json")["risi"]

      for r <- [card, offer] do
        assert r["kind"] == "confirm" and r["tool"] == "calendar_add"
        assert r["skill_id"] == "calendar"
        assert Map.has_key?(r, "calendar") and calendar_ref28?(r["calendar"])
        assert keys(r["args"]) == ~w(all_day end start title) and r["buttons"] == ~w(add cancel)
        assert r["when"]["start"] == r["args"]["start"] and r["text"] == r["args"]["title"]
        made_by27_ok?(r["made_by"], r["call_ref"] != nil)
      end

      # Additive over the v1.26 card: `calendar` (and `made_by`); the offer adds origin, item_id.
      assert keys(card) -- ~w(calendar made_by) == keys(v126)
      assert keys(offer) -- ~w(origin item_id) == keys(card)
      assert offer["origin"] == "offer" and offer["item_id"] =~ @uuid
      assert offer["turn_ref"] == nil and offer["made_by"]["model"] == nil
      assert offer["expires_at"] == offer["args"]["start"]
    end

    test "confirm_write edit, the calendar_add result and the Calendar skill PATCH (§28.4)" do
      e = example("envelope_risi_action_confirm_write_edit.json")
      assert e["action"] == "confirm_write" and e["target"] =~ @uuid
      assert keys(e) == keys(example("envelope_risi_action_confirm_write.json"))
      assert keys(e["edit"]) -- ~w(all_day end start title) == []
      assert ts27?(e["edit"]["start"]) and ts27?(e["edit"]["end"])
      assert e["edit"]["start"] < e["edit"]["end"]

      r = example("risi_tool_result_calendar_add_v128.json")
      assert r["status"] == "ok" and keys(r["result"]) == ~w(calendar event_id)
      assert calendar_ref28?(r["result"]["calendar"])

      %{"changes" => [c]} = example("risi_skills_patch_calendar.json")
      assert c["id"] == "calendar" and keys(c) == ~w(calendar id)
      assert calendar_ref28?(c["calendar"])
    end

    test "item_clarify (§28.6)" do
      e = example("envelope_risi_item_clarify.json")
      r = e["risi"]

      assert keys(r) ==
               ~w(buttons call_ref due_text item_id kind made_by notify question summary_id text v)

      assert r["kind"] == "item_clarify" and r["item_id"] =~ @uuid and e["body"] == r["question"]
      assert r["buttons"] in [["new_date"], []] and length(r["notify"]) == 1
      made_by27_ok?(r["made_by"], false)
    end

    test "My promises and the personal digest (§28.7, §28.8)" do
      ex = example("risi_commitments_reply_v128.json")
      cs = ex["commitments"]
      v127 = example("risi_commitments_reply_v127.json")["commitments"]
      [v124] = example("risi_commitments_reply.json")["commitments"]

      new =
        ~w(all_day direction needs_clarification owner_name source_conversation_id
           source_message_id source_message_ids status)

      for c <- cs do
        assert c["direction"] in @dir28 and is_boolean(c["needs_clarification"])
        assert is_boolean(c["all_day"]) and c["source_conversation_id"] =~ ~r/^grp:/
        assert c["source_message_id"] == List.first(c["source_message_ids"])
        assert Enum.all?(c["source_message_ids"], &(&1 =~ @timeuuid))
        assert c["direction"] == "i_promised" == (c["owner_name"] == "You")

        assert c["status"] ==
                 if(c["needs_clarification"], do: "needs_clarification", else: c["state"])

        if Map.has_key?(c, "summary_id"),
          do: assert(keys(c) -- new == keys(hd(v127)) -- ["all_day"]),
          else: assert(keys(c) -- new == keys(v124))
      end

      assert keys(ex) == ~w(commitments totals) and keys(ex["totals"]) == Enum.sort(@dir28)

      assert ex["totals"] ==
               Map.merge(
                 Map.new(@dir28, &{&1, 0}),
                 Enum.frequencies_by(cs, & &1["direction"])
               )

      d = example("envelope_risi_digest_personal_v128.json")["risi"]

      assert keys(d) ==
               Enum.sort(
                 keys(example("envelope_risi_digest_personal.json")["risi"]) ++ ["totals"]
               )

      assert d["totals"] == ex["totals"]
      assert Enum.map(d["items"], & &1["commitment_id"]) == Enum.map(cs, & &1["commitment_id"])

      for i <- d["items"] do
        assert keys(i) == ~w(commitment_id direction due owner state text)
        assert i["direction"] in @dir28
      end
    end

    test "30-day summaries (§27.13)" do
      req = example("envelope_risi_request_period.json")
      assert req["action"] == "summarise" and req["scope"] == %{"period" => "7d"}
      assert keys(req) == keys(example("envelope_risi_request.json"))

      e = example("envelope_risi_summary_period.json")
      r = e["risi"]
      assert r["kind"] == "summary" and e["body"] =~ ~r/^Summary of the last 7 days \(/
      v124 = example("envelope_risi_summary.json")["risi"]
      assert keys(r) -- ~w(days made_by period) == keys(v124)
      assert keys(r["period"]) == ~w(from scope to)
      assert r["period"]["scope"] in ~w(today 7d 30d range)
      assert ts27?(r["period"]["from"]) and ts27?(r["period"]["to"])
      made_by27_ok?(r["made_by"], true)

      for d <- r["days"] do
        assert keys(d) == ~w(date scope summary_id to) and d["scope"] in ~w(day week)
        assert d["date"] =~ ~r/^\d{4}-\d\d-\d\d$/ and d["summary_id"] =~ @uuid
      end

      [plain | sums] = example("risi_facts_reply_summaries.json")["facts"]
      assert keys(plain) == keys(hd(example("risi_facts_reply_v125.json")["facts"]))

      for f <- sums do
        assert f["kind"] == "summary" and f["scope"] in ~w(day week)
        assert keys(f) == Enum.sort(keys(plain) ++ ~w(period scope))
        assert keys(f["period"]) == ~w(from to)
      end
    end
  end
end
