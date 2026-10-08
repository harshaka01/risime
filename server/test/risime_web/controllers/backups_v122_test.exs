defmodule RisiMeWeb.BackupsV122Test do
  @moduledoc """
  Contract v1.22 §22 (decision 059): the `BACKUPS` switch, `backup` blob parts, `POST`/`GET`/
  `DELETE /backups` (checks in order, retention, the one-backup-device rule), `PUT`/`GET
  /backup_key` (no silent key change), `GET /blobs/usage` and the sweep.
  """
  use RisiMeWeb.ChannelCase, async: false

  import Ecto.Query
  import Plug.Conn
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.{Backups, Blobs, Repo}
  alias RisiMe.Backups.Backup

  @endpoint RisiMeWeb.Endpoint
  @mib 1024 * 1024

  setup :with_attestation_key

  setup do
    dir = Path.join(System.tmp_dir!(), "risime-blobs-v122-#{System.unique_integer([:positive])}")
    prev = Application.get_env(:risime, :blob_dir)
    Application.put_env(:risime, :blob_dir, dir)

    on_exit(fn ->
      Application.put_env(:risime, :blob_dir, prev)
      Application.delete_env(:risime, :backups)
      Application.delete_env(:risime, :backup_quota)
      File.rm_rf(dir)
    end)

    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    %{a: a, b: b, a1: device!(a), b1: device!(b)}
  end

  ## Helpers

  defp device!(user) do
    dev = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(user.user.id, dev, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => ["groups"]
        }
      })

    :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end

  defp decode(""), do: nil

  defp decode(body) do
    case Jason.decode(body) do
      {:ok, json} -> json
      _ -> body
    end
  end

  defp api(method, path, token, body \\ nil, device \\ nil) do
    conn = Phoenix.ConnTest.build_conn() |> put_req_header("authorization", "Bearer " <> token)
    conn = if device, do: put_req_header(conn, "x-device-id", device), else: conn
    conn = Phoenix.ConnTest.dispatch(conn, @endpoint, method, path, body)
    {conn.status, decode(conn.resp_body)}
  end

  defp upload(token, device, params, bytes, opts \\ []) do
    conn =
      Phoenix.ConnTest.build_conn()
      |> put_req_header("authorization", "Bearer " <> token)
      |> put_req_header("content-type", "application/octet-stream")
      |> put_req_header(
        "content-length",
        to_string(Keyword.get(opts, :content_length, byte_size(bytes)))
      )

    conn = if device, do: put_req_header(conn, "x-device-id", device), else: conn

    conn =
      Phoenix.ConnTest.dispatch(
        conn,
        @endpoint,
        :post,
        "/api/v1/blobs?" <> URI.encode_query(params),
        bytes
      )

    {conn.status, decode(conn.resp_body)}
  end

  defp part_params(backup_id),
    do: %{
      "purpose" => "backup",
      "backup_id" => backup_id,
      "client_blob_id" => Ecto.UUID.generate()
    }

  # Uploads `n` parts of `size` bytes for a new backup id; returns `{backup_id, parts}`.
  defp parts!(user, dev, n \\ 2, size \\ 100, backup_id \\ Ecto.UUID.generate()) do
    parts =
      for _ <- 1..n do
        {201, p} =
          upload(user.token, dev, part_params(backup_id), :crypto.strong_rand_bytes(size))

        Map.take(p, ~w(blob_id size sha256))
      end

    {backup_id, parts}
  end

  @bk "en20cm7Cc5c="

  defp key_body(bk \\ @bk, kinds \\ ["recovery_key"]) do
    %{
      "v" => 1,
      "bk_id" => bk,
      "wraps" =>
        for k <- kinds do
          %{
            "kind" => k,
            "kdf" => %{
              "alg" => "argon2id",
              "m" => 65_536,
              "t" => 3,
              "p" => 1,
              "salt" => Base.encode64(:crypto.strong_rand_bytes(16))
            },
            "nonce" => Base.encode64(:crypto.strong_rand_bytes(12)),
            "wrapped" => Base.encode64(:crypto.strong_rand_bytes(48)),
            "check" => Base.encode64(:crypto.strong_rand_bytes(16))
          }
        end
    }
  end

  defp key!(user, dev, bk \\ @bk),
    do: {200, _} = api(:put, "/api/v1/backup_key", user.token, key_body(bk), dev)

  defp commit_body({backup_id, parts}, extra \\ %{}) do
    Map.merge(
      %{
        "backup_id" => backup_id,
        "created_at" => "2026-10-08T02:00:00.000Z",
        "size" => parts |> Enum.map(& &1["size"]) |> Enum.sum(),
        "sha256" => Base.encode64(:crypto.strong_rand_bytes(32)),
        "schema" => 1,
        "app_version" => "0.3.0-test",
        "bk_id" => @bk,
        "parts" => parts,
        "replace_device" => false
      },
      extra
    )
  end

  defp commit(user, dev, body), do: api(:post, "/api/v1/backups", user.token, body, dev)

  defp backup!(user, dev) do
    body = commit_body(parts!(user, dev))
    {201, %{"backup" => b}} = commit(user, dev, body)
    b
  end

  defp blob_expiry(id),
    do:
      Repo.one(
        from b in "blobs",
          where: b.id == type(^id, :binary_id),
          select: type(b.expires_at, :utc_datetime_usec)
      )

  ## §22.1 the switch

  describe "the switch (§22.1)" do
    test "auth/config; off stops writes only", %{a: a, a1: a1} do
      assert {200, %{"backup" => "on"}} = api(:get, "/api/v1/auth/config", a.token)
      key!(a, a1)
      b = backup!(a, a1)

      Application.put_env(:risime, :backups, false)
      assert {200, %{"backup" => "off"}} = api(:get, "/api/v1/auth/config", a.token)

      off = %{
        "error" => %{"code" => "backup_unavailable", "message" => "Server backups are turned off"}
      }

      assert {503, ^off} =
               upload(a.token, a1, part_params(Ecto.UUID.generate()), "abc")

      fake = %{"blob_id" => Ecto.UUID.generate(), "size" => 1, "sha256" => b["sha256"]}
      assert {503, ^off} = commit(a, a1, commit_body({Ecto.UUID.generate(), [fake]}))
      assert {503, ^off} = api(:put, "/api/v1/backup_key", a.token, key_body(), a1)

      # Reads, downloads and DELETE keep working.
      assert {200, %{"backups" => [_]}} = api(:get, "/api/v1/backups", a.token)
      assert {200, %{"backup_key" => _}} = api(:get, "/api/v1/backup_key", a.token)
      [%{"blob_id" => part} | _] = b["parts"]
      assert {200, _} = api(:get, "/api/v1/blobs/#{part}", a.token)
      assert {204, nil} = api(:delete, "/api/v1/backups", a.token)
    end
  end

  ## §22.3 parts

  describe "backup parts (§22.3)" do
    test "device, no conversation, backup_id, part cap, 24 h TTL, idempotency, owner-only reads",
         %{a: a, b: b, a1: a1} do
      bid = Ecto.UUID.generate()
      params = part_params(bid)

      assert {403, %{"error" => %{"code" => "invalid_device"}}} =
               upload(a.token, nil, params, "x")

      assert {400, _} =
               upload(a.token, a1, Map.put(params, "conversation_id", "grp:x"), "x")

      assert {400, _} = upload(a.token, a1, Map.delete(params, "backup_id"), "x")
      assert {400, _} = upload(a.token, a1, Map.delete(params, "client_blob_id"), "x")

      assert {413, %{"error" => %{"code" => "too_large"}}} =
               upload(a.token, a1, params, "x", content_length: 33_562_625)

      {201, up} = upload(a.token, a1, params, "hello")
      assert %{"blob_id" => id, "size" => 5, "expires_at" => exp} = up
      {:ok, exp, _} = DateTime.from_iso8601(exp)
      assert DateTime.diff(exp, DateTime.utc_now(), :minute) in 1435..1440

      # Idempotent replay; the same id with another backup_id is a mismatch.
      assert {200, ^up} = upload(a.token, a1, params, "hello")

      assert {400, _} =
               upload(a.token, a1, %{params | "backup_id" => Ecto.UUID.generate()}, "hello")

      # Owner-only reads.
      assert {200, _} = api(:get, "/api/v1/blobs/#{id}", a.token)
      assert {404, _} = api(:get, "/api/v1/blobs/#{id}", b.token)
    end

    test "60 parts per hour, 200 per day", %{a: a, a1: a1} do
      now = DateTime.utc_now()

      rows =
        for i <- 1..60,
            do: %{
              id: Ecto.UUID.dump!(Ecto.UUID.generate()),
              owner: Ecto.UUID.dump!(a.user.id),
              purpose: "backup",
              size: 1,
              sha256: <<0::256>>,
              expires_at: DateTime.add(now, 3600, :second),
              inserted_at: DateTime.add(now, -i, :second)
            }

      Repo.insert_all("blobs", rows)

      assert {429, %{"error" => %{"code" => "rate_limited"}}} =
               upload(a.token, a1, part_params(Ecto.UUID.generate()), "x")
    end
  end

  ## §22.3 commit

  describe "POST /backups (§22.3)" do
    test "checks in order; idempotent replay", %{a: a, b: b, a1: a1} do
      parts = parts!(a, a1)
      body = commit_body(parts)

      assert {403, %{"error" => %{"code" => "invalid_device"}}} = commit(a, nil, body)
      assert {400, _} = commit(a, a1, Map.delete(body, "sha256"))
      assert {400, _} = commit(a, a1, %{body | "bk_id" => "short"})

      assert {409, %{"error" => %{"code" => "no_backup_key"}} = err} = commit(a, a1, body)
      assert err["error"]["message"] == "No backup key has been set up"

      key!(a, a1)
      other = Base.encode64(:crypto.strong_rand_bytes(8))

      assert {409, %{"error" => %{"code" => "backup_key_conflict", "bk_id" => @bk}}} =
               commit(a, a1, %{body | "bk_id" => other})

      # Parts: sizes must add up, each part must match the server's values and this backup.
      assert {400, _} = commit(a, a1, %{body | "size" => body["size"] + 1})
      [p1, p2] = elem(parts, 1)

      assert {400, _} =
               commit(a, a1, %{body | "parts" => [%{p1 | "sha256" => p2["sha256"]}, p2]})

      {_, [bp]} = parts!(b, device!(b), 1)
      assert {400, _} = commit(a, a1, %{body | "parts" => [p1, bp], "size" => 200})
      {_, [other_backup]} = parts!(a, a1, 1)
      assert {400, _} = commit(a, a1, %{body | "parts" => [p1, other_backup], "size" => 200})

      assert {201, %{"backup" => backup}} = commit(a, a1, body)
      assert backup["device_id"] == a1 and backup["current"] and backup["expires_at"] == nil
      assert backup["parts"] == [p1, p2]
      assert blob_expiry(p1["blob_id"]) == nil

      # A repeat of the same backup_id: 200, same body.
      assert {200, %{"backup" => ^backup}} = commit(a, a1, body)
    end

    test "the caps: more than 16 parts", %{a: a, a1: a1} do
      key!(a, a1)
      body = commit_body(parts!(a, a1, 17, 10))
      assert {413, %{"error" => %{"code" => "too_large"}}} = commit(a, a1, body)
    end

    test "6 committed backups per 24 h from Postgres", %{a: a, a1: a1} do
      key!(a, a1)
      now = DateTime.utc_now()

      for i <- 1..6 do
        Repo.insert!(%Backup{
          backup_id: Ecto.UUID.generate(),
          user_id: a.user.id,
          device_id: a1,
          created_at: now,
          uploaded_at: DateTime.add(now, -i * 60, :second),
          size: 1,
          sha256: "x",
          schema: 1,
          app_version: "x",
          bk_id: @bk,
          parts: [],
          current: false,
          expires_at: DateTime.add(now, 3600, :second)
        })
      end

      assert {429, %{"error" => %{"code" => "rate_limited"}}} =
               commit(a, a1, commit_body(parts!(a, a1)))
    end

    test "one backup device: mismatch, replace_device, a superseded device needs no flag",
         %{a: a, a1: a1} do
      key!(a, a1)
      backup!(a, a1)
      a2 = device!(a)
      body = commit_body(parts!(a, a2))

      # a1 is still alive (seen after a2 registered).
      :ok = RisiMe.MLS.record_instance(a.user.id, a1, nil, "0.3.0-test")

      assert {409, %{"error" => %{"code" => "backup_device_mismatch", "device_id" => ^a1}} = err} =
               commit(a, a2, body)

      assert Map.has_key?(err["error"], "device_name")

      assert {201, %{"backup" => %{"device_id" => ^a2}}} =
               commit(a, a2, %{body | "replace_device" => true})

      # a2 is now the backup device; a1 is refused, until a2 is superseded by a reinstall.
      assert {409, _} = commit(a, a1, commit_body(parts!(a, a1)))
      a3 = device!(a)
      past = DateTime.add(DateTime.utc_now(), -3600, :second)

      Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^a2),
        set: [last_seen_at: past]
      )

      Repo.update_all(from(i in "app_instances", where: i.instance_key == ^("device:" <> a2)),
        set: [last_seen_at: past]
      )

      assert {201, %{"backup" => %{"device_id" => ^a3}}} =
               commit(a, a3, commit_body(parts!(a, a3)))
    end

    test "retention: newest 2 current, replaced 7 days, at most 5 replaced", %{a: a, a1: a1} do
      key!(a, a1)
      b1 = backup!(a, a1)
      b2 = backup!(a, a1)
      b3 = backup!(a, a1)

      {200, %{"backups" => list, "key" => true, "quota" => quota}} =
        api(:get, "/api/v1/backups", a.token)

      assert Enum.map(list, & &1["backup_id"]) == [
               b3["backup_id"],
               b2["backup_id"],
               b1["backup_id"]
             ]

      assert Enum.map(list, & &1["current"]) == [true, true, false]
      old = List.last(list)
      {:ok, exp, _} = DateTime.from_iso8601(old["expires_at"])
      assert DateTime.diff(exp, DateTime.utc_now(), :hour) in 167..168
      assert blob_expiry(hd(old["parts"])["blob_id"]) |> DateTime.diff(exp, :second) |> abs() < 2

      # Replaced backups don't count against the quota.
      assert quota == %{"used" => 400, "limit" => 1536 * @mib}

      # Five replaced at most: the oldest goes at once.
      now = DateTime.utc_now()

      for i <- 1..5 do
        Repo.insert!(%Backup{
          backup_id: Ecto.UUID.generate(),
          user_id: a.user.id,
          device_id: a1,
          created_at: now,
          uploaded_at: DateTime.add(now, -86_400 - i * 60, :second),
          size: 1,
          sha256: "x",
          schema: 1,
          app_version: "x",
          bk_id: @bk,
          parts: [],
          current: false,
          expires_at: DateTime.add(now, 3600, :second)
        })
      end

      backup!(a, a1)
      assert Repo.aggregate(from(b in Backup, where: not b.current), :count) == 5
      assert Repo.aggregate(from(b in Backup, where: b.current), :count) == 2
      assert Repo.get(Backup, b1["backup_id"])
    end

    test "the quota counts current backups and unreferenced parts", %{a: a, a1: a1} do
      Application.put_env(:risime, :backup_quota, 350)
      key!(a, a1)
      backup!(a, a1)
      _unreferenced = parts!(a, a1, 1)

      assert {413, %{"error" => %{"code" => "quota_exceeded", "used" => 300, "limit" => 350}}} =
               upload(a.token, a1, part_params(Ecto.UUID.generate()), :binary.copy("x", 100))
    end
  end

  ## §22.3 backup_key

  describe "backup_key (§22.3)" do
    test "shapes, no silent key change, same bk_id replaces, 404 without", %{a: a, a1: a1} do
      assert {404, %{"error" => %{"code" => "no_backup_key"}}} =
               api(:get, "/api/v1/backup_key", a.token)

      assert {403, _} = api(:put, "/api/v1/backup_key", a.token, key_body(), nil)

      for bad <- [
            key_body(@bk, []),
            key_body(@bk, ["passphrase"]),
            key_body(@bk, ["recovery_key", "recovery_key"]),
            key_body("short"),
            %{key_body() | "v" => 2},
            put_in(key_body(), ["wraps", Access.at(0), "nonce"], Base.encode64("x")),
            put_in(key_body(), ["wraps", Access.at(0), "kdf", "alg"], "scrypt")
          ] do
        assert {400, _} = api(:put, "/api/v1/backup_key", a.token, bad, a1)
      end

      body = key_body(@bk, ["recovery_key", "passphrase"])
      assert {200, %{"backup_key" => key}} = api(:put, "/api/v1/backup_key", a.token, body, a1)
      assert Map.delete(key, "updated_at") == body
      assert {200, %{"backup_key" => ^key}} = api(:get, "/api/v1/backup_key", a.token)

      # A different BK without backups: replaced.
      other = Base.encode64(:crypto.strong_rand_bytes(8))
      assert {200, _} = api(:put, "/api/v1/backup_key", a.token, key_body(other), a1)
      key!(a, a1, @bk)
      backup!(a, a1)

      assert {409, %{"error" => %{"code" => "backup_key_conflict", "bk_id" => @bk}}} =
               api(:put, "/api/v1/backup_key", a.token, key_body(other), a1)

      # The same BK with a new recovery key replaces the record.
      assert {200, _} = api(:put, "/api/v1/backup_key", a.token, key_body(@bk), a1)
    end

    test "PUT 10 per day", %{a: a, a1: a1} do
      for _ <- 1..10, do: key!(a, a1)
      assert {429, _} = api(:put, "/api/v1/backup_key", a.token, key_body(), a1)
    end
  end

  ## DELETE, usage, sweep

  describe "DELETE /backups, usage and the sweep" do
    test "DELETE removes every backup, part file and the key record", %{a: a, a1: a1} do
      key!(a, a1)
      b = backup!(a, a1)
      {_bid, [loose]} = parts!(a, a1, 1)

      # Two parts of the backup and the unreferenced one.
      assert {200, %{"backup" => %{"used" => 300}}} = api(:get, "/api/v1/blobs/usage", a.token)
      assert {204, nil} = api(:delete, "/api/v1/backups", a.token)
      assert {204, nil} = api(:delete, "/api/v1/backups", a.token)

      assert {200, %{"backups" => [], "key" => false, "quota" => %{"used" => 0}}} =
               api(:get, "/api/v1/backups", a.token)

      for %{"blob_id" => id} <- [loose | b["parts"]] do
        assert {404, _} = api(:get, "/api/v1/blobs/#{id}", a.token)
      end

      assert {404, _} = api(:get, "/api/v1/backup_key", a.token)
    end

    test "the blob sweep removes expired replaced backups", %{a: a, a1: a1} do
      key!(a, a1)
      for _ <- 1..3, do: backup!(a, a1)
      later = DateTime.add(DateTime.utc_now(), 8, :day)
      Blobs.cleanup(later)
      assert Repo.aggregate(from(b in Backup, where: b.user_id == ^a.user.id), :count) == 2
      assert Backups.sweep(later) == 0
    end
  end
end
