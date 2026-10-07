defmodule RisiMeWeb.BlobsV111Test do
  @moduledoc """
  Contract v1.11 §14.2, §14.5, §14.8 (decision 042): `media` and `icon` blobs, streamed
  idempotent uploads, the pre-body checks and their order, quotas, the disk guard, ranges,
  per-purpose readers with membership intervals, usage, soft delete and the sweeps.
  """
  use RisiMeWeb.ChannelCase, async: false

  import Ecto.Query
  import Plug.Conn
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, e2ee_group!: 2]
  import RisiMe.GroupHelpers

  alias RisiMe.{Blobs, Groups, Repo}
  alias RisiMe.Blobs.Slots

  @mib 1024 * 1024

  setup :with_attestation_key

  setup do
    dir = Path.join(System.tmp_dir!(), "risime-blobs-v111-#{System.unique_integer([:positive])}")
    prev = Application.get_env(:risime, :blob_dir)
    Application.put_env(:risime, :blob_dir, dir)

    on_exit(fn ->
      Application.put_env(:risime, :blob_dir, prev)
      File.rm_rf(dir)
    end)

    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    a_dev = images_device!(a)
    b_dev = images_device!(b)
    c_dev = images_device!(c)
    clear_legacy!()

    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, c.user.id]}

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    api(
      :post,
      "/api/v1/mls/groups/#{id}/commit",
      a.token,
      create_commit([a.user.id, b.user.id, c.user.id], {a.user.id, a_dev}),
      a_dev
    )
    |> assert_status(200)

    dm = e2ee_group!(a, b)
    %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, id: id, dm: dm, dir: dir}
  end

  defp images_device!(user, caps \\ ["groups", "images"]) do
    device_id = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(user.user.id, device_id, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => caps
        }
      })

    :ok = RisiMe.MLS.record_instance(user.user.id, device_id, nil, "0.3.0-test")
    device_id
  end

  defp media(conv, extra \\ %{}),
    do:
      Map.merge(
        %{
          "purpose" => "media",
          "conversation_id" => conv,
          "client_blob_id" => Ecto.UUID.generate()
        },
        extra
      )

  defp upload(token, params, bytes, opts \\ []) do
    ct = Keyword.get(opts, :content_type, "application/octet-stream")
    cl = Keyword.get(opts, :content_length, byte_size(bytes))

    conn = Phoenix.ConnTest.build_conn() |> put_req_header("authorization", "Bearer " <> token)
    conn = if ct, do: put_req_header(conn, "content-type", ct), else: conn
    conn = if cl, do: put_req_header(conn, "content-length", to_string(cl)), else: conn

    conn =
      Phoenix.ConnTest.dispatch(
        conn,
        @endpoint,
        :post,
        "/api/v1/blobs?" <> URI.encode_query(params),
        bytes
      )

    {conn.status, decode(conn.resp_body), conn}
  end

  defp up!(token, params, bytes) do
    {status, body, _} = upload(token, params, bytes)
    assert status == 201, "expected 201, got #{status}: #{inspect(body)}"
    body
  end

  defp decode(""), do: nil
  defp decode(body), do: Jason.decode!(body)

  defp get(token, id, headers \\ [], method \\ :get) do
    conn = Phoenix.ConnTest.build_conn() |> put_req_header("authorization", "Bearer " <> token)
    conn = Enum.reduce(headers, conn, fn {k, v}, c -> put_req_header(c, k, v) end)
    Phoenix.ConnTest.dispatch(conn, @endpoint, method, "/api/v1/blobs/#{id}")
  end

  defp status(token, id), do: get(token, id).status
  defp code({_status, %{"error" => %{"code" => code}}, _conn}), do: code
  defp hdr(conn, name), do: conn |> get_resp_header(name) |> List.first()

  defp row(id) do
    Repo.one(
      from b in "blobs",
        where: b.id == type(^id, :binary_id),
        select: %{
          expires_at: type(b.expires_at, :utc_datetime_usec),
          deleted_at: type(b.deleted_at, :utc_datetime_usec),
          purpose: b.purpose
        }
    )
  end

  defp file(dir, id), do: Path.join([dir, String.slice(id, 0, 2), id])

  defp with_env(key, value) do
    prev = Application.get_env(:risime, key)
    Application.put_env(:risime, key, value)

    on_exit(fn ->
      if prev == nil,
        do: Application.delete_env(:risime, key),
        else: Application.put_env(:risime, key, prev)
    end)
  end

  # Rows that count as uploads (rate) without files.
  defp insert_uploads(owner, purpose, conv, n, at \\ DateTime.utc_now()) do
    rows =
      for _ <- 1..n,
          do: %{
            id: Ecto.UUID.dump!(Ecto.UUID.generate()),
            owner: Ecto.UUID.dump!(owner),
            purpose: purpose,
            conversation_id: conv,
            size: 1,
            sha256: :crypto.hash(:sha256, ""),
            expires_at: DateTime.add(DateTime.utc_now(), 1, :day),
            inserted_at: at
          }

    Repo.insert_all("blobs", rows)
  end

  # Holds `n` slots of `kind` for `user` in other processes (like requests in flight).
  defp hold_slots(kind, user_id, n) do
    me = self()

    for _ <- 1..n do
      pid =
        spawn(fn ->
          :ok = Slots.acquire(kind, user_id, 100, 0)
          send(me, :held)
          receive do: (:stop -> :ok)
        end)

      assert_receive :held
      pid
    end
  end

  describe "dm: media (§14.2)" do
    test "upload, replay, mismatch, both users read, others don't, after unfriend and block",
         ctx do
      %{a: a, b: b, c: c, dm: dm} = ctx
      bytes = :crypto.strong_rand_bytes(200_000)
      params = media(dm)

      {201, up, _} = upload(a.token, params, bytes)
      assert up["size"] == 200_000
      assert up["sha256"] == Base.encode64(:crypto.hash(:sha256, bytes))
      assert {:ok, exp, _} = DateTime.from_iso8601(up["expires_at"])
      assert_in_delta DateTime.diff(exp, DateTime.utc_now(), :day), 30, 1

      # Stored mode 600 under <dir>/<2 hex>/<id>, never under a client-chosen name.
      path = file(ctx.dir, up["blob_id"])
      assert File.read!(path) == bytes
      assert {:ok, %{mode: mode}} = File.stat(path)
      assert Bitwise.band(mode, 0o777) == 0o600

      # A repeat after the completed upload: 200, the same reply, the body not read.
      {200, ^up, conn} = upload(a.token, params, "never read", content_length: 200_000)
      assert hdr(conn, "connection") == "close"

      # The same id with another conversation, purpose or length: 400.
      for p <- [
            %{params | "conversation_id" => ctx.id},
            %{params | "purpose" => "mls"},
            %{params | "purpose" => "mls", "conversation_id" => ctx.id}
          ] do
        assert {400, _, _} = upload(a.token, p, bytes)
      end

      assert {400, _, _} = upload(a.token, params, "x", content_length: 199_999)

      assert get(a.token, up["blob_id"]).resp_body == bytes
      assert get(b.token, up["blob_id"]).resp_body == bytes
      assert status(c.token, up["blob_id"]) == 404

      # The DM readers don't depend on the friendship or a block.
      {204, _} = api(:delete, "/api/v1/friends/#{a.user.id}", b.token)
      assert status(b.token, up["blob_id"]) == 200
      {204, _} = api(:post, "/api/v1/blocks", b.token, %{"user_id" => a.user.id})
      assert status(b.token, up["blob_id"]) == 200

      # Uploading needs the friendship and no block now.
      assert {404, _, _} = upload(a.token, media(dm), bytes)
    end

    test "a plaintext DM is 409 not_e2ee; a non-participant or non-friend is 404", ctx do
      %{a: a, c: c} = ctx
      plain = RisiMe.Messaging.conversation_id(a.user.id, c.user.id)
      assert {409, %{"error" => %{"code" => "not_e2ee"}}, _} = upload(a.token, media(plain), "x")
      assert {404, _, _} = upload(c.token, media(ctx.dm), "x")

      d = logged_in_user()
      conv = e2ee_group!(a, d)
      assert {404, _, _} = upload(a.token, media(conv), "x")
      befriend!(a, d)
      assert {201, _, _} = upload(a.token, media(conv), "x")

      # A malformed dm: id is a bad request (purpose and conversation come first).
      assert {400, _, _} = upload(a.token, media("dm:nope"), "x")
    end
  end

  describe "pre-body checks (§14.2 order)" do
    test "each refusal, in order, with Connection: close", ctx do
      %{a: a, b: b, id: id, dm: dm} = ctx
      big = 16 * @mib

      refuse = fn token, params, bytes, opts, expected ->
        {status, body, conn} = upload(token, params, bytes, opts)
        assert {status, code({status, body, conn})} == expected
        assert hdr(conn, "connection") == "close"
      end

      # purpose and conversation (400)
      refuse.(a.token, media(dm, %{"purpose" => "video"}), "x", [], {400, "bad_request"})
      refuse.(a.token, Map.delete(media(dm), "client_blob_id"), "x", [], {400, "bad_request"})
      refuse.(a.token, media(dm, %{"client_blob_id" => "nope"}), "x", [], {400, "bad_request"})
      refuse.(a.token, media(dm, %{"purpose" => "icon"}), "x", [], {400, "bad_request"})
      refuse.(a.token, media("grp:nope"), "x", [], {400, "bad_request"})

      # rights (404 / 403) come before the media type
      stranger = logged_in_user()
      opts = [content_type: "image/jpeg"]
      refuse.(stranger.token, media(id), "x", opts, {404, "not_found"})

      refuse.(
        b.token,
        media(id, %{"purpose" => "icon"}),
        "x",
        opts,
        {403, "not_admin"}
      )

      # Content-Type (415) before Content-Length (400)
      refuse.(
        a.token,
        media(id),
        "x",
        [content_type: "image/jpeg", content_length: nil],
        {415, "bad_media_type"}
      )

      refuse.(a.token, media(id), "x", [content_type: ""], {415, "bad_media_type"})
      refuse.(a.token, media(id), "x", [content_length: nil], {400, "bad_request"})

      # the cap (413 too_large) from Content-Length, before the quota
      with_env(:media_blob_quota, 10)
      refuse.(a.token, media(id), "x", [content_length: big + 1], {413, "too_large"})

      refuse.(
        a.token,
        media(id, %{"purpose" => "icon"}),
        "x",
        [content_length: 512 * 1024 + 1],
        {413, "too_large"}
      )

      # the quota (413 quota_exceeded), before the disk guard
      with_env(:blob_disk, {1, 2 * 1024 ** 4})
      {413, err, _} = upload(a.token, media(id), "x", content_length: 11)

      assert err["error"] == %{
               "code" => "quota_exceeded",
               "message" => "Blob storage quota exceeded",
               "used" => 0,
               "limit" => 10
             }

      # the guard (507), before the rate
      Application.put_env(:risime, :media_blob_quota, 2048 * @mib)
      insert_uploads(a.user.id, "media", id, 120)
      refuse.(a.token, media(id), "x", [], {507, "storage_full"})

      # the rate (429 with Retry-After), before a concurrency slot
      Application.put_env(:risime, :blob_disk, {1024 ** 4, 2 * 1024 ** 4})
      pids = hold_slots(:up, a.user.id, 3)
      {429, _, conn} = upload(a.token, media(id), "x")
      retry = String.to_integer(hdr(conn, "retry-after"))
      assert retry > 3000 and retry <= 3601
      assert hdr(conn, "connection") == "close"

      # a concurrency slot (429, Retry-After: 2)
      Repo.delete_all(from x in "blobs", where: x.owner == type(^a.user.id, :binary_id))
      {429, _, conn} = upload(a.token, media(id), "x")
      assert hdr(conn, "retry-after") == "2"

      # Slots are bound to the process: when the holders die, they're free.
      for pid <- pids, do: Process.exit(pid, :kill)
      Process.sleep(50)
      assert Slots.count(:up, a.user.id) == 0
      assert {201, _, _} = upload(a.token, media(id), "x")
      # The request released its own slot after the response.
      assert Slots.count(:up, a.user.id) == 0
    end

    test "rates: media 120/h and 1000/day, icon 3/h, mls 60/h; replays and refusals don't count",
         ctx do
      %{a: a, id: id} = ctx
      p = media(id)
      up = up!(a.token, p, "x")
      insert_uploads(a.user.id, "media", id, 118)

      # 120th accepted, then limited; a replay is still a 200 when under the limit...
      assert {201, _, _} = upload(a.token, media(id), "x")
      assert {429, _, _} = upload(a.token, media(id), "x")
      {200, usage} = api(:get, "/api/v1/blobs/usage", a.token)
      assert usage["media"]["uploads_last_hour"] == 120

      # ...and refused attempts never extend the window: rows from an hour ago free it.
      Repo.update_all(
        from(x in "blobs", where: x.owner == type(^a.user.id, :binary_id)),
        set: [inserted_at: DateTime.add(DateTime.utc_now(), -3601, :second)]
      )

      assert {200, ^up, _} = upload(a.token, p, "x")
      assert {201, _, _} = upload(a.token, media(id), "x")

      # daily
      insert_uploads(a.user.id, "media", id, 1000, DateTime.add(DateTime.utc_now(), -7200))
      {429, _, conn} = upload(a.token, media(id), "x")
      assert String.to_integer(hdr(conn, "retry-after")) > 3600

      # icon 3/h
      for _ <- 1..3, do: up!(a.token, media(id, %{"purpose" => "icon"}), "i")
      assert {429, _, _} = upload(a.token, media(id, %{"purpose" => "icon"}), "i")

      # mls 60/h (client_blob_id optional)
      insert_uploads(a.user.id, "mls", id, 60)
      mls = %{"purpose" => "mls", "conversation_id" => id}
      assert {429, _, _} = upload(a.token, mls, "m")
    end
  end

  describe "§14 fixes (root decisions)" do
    test "idempotency before the quota, guard, rate and slot: a retry whose 201 was lost gets 200",
         ctx do
      %{a: a, id: id} = ctx
      p = media(id)
      up = up!(a.token, p, "xyz")

      # At the quota.
      with_env(:media_blob_quota, 1)
      assert {200, ^up, _} = upload(a.token, p, "xyz")
      assert {413, _, _} = upload(a.token, media(id), "xyz")

      # Disk full.
      with_env(:blob_disk, {1, 2 * 1024 ** 4})
      assert {200, ^up, _} = upload(a.token, p, "xyz")

      # Rate exhausted and every slot held.
      Application.put_env(:risime, :blob_disk, {1024 ** 4, 2 * 1024 ** 4})
      Application.put_env(:risime, :media_blob_quota, 2048 * @mib)
      insert_uploads(a.user.id, "media", id, 1200)
      pids = hold_slots(:up, a.user.id, 3)
      assert {200, ^up, _} = upload(a.token, p, "xyz")
      assert {429, _, _} = upload(a.token, media(id), "xyz")

      # A mismatch is still 400 and a deleted blob 404, also when limited.
      assert {400, _, _} = upload(a.token, p, "xyzw")
      assert {204, _} = api(:delete, "/api/v1/blobs/#{up["blob_id"]}", a.token)
      assert {404, _, _} = upload(a.token, p, "xyz")
      for pid <- pids, do: Process.exit(pid, :kill)
    end

    test "images_ready counts only installs that can still receive (as §12.1)", ctx do
      %{a: a, b: b, dm: dm} = ctx
      alias RisiMe.MLS.Images
      assert Images.missing([a.user.id, b.user.id]) == []

      # A census instance of a device id that isn't registered (removed) can't receive.
      :ok = RisiMe.MLS.record_instance(b.user.id, Ecto.UUID.generate(), nil, "0.2.0")
      assert Images.missing([b.user.id]) == []

      # A device-less (pre-v1.7) instance seen before b's latest registration can't either...
      :ok = RisiMe.MLS.record_instance(b.user.id, nil, "old", "0.1.0")

      Repo.update_all(
        from(i in "app_instances", where: i.instance_key == "legacy:old"),
        set: [last_seen_at: DateTime.add(DateTime.utc_now(), -3600)]
      )

      assert Images.missing([b.user.id]) == []

      # ...but one seen after it can.
      Process.sleep(5)
      :ok = RisiMe.MLS.record_instance(b.user.id, nil, "new", "0.1.0")
      assert Images.missing([b.user.id]) == [%{user_id: b.user.id, device_id: nil}]
      {200, view} = api(:get, "/api/v1/mls/groups/#{dm}", a.token)
      assert view["images_ready"] == false
    end

    test "a nightly.16-style reinstall registers fully and its replaced install never blocks",
         ctx do
      %{a: a, b: b, b_dev: old, dm: dm} = ctx
      # Before: b's install has no `deletes`/`calls`, so it's listed (as on nightly.11/12).
      {200, view} = api(:get, "/api/v1/mls/groups/#{dm}", a.token)
      assert %{"user_id" => b.user.id, "device_id" => old} in view["missing_deletes"]

      # b's phone comes back as a new install (new device id) with every capability.
      Process.sleep(5)
      new = Ecto.UUID.generate()

      body = %{
        "platform" => "android",
        "app_version" => "0.2.0-nightly.16",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => ~w(groups images deletes calls)
        }
      }

      {200, %{"attestation" => att}} = api(:put, "/api/v1/me/devices/#{new}", b.token, body)
      assert is_binary(att)
      :ok = RisiMe.MLS.record_instance(b.user.id, new, nil, "0.2.0-nightly.16")

      {200, view} = api(:get, "/api/v1/mls/groups/#{dm}", a.token)
      assert view["images_ready"] == true and view["missing_images"] == []

      for k <- ~w(missing_images missing_deletes missing_calls),
          entry <- view[k],
          do: refute(entry["device_id"] == old)

      refute Enum.any?(
               view["missing_deletes"] ++ view["missing_calls"],
               &(&1["user_id"] == b.user.id)
             )

      # §12.1: still group-ready for a's pickers.
      {200, %{"friends" => friends}} = api(:get, "/api/v1/friends", a.token)
      assert Enum.find(friends, &(&1["user_id"] == b.user.id))["group_ready"] == true

      # The replaced install is seen again (a second phone after all): it counts again.
      :ok = RisiMe.MLS.record_instance(b.user.id, old, nil, "0.2.0-nightly.12")
      {200, view} = api(:get, "/api/v1/mls/groups/#{dm}", a.token)
      assert %{"user_id" => b.user.id, "device_id" => old} in view["missing_deletes"]
    end
  end

  describe "streaming (§14.2)" do
    test "a lying Content-Length is aborted mid-stream with 413; short bodies store nothing",
         ctx do
      %{a: a, id: id, dir: dir} = ctx
      bytes = :crypto.strong_rand_bytes(300_000)

      {413, err, conn} = upload(a.token, media(id), bytes, content_length: 100_000)
      assert err["error"]["code"] == "too_large"
      assert hdr(conn, "connection") == "close"

      # The client went away (fewer bytes than declared): nothing stored.
      {400, _, _} = upload(a.token, media(id), bytes, content_length: 400_000)

      assert media_count() == 0
      assert File.ls!(Path.join(dir, ".tmp")) == []

      # A multi-chunk body is hashed while streaming.
      big = :crypto.strong_rand_bytes(3 * @mib + 17)
      up = up!(a.token, media(id), big)
      assert up["size"] == byte_size(big)
      assert up["sha256"] == Base.encode64(:crypto.hash(:sha256, big))
      assert File.read!(file(dir, up["blob_id"])) == big
      assert File.ls!(Path.join(dir, ".tmp")) == []
    end

    test "the 16 MiB media cap is exact", ctx do
      %{a: a, id: id} = ctx
      bytes = :binary.copy(<<7>>, 16 * @mib)
      assert %{"size" => 16_777_216} = up!(a.token, media(id), bytes)
      assert {413, _, _} = upload(a.token, media(id), bytes <> "x")
    end

    test "concurrent uploads with the same client_blob_id: one row, the loser replays", ctx do
      %{a: a, id: id} = ctx
      params = media(id)
      me = a.user.id
      bytes = "same ciphertext"
      size = byte_size(bytes)

      # Both pass every pre-body check before either commits (the race), then both finish.
      {:ok, up1} = Blobs.begin_upload(me, params, "application/octet-stream", size)
      {:ok, up2} = Blobs.begin_upload(me, params, "application/octet-stream", size)
      sha = :crypto.hash(:sha256, bytes)

      tmp_file = fn ->
        {tmp, io} = Blobs.open_tmp()
        :ok = :file.write(io, bytes)
        :ok = :file.close(io)
        tmp
      end

      {:created, r1} = Blobs.finish(me, up1, tmp_file.(), size, sha)
      {:replay, r2} = Blobs.finish(me, up2, tmp_file.(), size, sha)
      assert r1 == r2
      Blobs.release_upload(me)

      assert media_count() == 1
      # The loser's file is gone; only the winner's remains.
      files = Path.wildcard(Path.join(ctx.dir, "??/*"))
      assert files == [file(ctx.dir, r1.blob_id)]
    end

    test "the quota is rechecked under the owner's lock: concurrent uploads can't overshoot",
         ctx do
      %{a: a, id: id} = ctx
      with_env(:media_blob_quota, 150)
      me = a.user.id
      {:ok, u1} = Blobs.begin_upload(me, media(id), "application/octet-stream", 100)
      {:ok, u2} = Blobs.begin_upload(me, media(id), "application/octet-stream", 100)

      finish = fn up ->
        {tmp, io} = Blobs.open_tmp()
        :ok = :file.write(io, :binary.copy(<<1>>, 100))
        :ok = :file.close(io)
        Blobs.finish(me, up, tmp, 100, :crypto.hash(:sha256, :binary.copy(<<1>>, 100)))
      end

      assert {:created, _} = finish.(u1)
      assert {:error, {:quota_exceeded, 100, 150}} = finish.(u2)
      Blobs.release_upload(me)
      assert Blobs.used_bytes(me, "media") == 100
    end
  end

  describe "disk guard (§14.5, decision 042 defaults)" do
    test "media stops under max(50 GiB, 10 %); mls and icon go on to 20 GiB; global cap", ctx do
      %{a: a, id: id} = ctx
      gib = 1024 ** 3
      mls = %{"purpose" => "mls", "conversation_id" => id}
      icon = media(id, %{"purpose" => "icon"})

      # 10 % of 1 TiB (~102 GiB) is the media floor here, above 50 GiB.
      with_env(:blob_disk, {100 * gib, 1024 * gib})

      assert {507, %{"error" => %{"code" => "storage_full"} = e}, _} =
               upload(a.token, media(id), "x")

      assert e == example("error_storage_full.json")["error"]
      assert {201, _, _} = upload(a.token, mls, "x")
      assert {201, _, _} = upload(a.token, icon, "x")

      Application.put_env(:risime, :blob_disk, {60 * gib, 200 * gib})
      assert {201, _, _} = upload(a.token, media(id), "x")

      # In-flight uploads count against the free space.
      Application.put_env(:risime, :blob_disk, {50 * gib + 10 * @mib, 200 * gib})

      pid =
        spawn(fn ->
          :ok = Slots.acquire(:up, Ecto.UUID.generate(), 3, 16 * @mib)
          receive do: (:stop -> :ok)
        end)

      Process.sleep(20)
      assert {507, _, _} = upload(a.token, media(id), "x")
      Process.exit(pid, :kill)
      Process.sleep(20)
      assert {201, _, _} = upload(a.token, media(id), "x")

      Application.put_env(:risime, :blob_disk, {19 * gib, 200 * gib})
      assert {507, _, _} = upload(a.token, mls, "x")
      assert {507, _, _} = upload(a.token, media(id, %{"purpose" => "icon"}), "x")

      # Over the global live media cap.
      Application.put_env(:risime, :blob_disk, {500 * gib, 1000 * gib})
      with_env(:blob_guard, media_total_cache_ms: 0, media_max: 3)
      assert {507, _, _} = upload(a.token, media(id), "xy")
      assert {201, _, _} = upload(a.token, mls, "xyz")
    end
  end

  describe "downloads (§14.2)" do
    setup ctx do
      bytes = :crypto.strong_rand_bytes(1000)
      up = up!(ctx.a.token, media(ctx.id), bytes)

      %{
        bytes: bytes,
        up: up,
        etag: ~s("#{Base.encode16(:crypto.hash(:sha256, bytes), case: :lower)}")
      }
    end

    test "200 headers, ranges, suffix, 416, If-Range, 304, HEAD", ctx do
      %{a: a, bytes: bytes, up: %{"blob_id" => id}, etag: etag} = ctx

      conn = get(a.token, id)
      assert conn.status == 200 and conn.resp_body == bytes
      assert hdr(conn, "content-type") == "application/octet-stream"
      assert hdr(conn, "x-content-type-options") == "nosniff"
      assert hdr(conn, "content-disposition") == "attachment"
      assert hdr(conn, "accept-ranges") == "bytes"
      assert hdr(conn, "etag") == etag
      assert hdr(conn, "cache-control") == "private, max-age=86400, immutable"
      assert hdr(conn, "content-encoding") == nil

      range = fn spec, extra ->
        get(a.token, id, [{"range", spec} | extra])
      end

      conn = range.("bytes=10-19", [])
      assert conn.status == 206 and conn.resp_body == binary_part(bytes, 10, 10)
      assert hdr(conn, "content-range") == "bytes 10-19/1000"
      assert hdr(conn, "etag") == etag and hdr(conn, "accept-ranges") == "bytes"

      conn = range.("bytes=990-", [])
      assert {conn.status, conn.resp_body} == {206, binary_part(bytes, 990, 10)}
      assert hdr(conn, "content-range") == "bytes 990-999/1000"

      conn = range.("bytes=-5", [])
      assert {conn.status, conn.resp_body} == {206, binary_part(bytes, 995, 5)}
      assert hdr(conn, "content-range") == "bytes 995-999/1000"

      conn = range.("bytes=-5000", [])
      assert {conn.status, conn.resp_body} == {206, bytes}

      conn = range.("bytes=500-99999", [])
      assert {conn.status, hdr(conn, "content-range")} == {206, "bytes 500-999/1000"}

      for spec <- ["bytes=0-1,5-6", "bytes=1000-", "bytes=5-4", "bytes=-0"] do
        conn = range.(spec, [])
        assert {conn.status, hdr(conn, "content-range")} == {416, "bytes */1000"}, spec
      end

      # If-Range: a mismatched validator gets the whole blob.
      assert range.("bytes=0-9", [{"if-range", ~s("other")}]).status == 200
      assert range.("bytes=0-9", [{"if-range", etag}]).status == 206

      # If-None-Match
      conn = get(a.token, id, [{"if-none-match", etag}])
      assert {conn.status, conn.resp_body, hdr(conn, "etag")} == {304, "", etag}
      assert get(a.token, id, [{"if-none-match", ~s("x", #{etag})}]).status == 304
      assert get(a.token, id, [{"if-none-match", ~s("x")}]).status == 200

      # HEAD
      conn = get(a.token, id, [], :head)
      assert conn.status == 200 and conn.resp_body == ""
      assert hdr(conn, "etag") == etag

      # Any Accept is fine for the bytes.
      assert get(a.token, id, [{"accept", "application/octet-stream"}]).status == 200
    end

    test "an expiry race (row expired, or file swept after the check) is 404", ctx do
      %{a: a, up: %{"blob_id" => id}} = ctx
      File.rm!(file(ctx.dir, id))
      assert status(a.token, id) == 404

      Repo.update_all(from(x in "blobs", where: x.id == type(^id, :binary_id)),
        set: [expires_at: DateTime.add(DateTime.utc_now(), -1)]
      )

      assert status(a.token, id) == 404
    end

    test "at most 8 concurrent downloads per user (429)", ctx do
      %{a: a, up: %{"blob_id" => id}} = ctx
      pids = hold_slots(:down, a.user.id, 8)
      conn = get(a.token, id)
      assert {conn.status, hdr(conn, "retry-after")} == {429, "2"}
      for pid <- pids, do: Process.exit(pid, :kill)
      Process.sleep(50)
      assert status(a.token, id) == 200
      assert Slots.count(:down, a.user.id) == 0
    end
  end

  describe "group readers (§14.2 media, icon)" do
    defp add_member!(ctx, user, dev, epoch, while_pending) do
      {200, %{"group" => %{"pending" => pending}}} =
        api(
          :post,
          "/api/v1/groups/#{ctx.id}/members",
          ctx.a.token,
          %{"user_ids" => [user.user.id]},
          ctx.a_dev
        )

      op = Enum.find(pending, &(&1["type"] == "add"))
      while_pending.()

      body = %{
        "generation" => 1,
        "epoch" => epoch,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [ref(user.user.id, dev)],
        "op_id" => op["op_id"]
      }

      api(:post, "/api/v1/mls/groups/#{ctx.id}/commit", ctx.a.token, body, ctx.a_dev)
      |> assert_status(200)
    end

    test "removed after the upload reads; removed before doesn't; the owner always", ctx do
      %{a: a, b: b, c: c, id: id} = ctx

      # c is removed before the upload; b after it.
      {204, _} =
        api(:delete, "/api/v1/groups/#{id}/members/#{c.user.id}", a.token, nil, ctx.a_dev)

      Process.sleep(5)
      b_up = up!(b.token, media(id), "from b")
      %{"blob_id" => blob} = up!(a.token, media(id), "from a")
      Process.sleep(5)

      {204, _} =
        api(:delete, "/api/v1/groups/#{id}/members/#{b.user.id}", a.token, nil, ctx.a_dev)

      assert status(b.token, blob) == 200
      assert status(c.token, blob) == 404

      # The owner keeps reading after leaving the group; but can no longer upload to it.
      assert status(b.token, b_up["blob_id"]) == 200
      assert {404, _, _} = upload(b.token, media(id), "x")
    end

    test "pending_add can't read; once active it can (§14.9 joiners)", ctx do
      %{a: a, id: id} = ctx
      %{"blob_id" => blob} = up!(a.token, media(id), "from a")
      d = logged_in_user()
      befriend!(a, d)
      d_dev = images_device!(d)
      clear_legacy!()

      add_member!(ctx, d, d_dev, 1, fn ->
        assert %{state: "pending_add"} = Groups.member(id, d.user.id)
        assert status(d.token, blob) == 404
      end)

      assert %{state: "active"} = Groups.member(id, d.user.id)
      assert status(d.token, blob) == 200
    end

    test "icons: admin only, never in a DM, current members only, superseded after 7 days", ctx do
      %{a: a, b: b, c: c, id: id} = ctx
      icon = fn -> media(id, %{"purpose" => "icon"}) end

      first = up!(a.token, icon.(), "icon 1")
      assert first["expires_at"] == nil
      assert row(first["blob_id"]).expires_at == nil

      assert {403, %{"error" => %{"code" => "not_admin"}}, _} = upload(b.token, icon.(), "i")
      assert {400, _, _} = upload(a.token, media(ctx.dm, %{"purpose" => "icon"}), "i")

      second = up!(a.token, icon.(), "icon 2")
      exp = row(first["blob_id"]).expires_at
      assert_in_delta DateTime.diff(exp, DateTime.utc_now(), :day), 7, 1
      assert row(second["blob_id"]).expires_at == nil

      # Icons don't count against the media quota.
      assert Blobs.used_bytes(a.user.id, "media") == 0
      {200, usage} = api(:get, "/api/v1/blobs/usage", a.token)
      assert usage["media"]["used"] == 0

      for blob <- [first, second],
          user <- [a, b, c],
          do: assert(status(user.token, blob["blob_id"]) == 200)

      # A pending_add member reads icons; a removed member loses them at once.
      d = logged_in_user()
      befriend!(a, d)
      images_device!(d)

      {200, _} =
        api(
          :post,
          "/api/v1/groups/#{id}/members",
          a.token,
          %{"user_ids" => [d.user.id]},
          ctx.a_dev
        )

      assert status(d.token, second["blob_id"]) == 200

      {204, _} =
        api(:delete, "/api/v1/groups/#{id}/members/#{c.user.id}", a.token, nil, ctx.a_dev)

      assert status(c.token, second["blob_id"]) == 404

      # An admin who uploaded it and then lost membership loses it too (current members only).
      stranger = logged_in_user()
      assert status(stranger.token, second["blob_id"]) == 404
    end

    test "mls readers are unchanged; a commit can't reference a media blob", ctx do
      %{a: a, b: b, id: id} = ctx
      m = up!(a.token, media(id), "media")
      ref = Map.take(m, ~w(blob_id size sha256))
      refute Blobs.ref_ok?(a.user.id, id, ref, 1_000_000)

      {201, mls, _} = upload(a.token, %{"purpose" => "mls", "conversation_id" => id}, "mls")
      assert mls["expires_at"] != nil
      assert Blobs.ref_ok?(a.user.id, id, Map.take(mls, ~w(blob_id size sha256)), 1_000_000)
      assert status(b.token, mls["blob_id"]) == 200
    end
  end

  describe "membership intervals (§14.8)" do
    defp intervals(id) do
      Repo.all(
        from i in "group_member_intervals",
          where: i.group_id == ^id,
          order_by: [asc: i.id],
          select: {type(i.user_id, :binary_id), is_nil(i.active_until)}
      )
    end

    # An open interval exists iff the member's state is `active`; at most one open per pair.
    defp assert_invariant(id) do
      open = for {u, true} <- intervals(id), do: u
      assert open == Enum.uniq(open)
      active = Groups.active_member_ids(id)
      assert Enum.sort(open) == Enum.sort(active)
    end

    test "open on active, closed on pending_remove/leave/delete/reset, reopened on re-add", ctx do
      %{a: a, b: b, c: c, id: id, a_dev: a_dev} = ctx
      assert_invariant(id)
      assert length(intervals(id)) == 3

      # A creating group: only the creator.
      e = logged_in_user()
      befriend!(a, e)
      images_device!(e)
      body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [e.user.id]}

      %{"group" => %{"id" => g2}} =
        api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

      assert intervals(g2) == [{a.user.id, true}]
      assert_invariant(g2)

      # Removal (pending_remove) closes at once; the completed removal deletes the member.
      {204, _} = api(:delete, "/api/v1/groups/#{id}/members/#{c.user.id}", a.token, nil, a_dev)
      assert_invariant(id)
      [rm] = RisiMe.Groups.Ops.list(id)

      {200, _} =
        api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          %{
            "generation" => 1,
            "epoch" => 1,
            "commit" => b64(),
            "removed" => [ref(c.user.id, ctx.c_dev)],
            "op_id" => rm.op_id
          },
          a_dev
        )

      assert Groups.member(id, c.user.id) == nil
      assert_invariant(id)

      # Re-added: a second interval for the same pair.
      {200, %{"group" => %{"pending" => [op]}}} =
        api(:post, "/api/v1/groups/#{id}/members", a.token, %{"user_ids" => [c.user.id]}, a_dev)

      assert_invariant(id)

      {200, _} =
        api(
          :post,
          "/api/v1/mls/groups/#{id}/commit",
          a.token,
          %{
            "generation" => 1,
            "epoch" => 2,
            "commit" => b64(),
            "welcome" => b64(),
            "added" => [ref(c.user.id, ctx.c_dev)],
            "op_id" => op["op_id"]
          },
          a_dev
        )

      assert_invariant(id)
      assert Enum.count(intervals(id), &(elem(&1, 0) == c.user.id)) == 2

      # Leave.
      {204, _} = api(:post, "/api/v1/groups/#{id}/leave", b.token, nil, ctx.b_dev)
      assert_invariant(id)

      # Reset keeps active members open and expires the group's blobs.
      blob = up!(a.token, media(id), "before reset")

      {200, _} =
        api(:post, "/api/v1/mls/groups/#{id}/reset", a.token, %{"generation" => 1}, a_dev)

      assert_invariant(id)
      assert status(a.token, blob["blob_id"]) == 404
      assert DateTime.compare(row(blob["blob_id"]).expires_at, DateTime.utc_now()) != :gt

      # A group still creating after 10 minutes is deleted: intervals and blobs go.
      icon = up!(a.token, media(g2, %{"purpose" => "icon"}), "icon")
      :ok = perform_job(RisiMe.Workers.GroupTimer, %{"kind" => "creating", "group_id" => g2})
      assert intervals(g2) == []
      assert status(a.token, icon["blob_id"]) == 404
    end
  end

  describe "usage, delete, sweeps (§14.2, §14.8)" do
    test "GET /blobs/usage", ctx do
      %{a: a, id: id} = ctx
      up!(a.token, media(id), :binary.copy(<<1>>, 1000))
      {201, _, _} = upload(a.token, %{"purpose" => "mls", "conversation_id" => id}, "12345")

      {200, usage} = api(:get, "/api/v1/blobs/usage", a.token)

      assert usage == %{
               "media" => %{
                 "used" => 1000,
                 "limit" => 2 * 1024 * @mib,
                 "uploads_last_hour" => 1,
                 "hourly_limit" => 120,
                 "uploads_last_day" => 1,
                 "daily_limit" => 1000
               },
               "mls" => %{"used" => 5, "limit" => 256 * @mib},
               # v1.15 §17.9.
               "history" => %{
                 "used" => 0,
                 "limit" => 512 * @mib,
                 "uploads_last_hour" => 0,
                 "hourly_limit" => 40
               }
             }
    end

    test "DELETE: file gone at once, row kept, replay 404, quota freed, idempotent", ctx do
      %{a: a, b: b, id: id, dir: dir} = ctx
      params = media(id)
      up = up!(a.token, params, "abc")
      assert {404, _} = api(:delete, "/api/v1/blobs/#{up["blob_id"]}", b.token)
      assert {204, _} = api(:delete, "/api/v1/blobs/#{up["blob_id"]}", a.token)
      assert {204, _} = api(:delete, "/api/v1/blobs/#{up["blob_id"]}", a.token)
      refute File.exists?(file(dir, up["blob_id"]))
      assert %{deleted_at: %DateTime{}} = row(up["blob_id"])
      assert status(a.token, up["blob_id"]) == 404
      assert {404, _, _} = upload(a.token, params, "abc")
      assert Blobs.used_bytes(a.user.id, "media") == 0

      # A deleted current icon gets an expiry, so the sweep removes its row eventually.
      icon = up!(a.token, media(id, %{"purpose" => "icon"}), "i")
      {204, _} = api(:delete, "/api/v1/blobs/#{icon["blob_id"]}", a.token)
      assert %DateTime{} = row(icon["blob_id"]).expires_at
    end

    test "the sweep deletes expired rows in batches, then files; stale temp files go", ctx do
      %{a: a, id: id, dir: dir} = ctx
      keep = up!(a.token, media(id), "keep")
      gone = up!(a.token, media(id), "gone")

      Repo.update_all(from(x in "blobs", where: x.id == type(^gone["blob_id"], :binary_id)),
        set: [expires_at: DateTime.add(DateTime.utc_now(), -1)]
      )

      insert_uploads(a.user.id, "mls", id, 1500)

      Repo.update_all(from(x in "blobs", where: x.purpose == "mls"),
        set: [expires_at: DateTime.add(DateTime.utc_now(), -1)]
      )

      tmp = Path.join(dir, ".tmp")
      File.mkdir_p!(tmp)
      File.write!(Path.join(tmp, "old"), "x")
      File.touch!(Path.join(tmp, "old"), System.os_time(:second) - 7200)
      File.write!(Path.join(tmp, "new"), "x")

      assert Blobs.cleanup() == 1501
      assert row(gone["blob_id"]) == nil
      refute File.exists?(file(dir, gone["blob_id"]))
      assert File.exists?(file(dir, keep["blob_id"]))
      assert File.ls!(tmp) == ["new"]

      # A current icon (no expiry) survives any sweep.
      icon = up!(a.token, media(id, %{"purpose" => "icon"}), "i")
      Blobs.cleanup(DateTime.add(DateTime.utc_now(), 365, :day))
      assert row(icon["blob_id"]) != nil
      assert row(keep["blob_id"]) == nil
    end

    test "the orphan pass removes old files without a live row", ctx do
      %{a: a, id: id, dir: dir} = ctx
      keep = up!(a.token, media(id), "keep")
      old = System.os_time(:second) - 7200

      orphan = Ecto.UUID.generate()
      File.mkdir_p!(Path.dirname(file(dir, orphan)))
      File.write!(file(dir, orphan), "o")
      File.touch!(file(dir, orphan), old)

      young = Ecto.UUID.generate()
      File.mkdir_p!(Path.dirname(file(dir, young)))
      File.write!(file(dir, young), "y")

      File.touch!(file(dir, keep["blob_id"]), old)

      assert Blobs.orphans() == 1
      refute File.exists?(file(dir, orphan))
      assert File.exists?(file(dir, young))
      assert File.exists?(file(dir, keep["blob_id"]))
      assert :ok = perform_job(RisiMe.Workers.BlobCleanup, %{"pass" => "orphans"}) |> elem(0)
    end
  end

  defp example(name),
    do:
      Path.expand("../../../../contract/v1/examples/#{name}", __DIR__)
      |> File.read!()
      |> Jason.decode!()

  defp media_count,
    do: Repo.one(from x in "blobs", where: x.purpose == "media", select: count())

  defp perform_job(worker, args), do: worker.perform(%Oban.Job{args: args})
end
