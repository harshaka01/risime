defmodule RisiMeWeb.GroupsTest do
  @moduledoc "Contract v1.9 §12: groups REST, pending ops, committers, commits, reset."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, mls_device!: 1]
  import RisiMe.GroupHelpers

  alias RisiMe.{Groups, Repo}
  alias RisiMe.Groups.{Op, Ops}
  alias RisiMe.Workers.GroupTimer
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  setup do
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    a_dev = groups_device!(a)
    b_dev = groups_device!(b)
    c_dev = groups_device!(c)
    %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev}
  end

  # A created, active group of a (admin), b and c. Returns the group id.
  defp active_group!(%{a: a, b: b, c: c, a_dev: a_dev}) do
    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, c.user.id]}

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    members = [a.user.id, b.user.id, c.user.id]

    api(
      :post,
      "/api/v1/mls/groups/#{id}/commit",
      a.token,
      create_commit(members, {a.user.id, a_dev}),
      a_dev
    )
    |> assert_status(200)

    id
  end

  defp group(token, id),
    do: api(:get, "/api/v1/groups/#{id}", token) |> assert_status(200) |> Map.fetch!("group")

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    chan
  end

  describe "create (§12.3)" do
    test "creating → epoch-0 commit → active with created events", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev} = ctx
      cgid = Ecto.UUID.generate()
      body = %{"client_group_id" => cgid, "member_ids" => [b.user.id, c.user.id]}

      # X-Device-Id must name a groups-capable device.
      assert {403, %{"error" => %{"code" => "invalid_device"}}} =
               api(:post, "/api/v1/groups", a.token, body)

      plain = mls_device!(a)

      assert {403, %{"error" => %{"code" => "invalid_device"}}} =
               api(:post, "/api/v1/groups", a.token, body, plain)

      # The plain MLS device just made a not groups-ready... for others, not the creator.
      g =
        api(:post, "/api/v1/groups", a.token, body, a_dev)
        |> assert_status(201)
        |> Map.fetch!("group")

      assert g["state"] == "creating" and g["epoch"] == nil and g["my_role"] == "admin"
      assert "grp:" <> _ = id = g["id"]
      assert Groups.group_id?(id)

      states = Map.new(g["members"], &{&1["user_id"], &1["state"]})

      assert states == %{
               a.user.id => "active",
               b.user.id => "pending_add",
               c.user.id => "pending_add"
             }

      # Idempotent by client_group_id; invisible to the others while creating.
      assert {200, %{"group" => %{"id" => ^id}}} =
               api(:post, "/api/v1/groups", a.token, body, a_dev)

      assert {404, _} = api(:get, "/api/v1/groups/#{id}", b.token)
      assert {200, %{"groups" => []}} = api(:get, "/api/v1/groups", b.token)

      # Co-member claim (§12.5): groups devices only, pending_add members allowed.
      {:ok, devices} = RisiMe.MLS.claim(a.user.id, [b.user.id, c.user.id], a_dev, id)
      assert Enum.all?(devices, & &1.mls) and length(devices) == 2

      assert {:error, :not_member} = RisiMe.MLS.claim(b.user.id, [a.user.id], b_dev, id)

      # The epoch-0 commit must add exactly every groups device of every member.
      members = [a.user.id, b.user.id, c.user.id]
      good = create_commit(members, {a.user.id, a_dev})

      assert {400, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 a.token,
                 %{good | "added" => tl(good["added"])},
                 a_dev
               )

      assert {200, %{"epoch" => 1}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, good, a_dev)

      g = group(b.token, id)
      assert g["state"] == "active" and g["epoch"] == 1 and g["my_role"] == "member"
      assert Enum.all?(g["members"], &(&1["state"] == "active" and &1["joined_at"]))

      # b: mls_commit, then mls_welcome, then group_event created (with members).
      kinds = b.user.id |> events() |> Enum.map(& &1["kind"])
      assert kinds == ~w(mls_commit mls_welcome group_event)
      created = last_event(b.user.id, "group_event")["data"]
      assert created["action"] == "created" and created["epoch"] == 1
      assert Enum.sort(created["targets"]) == Enum.sort([b.user.id, c.user.id])
      # b sees a's phone (friends) but not c's.
      phones = Map.new(created["members"], &{&1["user_id"], &1["phone"]})
      assert phones[a.user.id] == a.user.phone and phones[c.user.id] == nil

      # The creating timer is then a no-op.
      assert :ok = perform(%{"kind" => "creating", "group_id" => id})
      assert Groups.get_group(id).state == "active"
    end

    test "errors: not_friends, not_ready (legacy_app), too_many_members; stale creating groups go",
         %{a: a, b: b, c: c, a_dev: a_dev} do
      stranger = logged_in_user()
      groups_device!(stranger)
      cg = fn ids -> %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => ids} end

      assert {403, %{"error" => %{"code" => "not_friends"}}} =
               api(:post, "/api/v1/groups", a.token, cg.([b.user.id, stranger.user.id]), a_dev)

      # c also runs an old app on a second registered install (no groups capability).
      old = Ecto.UUID.generate()

      {:ok, nil} =
        RisiMe.Devices.register(c.user.id, old, %{
          "platform" => "android",
          "push_token" => "t-#{old}"
        })

      :ok = RisiMe.MLS.record_instance(c.user.id, old, nil, "0.2.0")

      {409, err} = api(:post, "/api/v1/groups", a.token, cg.([b.user.id, c.user.id]), a_dev)
      assert err["error"]["code"] == "not_ready"
      assert err["error"]["message"] == "Chamari needs to update RisiMe"

      assert [%{"user_id" => cid, "device_id" => ^old, "reason" => "legacy_app"}] =
               err["error"]["missing"]

      assert cid == c.user.id

      too_many = for _ <- 1..256, do: Ecto.UUID.generate()

      assert {422, %{"error" => %{"code" => "too_many_members"}}} =
               api(:post, "/api/v1/groups", a.token, cg.(too_many), a_dev)

      {201, %{"group" => %{"id" => id}}} =
        api(:post, "/api/v1/groups", a.token, cg.([b.user.id]), a_dev)

      assert :ok = perform(%{"kind" => "creating", "group_id" => id})
      assert {404, _} = api(:get, "/api/v1/groups/#{id}", a.token)
    end

    test "friends carry group_ready", %{a: a, b: b, c: c} do
      # An old instance without device_id, last seen before c's device registered ⇒ ready.
      import Ecto.Query, only: [from: 2]
      :ok = RisiMe.MLS.record_instance(c.user.id, nil, "jwt", nil)

      Repo.update_all(
        from(i in "app_instances",
          where: i.user_id == type(^c.user.id, :binary_id) and i.instance_key == "legacy:jwt"
        ),
        set: [last_seen_at: DateTime.add(DateTime.utc_now(), -3600, :second)]
      )

      {200, %{"friends" => friends}} = api(:get, "/api/v1/friends", a.token)
      ready = Map.new(friends, &{&1["user_id"], &1["group_ready"]})
      assert ready == %{b.user.id => true, c.user.id => true}

      # A registered install on an old build keeps c not-ready.
      old = Ecto.UUID.generate()

      {:ok, nil} =
        RisiMe.Devices.register(c.user.id, old, %{
          "platform" => "android",
          "push_token" => "t-#{old}"
        })

      :ok = RisiMe.MLS.record_instance(c.user.id, old, nil, "0.2.0")
      {200, %{"friends" => friends}} = api(:get, "/api/v1/friends", a.token)
      ready = Map.new(friends, &{&1["user_id"], &1["group_ready"]})
      assert ready == %{b.user.id => true, c.user.id => false}
    end
  end

  describe "members, ops and commits (§12.3, §12.4)" do
    test "add: admin op, committer, non-admin refused, completion emits added", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      d = logged_in_user(display_name: "Dilan")
      befriend!(a, d)
      d_dev = groups_device!(d)

      # Only admins add.
      assert {403, %{"error" => %{"code" => "not_admin"}}} =
               api(
                 :post,
                 "/api/v1/groups/#{id}/members",
                 b.token,
                 %{"user_ids" => [d.user.id]},
                 b_dev
               )

      g =
        api(:post, "/api/v1/groups/#{id}/members", a.token, %{"user_ids" => [d.user.id]}, a_dev)
        |> assert_status(200)
        |> Map.fetch!("group")

      [op] = g["pending"]
      assert op["type"] == "add" and op["user_ids"] == [d.user.id]
      assert op["committer"] == %{"user_id" => a.user.id, "device_id" => a_dev}
      assert op["expires_at"] && op["committer_until"]
      assert Enum.find(g["members"], &(&1["user_id"] == d.user.id))["state"] == "pending_add"
      assert last_event(a.user.id, "group_op")["data"]["op"]["op_id"] == op["op_id"]

      # Repeat: already pending → ignored, still one op.
      {200, %{"group" => %{"pending" => [_]}}} =
        api(:post, "/api/v1/groups/#{id}/members", a.token, %{"user_ids" => [d.user.id]}, a_dev)

      body = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [ref(d.user.id, d_dev)],
        "removed" => [],
        "op_id" => op["op_id"],
        "meta_changed" => false
      }

      # A non-admin can't complete it; wrong lists are bad_request.
      assert {403, _} = api(:post, "/api/v1/mls/groups/#{id}/commit", b.token, body, b_dev)

      assert {400, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 a.token,
                 %{body | "added" => [], "welcome" => nil},
                 a_dev
               )

      assert {200, %{"epoch" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, body, a_dev)

      g = group(d.token, id)
      assert g["pending"] == [] and g["epoch"] == 2

      assert ~w(mls_welcome group_event) == d.user.id |> events() |> Enum.map(& &1["kind"])
      added = last_event(b.user.id, "group_event")["data"]

      assert added["action"] == "added" and added["targets"] == [d.user.id] and
               added["epoch"] == 2

      assert last_event(b.user.id, "mls_commit")["data"]["epoch"] == 1
    end

    test "add expiry drops pending members with add_expired; role ops expire silently", ctx do
      %{a: a, b: b, a_dev: a_dev} = ctx
      id = active_group!(ctx)
      d = logged_in_user()
      befriend!(a, d)
      groups_device!(d)

      {200, %{"group" => %{"pending" => [%{"op_id" => op_id}]}}} =
        api(:post, "/api/v1/groups/#{id}/members", a.token, %{"user_ids" => [d.user.id]}, a_dev)

      assert_enqueued_job("expire", op_id)
      assert :ok = perform(%{"kind" => "expire", "op_id" => op_id})
      assert Groups.member(id, d.user.id) == nil
      ev = last_event(b.user.id, "group_event")["data"]
      assert ev["action"] == "add_expired" and ev["targets"] == [d.user.id] and ev["epoch"] == 1

      {200, %{"group" => %{"pending" => [%{"op_id" => role_op}]}}} =
        api(
          :patch,
          "/api/v1/groups/#{id}/members/#{b.user.id}",
          a.token,
          %{"role" => "admin"},
          a_dev
        )

      assert :ok = perform(%{"kind" => "expire", "op_id" => role_op})
      assert group(a.token, id)["pending"] == []
      assert last_event(b.user.id, "group_event")["data"]["action"] == "add_expired"
    end

    test "leave: last_admin, admin-committed removal, left event, not_member at once", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev} = ctx
      id = active_group!(ctx)

      assert {409, %{"error" => %{"code" => "last_admin"}}} =
               api(:post, "/api/v1/groups/#{id}/leave", a.token, nil, a_dev)

      # No admin device online: the op waits with no committer.
      assert {204, nil} = api(:post, "/api/v1/groups/#{id}/leave", b.token, nil, b_dev)
      assert {204, nil} = api(:post, "/api/v1/groups/#{id}/leave", b.token, nil, b_dev)
      [op] = Ops.list(id)
      assert op.type == "remove" and op.committer_device == nil
      assert {404, _} = api(:get, "/api/v1/groups/#{id}", b.token)

      # b's sends and typing are refused right away.
      assert {:error, :not_member} =
               RisiMe.Messaging.send(
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

      # c (not an admin) joining doesn't get named; the admin's device does.
      join!(c, c_dev)
      assert Repo.get(Op, op.op_id).committer_device == nil
      join!(a, a_dev)
      op = Repo.get(Op, op.op_id)
      assert op.committer_device == a_dev
      assert last_event(a.user.id, "group_op")["data"]["op"]["type"] == "remove"

      body = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => b64(),
        "welcome" => nil,
        "added" => [],
        "removed" => [ref(b.user.id, b_dev)],
        "op_id" => op.op_id,
        "meta_changed" => false
      }

      # b's own device can't commit its removal (MLS can't, and b isn't in the group any more).
      assert {404, _} = api(:post, "/api/v1/mls/groups/#{id}/commit", b.token, body, b_dev)

      assert {200, %{"epoch" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, body, a_dev)

      # b gets the removal commit, then `left`, as its last events of the group.
      assert [%{"kind" => "mls_commit"}, %{"kind" => "group_event", "data" => left}] =
               b.user.id |> events() |> Enum.take(-2)

      assert left["action"] == "left" and left["actor"] == b.user.id and
               left["targets"] == [b.user.id]

      assert Groups.member(id, b.user.id) == nil
      assert Ops.list(id) == []
    end

    test "remove by admin; admins only removable by the creator; role changes", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev} = ctx
      id = active_group!(ctx)

      # Promote b (role op, completed by a's group_meta commit).
      {200, %{"group" => %{"pending" => [op]}}} =
        api(
          :patch,
          "/api/v1/groups/#{id}/members/#{b.user.id}",
          a.token,
          %{"role" => "admin"},
          a_dev
        )

      assert {200, _} =
               api(
                 :patch,
                 "/api/v1/groups/#{id}/members/#{b.user.id}",
                 a.token,
                 %{"role" => "admin"},
                 a_dev
               )

      assert {422, %{"error" => %{"code" => "invalid_role"}}} =
               api(
                 :patch,
                 "/api/v1/groups/#{id}/members/#{b.user.id}",
                 a.token,
                 %{"role" => "owner"},
                 a_dev
               )

      role_commit = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => b64(),
        "added" => [],
        "removed" => [],
        "op_id" => op["op_id"],
        "meta_changed" => true
      }

      assert {200, %{"epoch" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, role_commit, a_dev)

      ev = last_event(c.user.id, "group_event")["data"]

      assert ev["action"] == "role_changed" and ev["role"] == "admin" and
               ev["targets"] == [b.user.id]

      assert group(b.token, id)["my_role"] == "admin"

      # b (not the creator) can't remove or demote admin a.
      assert {403, %{"error" => %{"code" => "not_admin"}}} =
               api(:delete, "/api/v1/groups/#{id}/members/#{a.user.id}", b.token, nil, b_dev)

      assert {403, _} =
               api(
                 :patch,
                 "/api/v1/groups/#{id}/members/#{a.user.id}",
                 b.token,
                 %{"role" => "member"},
                 b_dev
               )

      # A member renaming is refused; an admin renaming emits metadata_changed.
      rename = %{role_commit | "epoch" => 2, "op_id" => nil}
      assert {403, _} = api(:post, "/api/v1/mls/groups/#{id}/commit", c.token, rename, c_dev)

      assert {200, %{"epoch" => 3}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", b.token, rename, b_dev)

      assert last_event(c.user.id, "group_event")["data"]["action"] == "metadata_changed"

      # Self-update by anyone.
      self_update = %{rename | "epoch" => 3, "meta_changed" => false}

      assert {200, %{"epoch" => 4}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", c.token, self_update, c_dev)

      # b removes c: pending_remove at once, then the commit.
      assert {204, nil} =
               api(:delete, "/api/v1/groups/#{id}/members/#{c.user.id}", b.token, nil, b_dev)

      assert {204, nil} =
               api(:delete, "/api/v1/groups/#{id}/members/#{c.user.id}", b.token, nil, b_dev)

      [op] = Ops.list(id)
      assert op.committer_device == b_dev

      remove = %{
        self_update
        | "epoch" => 4,
          "removed" => [ref(c.user.id, c_dev)],
          "op_id" => op.op_id
      }

      # a completes it (any admin device may).
      assert {200, %{"epoch" => 5}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, remove, a_dev)

      ev = last_event(c.user.id, "group_event")["data"]
      assert ev["action"] == "removed" and ev["actor"] == b.user.id
      assert {404, _} = api(:get, "/api/v1/groups/#{id}", c.token)

      assert {409, %{"error" => %{"code" => "epoch_conflict", "epoch" => 5}}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, remove, a_dev)
    end

    test "committer timeout names the next online admin device; stale timers are no-ops", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev} = ctx
      id = active_group!(ctx)

      # Second admin b with an online device.
      {200, %{"group" => %{"pending" => [op]}}} =
        api(
          :patch,
          "/api/v1/groups/#{id}/members/#{b.user.id}",
          a.token,
          %{"role" => "admin"},
          a_dev
        )

      {200, _} =
        api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          %{
            "generation" => 1,
            "epoch" => 1,
            "commit" => b64(),
            "op_id" => op["op_id"],
            "meta_changed" => true
          },
          a_dev
        )

      join!(b, b_dev)
      {204, nil} = api(:delete, "/api/v1/groups/#{id}/members/#{c.user.id}", a.token, nil, a_dev)
      [op] = Ops.list(id)
      assert op.committer_device == a_dev and op.naming == 1

      assert :ok = perform(%{"kind" => "committer", "op_id" => op.op_id, "naming" => 0})
      assert Repo.get(Op, op.op_id).committer_device == a_dev

      assert :ok = perform(%{"kind" => "committer", "op_id" => op.op_id, "naming" => 1})
      op = Repo.get(Op, op.op_id)
      assert op.committer_device == b_dev and op.naming == 2
      assert last_event(b.user.id, "group_op")["data"]["op"]["committer"]["device_id"] == b_dev

      # Only b online: the rotation comes back to b.
      assert :ok = perform(%{"kind" => "committer", "op_id" => op.op_id, "naming" => 2})
      assert Repo.get(Op, op.op_id).committer_device == b_dev
    end

    test "devices ops: a new device of a member, completed by the member's own device", ctx do
      %{b: b, b_dev: b_dev} = ctx
      id = active_group!(ctx)

      b2 = groups_device!(b)
      [op] = Ops.list(id)
      assert op.type == "devices" and Ops.refs(op, "added") == [{b.user.id, b2}]

      body = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [ref(b.user.id, b2)],
        "removed" => [],
        "op_id" => nil,
        "meta_changed" => false
      }

      assert {200, %{"epoch" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", b.token, body, b_dev)

      assert Ops.list(id) == []
      # Device-only changes emit no group_event (only `created` so far).
      assert length(events(b.user.id, "group_event")) == 1

      # Removing the device makes a removal op; b2's rejoin makes a remove + re-add op.
      {202, %{"group" => %{"pending" => [rejoin]}}} =
        api(:post, "/api/v1/groups/#{id}/rejoin", b.token, nil, b2)

      assert rejoin["type"] == "devices" and rejoin["added"] == rejoin["removed"]
      assert rejoin["committer"]["device_id"] in [nil, b_dev]
    end

    test "logout + login on the same device id: the stale leaf is removed and re-added", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      bid = b.user.id

      # Logout: the device goes; a removal op waits (no admin device online).
      :ok = RisiMe.Devices.delete(bid, b_dev)
      [removal] = Ops.list(id)
      assert Ops.refs(removal, "removed") == [{bid, b_dev}] and Ops.refs(removal, "added") == []

      # Login with a new MLS state before the removal landed: one rejoin op, no removal-only op.
      ^b_dev = groups_device!(b, b_dev)
      [op] = Ops.list(id)
      assert Ops.refs(op, "added") == [{bid, b_dev}] and Ops.refs(op, "removed") == [{bid, b_dev}]

      # The app's rejoin is idempotent; the rejoining device itself is never named.
      join!(b, b_dev)
      join!(a, a_dev)

      {202, %{"group" => %{"pending" => [p]}}} =
        api(:post, "/api/v1/groups/#{id}/rejoin", b.token, nil, b_dev)

      assert p["op_id"] == op.op_id
      Ops.name_next(Groups.get_group(id), Repo.get(Op, op.op_id))
      assert Repo.get(Op, op.op_id).committer_device == a_dev

      body = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [ref(bid, b_dev)],
        "removed" => [ref(bid, b_dev)],
        "op_id" => op.op_id,
        "meta_changed" => false
      }

      assert {200, %{"epoch" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, body, a_dev)

      assert Ops.list(id) == []
      # The re-added device is in the leaf set (removal applied before the add).
      assert MapSet.member?(Groups.in_group(id), {bid, b_dev})
      assert last_event(bid, "mls_welcome")["data"]["to_devices"] == [b_dev]
    end

    test "a rejoin whose old leaf is already gone completes without the removal", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      bid = b.user.id

      {202, %{"group" => %{"pending" => [p]}}} =
        api(:post, "/api/v1/groups/#{id}/rejoin", b.token, nil, b_dev)

      # The old leaf goes by another commit first (e.g. an earlier removal).
      import Ecto.Query

      Repo.delete_all(
        from gd in "mls_group_devices",
          where: gd.conversation_id == ^id and gd.device_id == type(^b_dev, :binary_id)
      )

      body = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [ref(bid, b_dev)],
        "removed" => [],
        "op_id" => p["op_id"],
        "meta_changed" => false
      }

      assert {200, %{"epoch" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, body, a_dev)

      assert Ops.list(id) == []
      assert MapSet.member?(Groups.in_group(id), {bid, b_dev})
    end

    test "commit catch-up paging and 410 log_expired", ctx do
      %{a: a, a_dev: a_dev} = ctx
      id = active_group!(ctx)

      for e <- 1..3 do
        body = %{"generation" => 1, "epoch" => e, "commit" => b64(), "meta_changed" => false}
        {200, _} = api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, body, a_dev)
      end

      {200, page} = api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=0&limit=2", a.token)
      assert page["has_more"] and Enum.map(page["commits"], & &1["epoch"]) == [0, 1]
      assert Enum.all?(page["commits"], &(Map.has_key?(&1, "commit_ref") and &1["commit"]))

      {200, page} = api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=2", a.token)
      assert page["has_more"] == false and Enum.map(page["commits"], & &1["epoch"]) == [2, 3]

      import Ecto.Query
      Repo.delete_all(from c in "mls_commits", where: c.conversation_id == ^id and c.epoch < 2)

      assert {410, %{"error" => %{"code" => "log_expired"}}} =
               api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=1", a.token)

      assert {200, _} = api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=2", a.token)
    end

    test "reset: conflicts, admin only, rate limit; rebuild commit makes epoch 1", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      d = logged_in_user()
      befriend!(a, d)
      groups_device!(d)

      {200, _} =
        api(:post, "/api/v1/groups/#{id}/members", a.token, %{"user_ids" => [d.user.id]}, a_dev)

      path = "/api/v1/mls/groups/#{id}/reset"

      assert {403, %{"error" => %{"code" => "not_admin"}}} =
               api(:post, path, b.token, %{"generation" => 1}, b_dev)

      assert {409, %{"error" => %{"code" => "generation_conflict", "generation" => 1}}} =
               api(:post, path, a.token, %{"generation" => 7}, a_dev)

      assert {200, %{"generation" => 2}} = api(:post, path, a.token, %{"generation" => 1}, a_dev)
      assert {409, _} = api(:post, path, a.token, %{"generation" => 1}, a_dev)
      assert {429, _} = api(:post, path, a.token, %{"generation" => 2}, a_dev)

      g = group(a.token, id)
      assert g["generation"] == 2 and g["epoch"] == nil
      assert [%{"type" => "rebuild", "committer" => %{"device_id" => ^a_dev}} = op] = g["pending"]
      # The pending add of d was dropped.
      refute Enum.any?(g["members"], &(&1["user_id"] == d.user.id))

      ev = last_event(c.user.id, "group_event")["data"]
      assert ev["action"] == "reset" and ev["generation"] == 2 and ev["epoch"] == nil
      assert ev["rebuilder"] == %{"user_id" => a.user.id, "device_id" => a_dev}
      assert length(ev["members"]) == 3

      # Sends at the old generation are stale.
      body =
        create_commit([a.user.id, b.user.id, c.user.id], {a.user.id, a_dev}, %{"generation" => 2})

      assert {400, _} = api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, body, a_dev)

      assert {200, %{"epoch" => 1}} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 a.token,
                 %{body | "op_id" => op["op_id"]},
                 a_dev
               )

      assert group(b.token, id)["epoch"] == 1 and group(b.token, id)["pending"] == []
      assert last_event(b.user.id, "mls_welcome")["data"]["generation"] == 2
    end
  end

  defp perform(args), do: GroupTimer.perform(%Oban.Job{args: args})

  defp assert_enqueued_job(kind, op_id) do
    import Ecto.Query

    assert Repo.exists?(
             from j in Oban.Job,
               where:
                 j.worker == "RisiMe.Workers.GroupTimer" and
                   fragment("?->>'kind' = ?", j.args, ^kind) and
                   fragment("?->>'op_id' = ?", j.args, ^op_id)
           )
  end
end
