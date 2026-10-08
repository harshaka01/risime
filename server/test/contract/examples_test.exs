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
  # parse-only placeholders until the server implements it.
  @pending_v1_21 ~w(error_rejoin_pending.json event_group_op_cleanup.json event_mls_dm_op.json
                    group_rejoin_reply_v121.json mls_commit_request_dm_op.json
                    mls_dm_rejoin_reply.json mls_dm_rejoin_reply_v121.json)

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
        @pending_v1_21

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
    assert Map.drop(ours, ["phone_verification", "signup"]) == example("auth_config.json")
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
      assert Map.delete(ours, "signup") == example("auth_config_v14.json")
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
      assert_same_shape(Map.delete(usage, "history"), example("blob_usage_reply.json"))
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
      assert_same_shape(usage, example("blob_usage_reply_history.json"))
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
      assert keys(reply) == keys(rex)
      assert_same_shape(Map.drop(reply, ["identity"]), Map.drop(rex, ["identity"]))
      assert reply["url"] == RisiMe.FakeLiveKit.config()[:url]
      assert reply["max_participants"] == rex["max_participants"]

      {200, st} =
        G.api(:post, "/api/v1/calls/rooms", b.token, %{body | "action" => "status"}, b_dev)

      sex = example("calls_room_status_reply.json")
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
      assert get_json("/api/v1/auth/config") == {200, example("auth_config_v120.json")}
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
end
