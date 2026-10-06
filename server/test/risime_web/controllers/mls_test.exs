defmodule RisiMeWeb.MLSTest do
  @moduledoc "Contract v1.7 §10: attestation, key packages, claim, readiness, commits. Opaque fake blobs."
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.{MLS, Repo}
  alias RisiMe.Devices.Device

  defp authed(conn, token, device \\ nil) do
    conn = put_req_header(conn, "authorization", "Bearer " <> token)
    if device, do: put_req_header(conn, "x-device-id", device), else: conn
  end

  defp call(conn, token, method, path, body \\ nil, device \\ nil),
    do: conn |> authed(token, device) |> dispatch(@endpoint, method, path, body)

  defp events(user_id, kind) do
    {:ok, events, _} = RisiMe.Messaging.fetch_events(user_id, nil)
    Enum.filter(events, &(&1.kind == kind))
  end

  describe "E2EE off (no attestation key): the pilot's state" do
    test "every MLS endpoint is 503 mls_unavailable; groups report ready: false", %{conn: conn} do
      a = logged_in_user()
      b = logged_in_user()
      befriend!(a, b)
      id = Ecto.UUID.generate()

      body = %{"platform" => "android", "mls" => %{"signature_key" => b64()}}

      assert %{"error" => %{"code" => "mls_unavailable"}} =
               call(conn, a.token, :put, "/api/v1/me/devices/#{id}", body) |> json_response(503)

      assert get(conn, "/api/v1/mls/attestation_keys") |> json_response(503)

      assert call(conn, a.token, :post, "/api/v1/mls/key_packages/claim", %{
               "user_ids" => [b.user.id]
             })
             |> json_response(503)

      conv = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)

      assert %{"ready" => false, "e2ee" => false, "generation" => 1, "epoch" => nil} =
               call(conn, a.token, :get, "/api/v1/mls/groups/#{conv}") |> json_response(200)

      assert call(
               conn,
               a.token,
               :post,
               "/api/v1/mls/groups/#{conv}/commit",
               %{"generation" => 1, "epoch" => 0, "commit" => b64()},
               id
             )
             |> json_response(503)

      # Push-only registration still works.
      assert call(conn, a.token, :put, "/api/v1/me/devices/#{id}", %{
               "platform" => "android",
               "push_token" => "t"
             }).status == 204
    end
  end

  describe "attestation" do
    setup :with_attestation_key

    test "PUT with mls returns a verifiable EdDSA attestation", %{
      conn: conn,
      attestation_kid: kid
    } do
      a = logged_in_user()
      id = Ecto.UUID.generate()
      key = b64()

      %{"attestation" => jws} =
        call(conn, a.token, :put, "/api/v1/me/devices/#{id}", %{
          "platform" => "android",
          "push_token" => nil,
          "mls" => %{"signature_key" => key}
        })
        |> json_response(200)

      %{"keys" => [jwk]} = get(conn, "/api/v1/mls/attestation_keys") |> json_response(200)
      assert jwk["kid"] == kid and jwk["alg"] == "EdDSA" and not Map.has_key?(jwk, "d")
      assert kid == JOSE.JWK.thumbprint(JOSE.JWK.from_map(jwk))

      {true, %JOSE.JWT{fields: claims}, %JOSE.JWS{fields: header}} =
        JOSE.JWT.verify_strict(JOSE.JWK.from_map(jwk), ["EdDSA"], jws)

      assert header["typ"] == "risime-attest+jwt" and header["kid"] == kid

      assert %{
               "aud" => "risime-mls",
               "user_id" => uid,
               "device_id" => ^id,
               "signature_key" => ^key,
               "v" => 1
             } = claims

      assert uid == a.user.id
    end

    test "422 for a bad key; at least one of push_token / mls", %{conn: conn} do
      a = logged_in_user()
      id = Ecto.UUID.generate()

      for body <- [
            %{"platform" => "android", "mls" => %{"signature_key" => b64(31)}},
            %{"platform" => "android", "mls" => %{"signature_key" => "!!"}},
            %{"platform" => "android"}
          ] do
        assert call(conn, a.token, :put, "/api/v1/me/devices/#{id}", body) |> json_response(422)
      end
    end

    test "previous keys are published during rotation", %{conn: conn} do
      old = JOSE.JWK.generate_key({:okp, :Ed25519})
      {_, pub} = old |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()

      path =
        Path.join(System.tmp_dir!(), "risime-prev-#{System.unique_integer([:positive])}.json")

      File.write!(path, Jason.encode!(%{"keys" => [Map.put(pub, "d", "must-be-dropped")]}))
      Application.put_env(:risime, :attestation_previous_keys, path)
      RisiMe.MLS.Attestation.reload()

      on_exit(fn ->
        File.rm(path)
        Application.delete_env(:risime, :attestation_previous_keys)
        RisiMe.MLS.Attestation.reload()
      end)

      %{"keys" => [_active, prev]} =
        get(conn, "/api/v1/mls/attestation_keys") |> json_response(200)

      refute Map.has_key?(prev, "d")
      assert prev["kid"] == JOSE.JWK.thumbprint(old)
    end
  end

  describe "key packages and claim" do
    setup :with_attestation_key

    setup do
      a = logged_in_user()
      b = logged_in_user()
      befriend!(a, b)
      %{a: a, b: b, a_dev: mls_device!(a), b_dev: mls_device!(b)}
    end

    defp upload(conn, who, dev, n, last_resort \\ nil),
      do:
        call(conn, who.token, :post, "/api/v1/me/devices/#{dev}/key_packages", %{
          "key_packages" => for(_ <- 1..n//1, do: b64(64)),
          "last_resort" => last_resort
        })

    defp count(conn, who, dev),
      do:
        call(conn, who.token, :get, "/api/v1/me/devices/#{dev}/key_packages/count")
        |> json_response(200)
        |> Map.fetch!("count")

    test "upload, count, the 100 cap, one last resort", %{conn: conn, a: a, a_dev: dev} do
      assert upload(conn, a, dev, 60, b64()).status == 204
      assert upload(conn, a, dev, 60, b64()).status == 204
      assert count(conn, a, dev) == 100
      assert Repo.aggregate(from(k in "mls_key_packages", where: k.last_resort), :count) >= 1
      dev_ref = Repo.get_by!(Device, device_id: dev).id

      assert Repo.aggregate(
               from(k in "mls_key_packages",
                 where: k.device_ref == type(^dev_ref, :binary_id) and k.last_resort
               ),
               :count
             ) == 1

      assert upload(conn, a, dev, 101).status == 400

      assert call(conn, a.token, :post, "/api/v1/me/devices/#{dev}/key_packages", %{
               "key_packages" => ["!!"]
             }).status == 400

      assert call(conn, a.token, :post, "/api/v1/me/devices/#{dev}/key_packages", %{
               "key_packages" => [b64(5000)]
             }).status == 400

      assert upload(conn, a, Ecto.UUID.generate(), 1) |> json_response(422)
    end

    test "claim: friends and self, atomic consumption, last resort never consumed, low signal",
         %{conn: conn, a: a, b: b, a_dev: a_dev, b_dev: b_dev} do
      upload(conn, b, b_dev, 2, b64())
      a2 = mls_device!(a)
      Phoenix.PubSub.subscribe(RisiMe.PubSub, RisiMe.Messaging.topic(b.user.id))

      claim = fn ->
        call(
          conn,
          a.token,
          :post,
          "/api/v1/mls/key_packages/claim",
          %{"user_ids" => [b.user.id, a.user.id]},
          a_dev
        )
        |> json_response(200)
        |> Map.fetch!("devices")
      end

      d1 = claim.()

      assert [%{"device_id" => ^b_dev, "mls" => true, "attestation" => att, "key_package" => kp1}] =
               Enum.filter(d1, &(&1["user_id"] == b.user.id))

      assert is_binary(att) and is_binary(kp1)
      # My own other device, not the calling one.
      assert [%{"device_id" => ^a2}] = Enum.filter(d1, &(&1["user_id"] == a.user.id))
      assert_receive {:signal, %{kind: "mls_key_packages_low", data: %{"count" => 1}}}

      d2 = claim.()
      d3 = claim.()
      kps = for d <- [d2, d3], e <- d, e["device_id"] == b_dev, do: e["key_package"]
      [kp2, lr] = kps
      assert kp2 != kp1
      # Out of normal packages: the last resort, again and again.
      assert lr == hd(for e <- claim.(), e["device_id"] == b_dev, do: e["key_package"])
      assert count(conn, b, b_dev) == 0
    end

    test "claim is all-or-nothing: a non-friend id consumes nothing", %{
      conn: conn,
      a: a,
      b: b,
      b_dev: b_dev
    } do
      upload(conn, b, b_dev, 3)
      %{user: stranger} = logged_in_user()

      assert %{"error" => %{"code" => "not_friends"}} =
               call(conn, a.token, :post, "/api/v1/mls/key_packages/claim", %{
                 "user_ids" => [b.user.id, stranger.id]
               })
               |> json_response(403)

      assert count(conn, b, b_dev) == 3
    end

    test "a device without packages gets key_package null; non-MLS instances are listed", %{
      conn: conn,
      a: a,
      b: b
    } do
      :ok = MLS.record_instance(b.user.id, nil, "token:legacy", nil)

      devices =
        call(conn, a.token, :post, "/api/v1/mls/key_packages/claim", %{"user_ids" => [b.user.id]})
        |> json_response(200)
        |> Map.fetch!("devices")

      assert %{"mls" => true, "key_package" => nil} = Enum.find(devices, & &1["mls"])

      assert %{"mls" => false, "device_id" => nil, "attestation" => nil} =
               Enum.find(devices, &(!&1["mls"]))
    end
  end

  describe "readiness and the census" do
    setup :with_attestation_key

    test "legacy apps and devices without MLS block readiness", %{conn: conn} do
      a = logged_in_user()
      b = logged_in_user()
      befriend!(a, b)
      conv = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)

      view = fn ->
        call(conn, a.token, :get, "/api/v1/mls/groups/#{conv}") |> json_response(200)
      end

      mls_device!(a)

      assert %{
               "ready" => false,
               "missing" => [%{"user_id" => bid, "device_id" => nil, "reason" => "no_mls"}]
             } = view.()

      assert bid == b.user.id

      b_dev = mls_device!(b)
      assert %{"ready" => true, "missing" => []} = view.()

      # A pre-v1.7 app of b seen recently, then a v1.7 install that has no MLS identity.
      :ok = MLS.record_instance(b.user.id, nil, "jwt", nil)
      assert %{"ready" => false, "missing" => [%{"reason" => "legacy_app"}]} = view.()
      Repo.delete_all(from i in "app_instances", where: i.instance_key == "legacy:jwt")
      # A registered install (push only) that hasn't set up MLS yet.
      other = Ecto.UUID.generate()

      {:ok, nil} =
        RisiMe.Devices.register(b.user.id, other, %{"platform" => "android", "push_token" => "t"})

      :ok = MLS.record_instance(b.user.id, other, nil, "0.3.0")
      assert %{"missing" => [%{"device_id" => ^other, "reason" => "no_mls"}]} = view.()
      _ = b_dev

      # Only members may look.
      %{token: outsider} = logged_in_user()
      assert call(conn, outsider, :get, "/api/v1/mls/groups/#{conv}") |> json_response(404)
    end
  end

  describe "1:1 readiness uses the §12.1 'can still receive' rule (P0-1)" do
    setup :with_attestation_key

    defp age_instance!(user_id, key, seconds) do
      Repo.update_all(
        from(i in "app_instances",
          where: i.user_id == type(^user_id, :binary_id) and i.instance_key == ^key
        ),
        set: [last_seen_at: DateTime.add(DateTime.utc_now(), -seconds, :second)]
      )
    end

    test "superseded pre-v1.7 rows and dead (reinstalled) device ids don't block; claim agrees",
         %{conn: conn} do
      a = logged_in_user()
      b = logged_in_user()
      befriend!(a, b)
      conv = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)

      # The live pilot shape: each user has an old pre-v1.7 row from before the update, and
      # census rows of device ids from earlier installs that are no longer registered.
      for u <- [a, b] do
        :ok = MLS.record_instance(u.user.id, nil, "jwt", nil)
        age_instance!(u.user.id, "legacy:jwt", 3600)
        dead = Ecto.UUID.generate()
        :ok = MLS.record_instance(u.user.id, dead, nil, "0.2.0-nightly.7")
        age_instance!(u.user.id, "device:" <> dead, 1800)
      end

      a_dev = mls_device!(a)
      b_dev = mls_device!(b)
      :ok = MLS.record_instance(a.user.id, a_dev, nil, "0.2.0-nightly.13")
      :ok = MLS.record_instance(b.user.id, b_dev, nil, "0.2.0-nightly.13")

      assert {true, []} = MLS.readiness([a.user.id, b.user.id])

      assert %{"ready" => true, "missing" => []} =
               call(conn, a.token, :get, "/api/v1/mls/groups/#{conv}") |> json_response(200)

      devices =
        call(conn, a.token, :post, "/api/v1/mls/key_packages/claim", %{"user_ids" => [b.user.id]})
        |> json_response(200)
        |> Map.fetch!("devices")

      assert Enum.all?(devices, & &1["mls"])
      assert [b_dev] == Enum.map(devices, & &1["device_id"])
    end

    test "an old app still in use after the registration blocks as legacy_app" do
      a = logged_in_user().user
      _ = mls_device!(a)

      Repo.update_all(from(d in Device, where: d.user_id == ^a.id),
        set: [last_seen_at: DateTime.add(DateTime.utc_now(), -60, :second)]
      )

      :ok = MLS.record_instance(a.id, nil, "jwt", nil)
      assert {false, [%{device_id: nil, reason: "legacy_app"}]} = MLS.readiness([a.id])
    end

    test "a member without any MLS device is no_mls with device_id null" do
      a = logged_in_user().user
      assert {false, [%{device_id: nil, reason: "no_mls"}]} = MLS.readiness([a.id])
    end
  end

  describe "commits" do
    setup :with_attestation_key

    setup do
      a = logged_in_user()
      b = logged_in_user()
      befriend!(a, b)
      a_dev = mls_device!(a)
      b_dev = mls_device!(b)
      conv = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, conv: conv}
    end

    defp commit(conn, who, dev, conv, body),
      do: call(conn, who.token, :post, "/api/v1/mls/groups/#{conv}/commit", body, dev)

    defp create(conn, ctx) do
      commit(conn, ctx.a, ctx.a_dev, ctx.conv, %{
        "generation" => 1,
        "epoch" => 0,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [%{"user_id" => ctx.b.user.id, "device_id" => ctx.b_dev}],
        "removed" => []
      })
    end

    test "epoch 0 creates the group; events fan out per user; the log serves recovery",
         %{conn: conn} = ctx do
      assert %{"epoch" => 1} = create(conn, ctx) |> json_response(200)

      assert %{"e2ee" => true, "epoch" => 1, "devices" => devices} =
               call(conn, ctx.a.token, :get, "/api/v1/mls/groups/#{ctx.conv}")
               |> json_response(200)

      assert length(devices) == 2

      for u <- [ctx.a.user.id, ctx.b.user.id] do
        assert [%{data: %{"epoch" => 0, "from_device" => from}}] = events(u, "mls_commit")
        assert from == ctx.a_dev
      end

      assert [%{data: %{"to_devices" => [b_dev], "epoch" => 1}}] =
               events(ctx.b.user.id, "mls_welcome")

      assert b_dev == ctx.b_dev
      assert events(ctx.a.user.id, "mls_welcome") == []

      assert %{"commits" => [%{"epoch" => 0, "from_device" => _}]} =
               call(
                 conn,
                 ctx.a.token,
                 :get,
                 "/api/v1/mls/groups/#{ctx.conv}/commits?since_epoch=0"
               )
               |> json_response(200)

      # A second creator loses.
      assert %{"error" => %{"code" => "epoch_conflict", "epoch" => 1}} =
               commit(conn, ctx.b, ctx.b_dev, ctx.conv, %{
                 "generation" => 1,
                 "epoch" => 0,
                 "commit" => b64(),
                 "welcome" => b64(),
                 "added" => [%{"user_id" => ctx.a.user.id, "device_id" => ctx.a_dev}]
               })
               |> json_response(409)
    end

    test "epoch 0 must add all current devices of both members; not_ready otherwise",
         %{conn: conn} = ctx do
      b2 = mls_device!(ctx.b)
      assert create(conn, ctx) |> json_response(400)
      _ = b2

      :ok = MLS.record_instance(ctx.b.user.id, nil, "jwt", nil)

      assert %{"error" => %{"code" => "not_ready", "missing" => [%{"reason" => "legacy_app"}]}} =
               commit(conn, ctx.a, ctx.a_dev, ctx.conv, %{
                 "generation" => 1,
                 "epoch" => 0,
                 "commit" => b64(),
                 "welcome" => b64(),
                 "added" => [
                   %{"user_id" => ctx.b.user.id, "device_id" => ctx.b_dev},
                   %{"user_id" => ctx.b.user.id, "device_id" => b2}
                 ]
               })
               |> json_response(409)
    end

    test "compare-and-set: one of two concurrent commits wins", %{conn: conn} = ctx do
      create(conn, ctx) |> json_response(200)
      body = %{"generation" => 1, "epoch" => 1, "commit" => b64(), "added" => [], "removed" => []}

      results =
        [{ctx.a, ctx.a_dev}, {ctx.b, ctx.b_dev}]
        |> Enum.map(fn {who, dev} ->
          Task.async(fn -> commit(build_conn(), who, dev, ctx.conv, body).status end)
        end)
        |> Task.await_many()

      assert Enum.sort(results) == [200, 409]
      assert MLS.group(ctx.conv).epoch == 2
    end

    test "validation: caller in group, added/removed rules, welcome iff added, friends",
         %{conn: conn} = ctx do
      create(conn, ctx) |> json_response(200)
      base = %{"generation" => 1, "epoch" => 1, "commit" => b64()}

      # The caller's device must be in the group.
      outsider_dev = mls_device!(ctx.a)
      assert commit(conn, ctx.a, outsider_dev, ctx.conv, base).status == 400
      assert commit(conn, ctx.a, nil, ctx.conv, base).status == 400

      # Can't remove the other user's live device; can remove my own.
      assert commit(
               conn,
               ctx.a,
               ctx.a_dev,
               ctx.conv,
               Map.put(base, "removed", [%{"user_id" => ctx.b.user.id, "device_id" => ctx.b_dev}])
             ).status == 400

      # welcome exactly when added.
      assert commit(
               conn,
               ctx.a,
               ctx.a_dev,
               ctx.conv,
               Map.merge(base, %{
                 "added" => [%{"user_id" => ctx.a.user.id, "device_id" => outsider_dev}]
               })
             ).status == 400

      assert commit(conn, ctx.a, ctx.a_dev, ctx.conv, Map.put(base, "welcome", b64())).status ==
               400

      # Adding my new device works; then a removed (no longer current) device of b may be removed.
      assert %{"epoch" => 2} =
               commit(
                 conn,
                 ctx.a,
                 ctx.a_dev,
                 ctx.conv,
                 Map.merge(base, %{
                   "welcome" => b64(),
                   "added" => [%{"user_id" => ctx.a.user.id, "device_id" => outsider_dev}]
                 })
               )
               |> json_response(200)

      # Stale epoch / wrong generation.
      assert %{"error" => %{"code" => "epoch_conflict", "epoch" => 2}} =
               commit(conn, ctx.a, ctx.a_dev, ctx.conv, base) |> json_response(409)

      assert commit(
               conn,
               ctx.a,
               ctx.a_dev,
               ctx.conv,
               %{base | "generation" => 2} |> Map.put("epoch", 2)
             )
             |> json_response(409)

      RisiMe.Social.unfriend(ctx.a.user, ctx.b.user.id)

      assert %{"error" => %{"code" => "not_friends"}} =
               commit(conn, ctx.a, ctx.a_dev, ctx.conv, %{base | "epoch" => 2})
               |> json_response(403)
    end

    test "device removal emits mls_membership to every member; FCM cleanup keeps the MLS device",
         %{conn: conn} = ctx do
      create(conn, ctx) |> json_response(200)
      d = Repo.get_by!(Device, device_id: ctx.b_dev)
      Repo.update_all(from(x in Device, where: x.id == ^d.id), set: [push_token: "fcm-b"])
      RisiMe.Devices.delete_push_token("fcm-b")
      assert %Device{push_token: nil} = Repo.get!(Device, d.id)

      assert call(conn, ctx.b.token, :delete, "/api/v1/me/devices/#{ctx.b_dev}").status == 204

      for u <- [ctx.a.user.id, ctx.b.user.id] do
        assert [%{data: %{"change" => "removed", "device_id" => dev}}] =
                 events(u, "mls_membership")

        assert dev == ctx.b_dev
      end

      # The removed device is no longer current, so a member may remove it.
      assert %{"epoch" => 2} =
               commit(conn, ctx.a, ctx.a_dev, ctx.conv, %{
                 "generation" => 1,
                 "epoch" => 1,
                 "commit" => b64(),
                 "removed" => [%{"user_id" => ctx.b.user.id, "device_id" => ctx.b_dev}]
               })
               |> json_response(200)
    end
  end
end
