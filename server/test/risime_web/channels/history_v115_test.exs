defmodule RisiMeWeb.HistoryV115Test do
  @moduledoc """
  Contract v1.15 §17: history sharing between devices (the server side). Naming (own phase,
  dormant devices, member phase), the refresh path, accept races, the delivery deadline, the
  `history` blob purpose, durable limits, closes (reset, cancel, clear, removal) and the events.
  """
  use RisiMeWeb.ChannelCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query, only: [from: 2]
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers

  alias RisiMe.{Devices, History, MLS, Repo}
  alias RisiMe.History.{Candidate, Request}
  alias RisiMe.MLS.Wire
  alias RisiMe.Workers.HistoryTimer
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @caps ["groups", "history_share"]
  @cluster RisiMe.Messaging.Store.Cassandra.Cluster

  setup :with_attestation_key

  setup do
    [a, b, c, d] = for n <- ~w(Asha Bimal Chamari Dilan), do: logged_in_user(display_name: n)
    for x <- [b, c, d], do: befriend!(a, x)
    clear_legacy!()

    a_old = device!(a, @caps, name: true)
    a_new = device!(a, @caps)
    b_dev = device!(b, @caps)
    c_dev = device!(c, @caps)
    d_dev = device!(d, ["groups"])
    # The old phone is still in use (seen after the new one registered): not superseded.
    touch!(a, a_old)

    %{
      a: a,
      b: b,
      c: c,
      d: d,
      a_old: a_old,
      a_new: a_new,
      b_dev: b_dev,
      c_dev: c_dev,
      d_dev: d_dev
    }
  end

  ## Helpers

  defp touch!(user, dev), do: :ok = MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")

  defp device!(user, caps, opts \\ []) do
    dev = Ecto.UUID.generate()

    token_id =
      if opts[:name],
        do:
          Repo.one(
            from t in RisiMe.Accounts.UserToken,
              where: t.user_id == ^user.user.id,
              limit: 1,
              select: t.id
          )

    {:ok, _} =
      Devices.register(
        user.user.id,
        dev,
        %{
          "platform" => "android",
          "mls" => %{
            "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
            "capabilities" => caps
          }
        },
        token_id
      )

    :ok = MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end

  # An active group of a (creator, device a_new) and `others`, all devices in the MLS group.
  defp group!(ctx, others \\ [:b, :c, :d]) do
    others = Enum.map(others, &ctx[&1])

    body = %{
      "client_group_id" => Ecto.UUID.generate(),
      "member_ids" => Enum.map(others, & &1.user.id)
    }

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", ctx.a.token, body, ctx.a_new) |> assert_status(201)

    members = [ctx.a.user.id | Enum.map(others, & &1.user.id)]

    api(
      :post,
      "/api/v1/mls/groups/#{id}/commit",
      ctx.a.token,
      create_commit(members, {ctx.a.user.id, ctx.a_new}),
      ctx.a_new
    )
    |> assert_status(200)

    # Devices were first seen two hours ago (they hold the history asked for).
    Repo.update_all("app_instances",
      set: [first_seen_at: DateTime.add(DateTime.utc_now(), -7200, :second)]
    )

    # Memberships began an hour ago (history to share).
    past = DateTime.add(DateTime.utc_now(), -3600, :second)

    Repo.update_all(from(i in "group_member_intervals", where: i.group_id == ^id),
      set: [active_from: past]
    )

    id
  end

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    chan
  end

  defp epoch(conv), do: MLS.group(conv).epoch

  defp ct(conv, rid, opts \\ []) do
    g = MLS.group(conv)
    epoch = Keyword.get(opts, :epoch, g.epoch)
    aad = Keyword.get(opts, :aad, Wire.history_aad(rid))
    gid = Keyword.get(opts, :group_id, "#{conv}##{g.generation}")
    body = Keyword.get(opts, :body, "ciphertext")
    Base.encode64(Wire.encode_private_message(gid, epoch, aad, "sender", body))
  end

  defp iso(dt), do: RisiMe.Messaging.iso(dt)

  defp req_payload(conv, opts \\ []) do
    rid = Keyword.get(opts, :rid, Ecto.UUID.generate())
    now = DateTime.utc_now()

    %{
      "request_id" => rid,
      "conversation_id" => conv,
      "sources" => Keyword.get(opts, :sources, "any"),
      "range" => %{
        "from" => iso(Keyword.get(opts, :from, DateTime.add(now, -1800, :second))),
        "to" => iso(Keyword.get(opts, :to, DateTime.add(now, -60, :second)))
      },
      "gap_count" => 12,
      "ciphertext" => Keyword.get(opts, :ciphertext, ct(conv, rid)),
      "generation" => MLS.group(conv).generation,
      "epoch" => epoch(conv)
    }
  end

  defp request!(chan, conv, opts \\ []) do
    p = req_payload(conv, opts)
    ref = push(chan, "history:request", p)
    assert_reply ref, :ok, reply
    {p["request_id"], reply}
  end

  defp push!(chan, event, payload) do
    ref = push(chan, event, payload)
    assert_reply ref, status, reply
    {status, reply}
  end

  defp req(rid), do: Repo.get!(Request, rid)
  defp rows(rid), do: Repo.all(from c in Candidate, where: c.request_id == ^rid)

  defp hist_events(user, kind, rid) do
    for e <- events(user.user.id, kind), e["data"]["request_id"] == rid, do: e["data"]
  end

  defp timer!(kind, rid, extra \\ %{}),
    do: perform_job(HistoryTimer, Map.merge(%{"kind" => kind, "request_id" => rid}, extra))

  defp to_member_phase!(rid), do: timer!("own_end", rid)

  defp respond!(chan, rid, decision, reason \\ nil),
    do:
      push!(chan, "history:respond", %{
        "request_id" => rid,
        "decision" => decision,
        "reason" => reason
      })

  defp deliver_payload(conv, rid, part, parts),
    do: %{
      "request_id" => rid,
      "ciphertext" => ct(conv, rid),
      "generation" => MLS.group(conv).generation,
      "epoch" => epoch(conv),
      "part" => part,
      "parts" => parts
    }

  defp upload(user, device, conv, rid, bytes, cbid \\ Ecto.UUID.generate()) do
    path =
      "/api/v1/blobs?purpose=history&conversation_id=#{URI.encode_www_form(conv)}" <>
        "&request_id=#{rid}&client_blob_id=#{cbid}"

    conn =
      Phoenix.ConnTest.build_conn()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> user.token)
      |> Plug.Conn.put_req_header("content-type", "application/octet-stream")
      |> Plug.Conn.put_req_header("content-length", Integer.to_string(byte_size(bytes)))

    conn = if device, do: Plug.Conn.put_req_header(conn, "x-device-id", device), else: conn
    conn = Phoenix.ConnTest.dispatch(conn, RisiMeWeb.Endpoint, :post, path, bytes)
    {conn.status, if(conn.resp_body == "", do: nil, else: Jason.decode!(conn.resp_body))}
  end

  defp blob_status(user, id) do
    conn =
      Phoenix.ConnTest.build_conn()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> user.token)
      |> Phoenix.ConnTest.dispatch(RisiMeWeb.Endpoint, :get, "/api/v1/blobs/#{id}")

    conn.status
  end

  # An accepted request: a (a_new) asks, members named, b accepts.
  defp accepted!(ctx) do
    conv = group!(ctx)
    chan_a = join!(ctx.a, ctx.a_new)
    {rid, _} = request!(chan_a, conv)
    to_member_phase!(rid)
    chan_b = join!(ctx.b, ctx.b_dev)
    assert {:ok, %{}} = respond!(chan_b, rid, "accept")
    %{conv: conv, rid: rid, chan_a: chan_a, chan_b: chan_b}
  end

  defp past(rid, field, seconds) do
    Repo.update_all(from(r in Request, where: r.request_id == ^rid),
      set: [{field, DateTime.add(DateTime.utc_now(), -seconds, :second)}]
    )
  end

  ## Requests and the own phase

  describe "history:request and the own phase (§17.4)" do
    test "every own candidate is named at once; the reply lists own devices", ctx do
      a_third = device!(ctx.a, @caps)
      touch!(ctx.a, ctx.a_old)
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, reply} = request!(chan, conv)

      assert reply.state == "searching"
      own = Enum.map(reply.own_devices, & &1.device_id) |> Enum.sort()
      assert own == Enum.sort([ctx.a_old, a_third])
      old = Enum.find(reply.own_devices, &(&1.device_id == ctx.a_old))
      assert old.device_name == "test" and is_binary(old.last_seen_at) and old.online == false

      [ev] = hist_events(ctx.a, "history_request", rid)
      assert Enum.sort(ev["to_devices"]) == Enum.sort([ctx.a_old, a_third])
      assert ev["consent"] == "own" and ev["from"] == ctx.a.user.id
      assert ev["from_device"] == ctx.a_new and ev["epoch"] == epoch(conv)
      assert ev["intervals"] != [] and ev["gap_count"] == 12
      # Members aren't asked during the own phase.
      assert hist_events(ctx.b, "history_request", rid) == []

      assert_enqueued(worker: HistoryTimer, args: %{"kind" => "own_end", "request_id" => rid})
      assert_enqueued(worker: HistoryTimer, args: %{"kind" => "expire", "request_id" => rid})

      # Idempotent by request_id; a second request for the conversation is `request_open`.
      p = req_payload(conv, rid: rid)
      assert {:ok, %{request_id: ^rid, state: "searching"}} = push!(chan, "history:request", p)
      assert length(hist_events(ctx.a, "history_request", rid)) == 1

      assert {:error, %{reason: "request_open", request_id: ^rid}} =
               push!(chan, "history:request", req_payload(conv))
    end

    test "a superseded own device is dormant until its inbox joins", ctx do
      conv = group!(ctx)
      past = DateTime.add(DateTime.utc_now(), -7200, :second)

      Repo.update_all(from(d in Devices.Device, where: d.device_id == ^ctx.a_old),
        set: [last_seen_at: past]
      )

      Repo.update_all(
        from(i in "app_instances", where: i.instance_key == ^("device:" <> ctx.a_old)),
        set: [last_seen_at: past]
      )

      chan = join!(ctx.a, ctx.a_new)
      {rid, reply} = request!(chan, conv, sources: "own")
      assert [%{device_id: dev}] = reply.own_devices
      assert dev == ctx.a_old
      assert hist_events(ctx.a, "history_request", rid) == []
      assert [%{wait: "join"}] = rows(rid)

      join!(ctx.a, ctx.a_old)
      assert [ev] = hist_events(ctx.a, "history_request", rid)
      assert ev["to_devices"] == [ctx.a_old]
      assert req(rid).state == "searching"
    end

    test "a device first seen after range.to is never named", ctx do
      conv = group!(ctx)
      later = DateTime.add(DateTime.utc_now(), 3600, :second)

      Repo.update_all(
        from(i in "app_instances", where: i.instance_key == ^("device:" <> ctx.a_old)),
        set: [first_seen_at: later]
      )

      chan = join!(ctx.a, ctx.a_new)
      {rid, reply} = request!(chan, conv)
      assert reply.own_devices == []
      # No own candidate: the member phase starts at once.
      assert req(rid).phase == "member"
      assert [ev] = hist_events(ctx.b, "history_request", rid)
      assert ev["consent"] == "member"
    end

    test "who may request, and the range rules", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      now = DateTime.utc_now()

      # `from` older than 30 days is clipped, not refused.
      {rid, _} = request!(chan, conv, from: DateTime.add(now, -40, :day), sources: "own")
      r = req(rid)
      assert DateTime.diff(r.range_from, DateTime.add(now, -30, :day), :second) in -5..5
      assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => rid})

      for {from, to} <- [
            {DateTime.add(now, -40, :day), DateTime.add(now, -31, :day)},
            {DateTime.add(now, -60, :second), DateTime.add(now, -120, :second)},
            {now, DateTime.add(now, 600, :second)}
          ] do
        assert {:error, %{reason: "bad_request"}} =
                 push!(chan, "history:request", req_payload(conv, from: from, to: to))
      end

      # A device without the capability: bad_request; a device outside the group: not_member.
      plain = device!(ctx.a, ["groups"])
      chan_plain = join!(ctx.a, plain)

      assert {:error, %{reason: "not_member"}} =
               push!(chan_plain, "history:request", req_payload(conv))

      Repo.insert_all("mls_group_devices", [
        %{
          conversation_id: conv,
          user_id: Ecto.UUID.dump!(ctx.a.user.id),
          device_id: Ecto.UUID.dump!(plain)
        }
      ])

      assert {:error, %{reason: "bad_request"}} =
               push!(chan_plain, "history:request", req_payload(conv))

      # Not a member of the group.
      outsider = logged_in_user(display_name: "Out")
      o_dev = device!(outsider, @caps)
      chan_o = join!(outsider, o_dev)

      assert {:error, %{reason: "not_member"}} =
               push!(chan_o, "history:request", req_payload(conv))
    end

    test "the ciphertext checks; an 'H' AAD on msg:send and msg:delete is bad_request", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      rid = Ecto.UUID.generate()
      e = epoch(conv)

      bad = [
        {ct(conv, rid, aad: Wire.history_aad(Ecto.UUID.generate())), "bad_request"},
        {ct(conv, rid, aad: Wire.delete_aad([rid])), "bad_request"},
        {ct(conv, rid, aad: ""), "bad_request"},
        {ct(conv, rid, group_id: "#{conv}#9"), "bad_request"},
        {"not base64!", "bad_request"},
        {ct(conv, rid, body: :crypto.strong_rand_bytes(25 * 1024)), "too_long"}
      ]

      for {ciphertext, reason} <- bad do
        assert {:error, %{reason: ^reason}} =
                 push!(
                   chan,
                   "history:request",
                   req_payload(conv, rid: rid, ciphertext: ciphertext)
                 )
      end

      stale = %{req_payload(conv, rid: rid) | "epoch" => e - 1}
      assert {:error, %{reason: "stale_epoch"}} = push!(chan, "history:request", stale)

      assert {:error, %{reason: "bad_request"}} =
               push!(chan, "msg:send", %{
                 "client_msg_id" => Ecto.UUID.generate(),
                 "conversation_id" => conv,
                 "ciphertext" => ct(conv, rid),
                 "generation" => 1,
                 "epoch" => e
               })

      ref =
        push(chan, "msg:delete", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "conversation_id" => conv,
          "scope" => "everyone",
          "targets" => [RisiMe.TimeUUID.generate()],
          "ciphertext" => ct(conv, rid),
          "generation" => 1,
          "epoch" => e
        })

      assert_reply ref, :error, %{reason: "bad_request"}
    end
  end

  ## Escalation and members

  describe "member phase (§17.4)" do
    test "escalation after the own phase ends: members named with clipped intervals", ctx do
      conv = group!(ctx)
      # c joined the group 10 minutes ago: its overlap starts then.
      c_from = DateTime.add(DateTime.utc_now(), -600, :second)

      Repo.update_all(
        from(i in "group_member_intervals",
          where: i.group_id == ^conv and i.user_id == type(^ctx.c.user.id, :binary_id)
        ),
        set: [active_from: c_from]
      )

      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      to_member_phase!(rid)

      assert req(rid).state == "waiting_for_member"
      [b_ev] = hist_events(ctx.b, "history_request", rid)
      [c_ev] = hist_events(ctx.c, "history_request", rid)
      assert b_ev["consent"] == "member" and b_ev["to_devices"] == [ctx.b_dev]
      [%{"from" => from}] = c_ev["intervals"]
      {:ok, from, _} = DateTime.from_iso8601(from)
      assert abs(DateTime.diff(from, c_from, :second)) <= 1
      [%{"from" => b_from}] = b_ev["intervals"]
      assert b_from == b_ev["range"]["from"]
      # d has no `history_share` device: never named.
      assert hist_events(ctx.d, "history_request", rid) == []

      [status] = hist_events(ctx.a, "history_status", rid)
      assert status["state"] == "waiting_for_member" and status["provider"] == nil
      assert status["to_devices"] == [ctx.a_new]

      assert_enqueued(
        worker: HistoryTimer,
        args: %{"kind" => "member_window", "request_id" => rid, "user_id" => ctx.b.user.id}
      )
    end

    test "escalation when every own candidate declined, and on history:escalate", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      chan_old = join!(ctx.a, ctx.a_old)
      {rid, _} = request!(chan, conv)
      assert {:ok, %{}} = respond!(chan_old, rid, "decline")
      assert req(rid).phase == "member"
      assert [_] = hist_events(ctx.b, "history_request", rid)
      assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => rid})

      {rid2, _} = request!(chan, conv)
      assert {:ok, %{}} = push!(chan, "history:escalate", %{"request_id" => rid2})
      assert req(rid2).phase == "member"
      assert [_] = hist_events(ctx.c, "history_request", rid2)

      # The own device can still accept in the member phase, and wins.
      assert {:ok, %{}} = respond!(chan_old, rid2, "accept")
      r = req(rid2)
      assert r.state == "accepted" and r.provider_device == ctx.a_old

      assert [%{"reason" => "accepted_elsewhere"}] =
               hist_events(ctx.b, "history_request_closed", rid2)

      assert [%{"reason" => "accepted_elsewhere"}] =
               hist_events(ctx.c, "history_request_closed", rid2)

      accepted = hist_events(ctx.a, "history_status", rid2) |> List.last()
      assert accepted["provider"] == %{"user_id" => ctx.a.user.id, "device_id" => ctx.a_old}

      {rid3, _} = request!(join!(ctx.b, ctx.b_dev), conv, sources: "own")
      chan_b = join!(ctx.b, ctx.b_dev)

      assert {:error, %{reason: "bad_request"}} =
               push!(chan_b, "history:escalate", %{"request_id" => rid3})
    end

    test "up to 3 member users at a time; a decline or expiry names the next", ctx do
      e = logged_in_user(display_name: "Eranga")
      f = logged_in_user(display_name: "Fathima")
      befriend!(ctx.a, e)
      befriend!(ctx.a, f)
      e_dev = device!(e, @caps)
      f_dev = device!(f, @caps)
      ctx = Map.merge(ctx, %{e: e, f: f})
      conv = group!(ctx, [:b, :c, :d, :e, :f])
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      to_member_phase!(rid)

      named = fn ->
        rows(rid) |> Enum.filter(&(&1.phase == "member")) |> Enum.map(& &1.user_id)
      end

      assert length(named.()) == 3
      [left] = [ctx.b.user.id, ctx.c.user.id, e.user.id, f.user.id] -- named.()

      first = hd(named.())

      {u, dev} =
        Enum.find(
          [{ctx.b, ctx.b_dev}, {ctx.c, ctx.c_dev}, {e, e_dev}, {f, f_dev}],
          &(elem(&1, 0).user.id == first)
        )

      assert {:ok, %{}} = respond!(join!(u, dev), rid, "decline")
      assert left in named.()

      # Every member answered or expired → unavailable.
      for row <- rows(rid), row.phase == "member", row.answer == nil do
        Repo.update_all(
          from(c in Candidate, where: c.request_id == ^rid and c.user_id == ^row.user_id),
          set: [named_until: DateTime.add(DateTime.utc_now(), -1, :second)]
        )

        timer!("member_window", rid, %{"user_id" => row.user_id})
      end

      own = Enum.find(rows(rid), &(&1.phase == "own"))
      assert own.answer == nil
      assert {:ok, _} = respond!(join!(ctx.a, ctx.a_old), rid, "unable", "no_data")
      assert req(rid).state == "unavailable"
      statuses = hist_events(ctx.a, "history_status", rid) |> Enum.map(& &1["state"])
      assert List.last(statuses) == "unavailable"
      # Never who declined or who is being asked.
      for s <- hist_events(ctx.a, "history_status", rid), do: assert(s["provider"] == nil)

      assert [%{"reason" => "expired"}] =
               hist_events(f, "history_request_closed", rid) |> Enum.take(-1)
    end

    test "accept races: the first accept wins, the other gets gone", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      to_member_phase!(rid)
      chan_b = join!(ctx.b, ctx.b_dev)
      chan_c = join!(ctx.c, ctx.c_dev)

      results =
        [chan_b, chan_c]
        |> Enum.map(
          &push(&1, "history:respond", %{
            "request_id" => rid,
            "decision" => "accept",
            "reason" => nil
          })
        )
        |> Enum.map(fn ref ->
          assert_reply ref, status, reply
          {status, reply}
        end)

      assert Enum.count(results, &match?({:ok, _}, &1)) == 1
      assert Enum.count(results, &match?({:error, %{reason: "gone"}}, &1)) == 1
      r = req(rid)
      loser = if r.provider_user == ctx.b.user.id, do: ctx.c, else: ctx.b

      assert [%{"reason" => "accepted_elsewhere"}] =
               hist_events(loser, "history_request_closed", rid)

      # A device that wasn't named: gone.
      assert {:error, %{reason: "gone"}} = respond!(join!(ctx.d, ctx.d_dev), rid, "accept")
    end
  end

  ## Refresh

  describe "stale request ciphertexts (§17.4)" do
    test "epoch drift > 2 at naming time waits for history:refresh", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      Repo.update_all(from(g in "mls_groups", where: g.conversation_id == ^conv), inc: [epoch: 3])
      to_member_phase!(rid)

      assert req(rid).state == "refresh"
      assert hist_events(ctx.b, "history_request", rid) == []
      assert List.last(hist_events(ctx.a, "history_status", rid))["state"] == "refresh"

      # A refresh at the old epoch is stale; at the current one it names the waiting members.
      old = %{
        "request_id" => rid,
        "ciphertext" => ct(conv, rid, epoch: epoch(conv) - 3),
        "generation" => 1,
        "epoch" => epoch(conv) - 3
      }

      assert {:error, %{reason: "stale_epoch"}} = push!(chan, "history:refresh", old)

      new = %{
        "request_id" => rid,
        "ciphertext" => ct(conv, rid),
        "generation" => 1,
        "epoch" => epoch(conv)
      }

      assert {:ok, %{}} = push!(chan, "history:refresh", new)

      assert [ev] = hist_events(ctx.b, "history_request", rid)
      assert ev["epoch"] == epoch(conv)
      assert req(rid).state == "waiting_for_member"

      # Only the requesting device; gone for a closed request.
      assert {:error, %{reason: "gone"}} = push!(join!(ctx.a, ctx.a_old), "history:refresh", new)
      assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => rid})
      assert {:error, %{reason: "gone"}} = push!(chan, "history:refresh", new)
    end

    test "an `unable`/`stale` answer is not a decline: refresh, then named again", ctx do
      conv = group!(ctx, [:b])
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      to_member_phase!(rid)
      chan_b = join!(ctx.b, ctx.b_dev)
      assert {:ok, %{}} = respond!(chan_b, rid, "unable", "stale")
      assert req(rid).state == "refresh"
      # Until it is named again the device can't answer.
      assert {:error, %{reason: "gone"}} = respond!(chan_b, rid, "accept")

      new = %{
        "request_id" => rid,
        "ciphertext" => ct(conv, rid),
        "generation" => 1,
        "epoch" => epoch(conv)
      }

      assert {:ok, %{}} = push!(chan, "history:refresh", new)
      assert length(hist_events(ctx.b, "history_request", rid)) == 2
      assert {:ok, %{}} = respond!(chan_b, rid, "accept")
    end
  end

  ## Delivery, ack, blobs

  describe "delivery and the history blob (§17.4, §17.9)" do
    test "parts are delivered to the requesting device; every ack makes it done", ctx do
      %{conv: conv, rid: rid, chan_a: chan_a, chan_b: chan_b} = accepted!(ctx)

      {201, blob} = upload(ctx.b, ctx.b_dev, conv, rid, :crypto.strong_rand_bytes(1000))
      assert blob["expires_at"]
      {201, blob2} = upload(ctx.b, ctx.b_dev, conv, rid, :crypto.strong_rand_bytes(500))

      assert {:ok, %{}} = push!(chan_b, "history:deliver", deliver_payload(conv, rid, 1, 2))
      assert {:ok, %{}} = push!(chan_b, "history:deliver", deliver_payload(conv, rid, 1, 2))

      assert {:error, %{reason: "bad_request"}} =
               push!(chan_b, "history:deliver", deliver_payload(conv, rid, 2, 3))

      assert {:ok, %{}} = push!(chan_b, "history:deliver", deliver_payload(conv, rid, 2, 2))

      shares = hist_events(ctx.a, "history_share", rid)
      assert Enum.map(shares, & &1["part"]) == [1, 2]
      assert hd(shares)["to_devices"] == [ctx.a_new] and hd(shares)["from_device"] == ctx.b_dev
      assert req(rid).state == "receiving"

      # Readers: the requester's user (any of its devices), the uploader; not another member.
      assert blob_status(ctx.a, blob["blob_id"]) == 200
      assert blob_status(ctx.b, blob["blob_id"]) == 200
      assert blob_status(ctx.c, blob["blob_id"]) == 404

      ack = fn part ->
        push!(chan_a, "history:ack", %{
          "request_id" => rid,
          "part" => part,
          "result" => "imported"
        })
      end

      assert {:ok, %{}} = ack.(1)
      assert req(rid).state == "receiving"
      assert blob_status(ctx.a, blob2["blob_id"]) == 200
      assert {:ok, %{}} = ack.(2)
      assert req(rid).state == "done"
      assert {:ok, %{}} = ack.(2)

      # done: still readable for 1 h, then gone.
      assert blob_status(ctx.a, blob["blob_id"]) == 200

      [exp] =
        Repo.all(
          from b in "blobs",
            where: b.id == type(^blob["blob_id"], :binary_id),
            select: b.expires_at
        )

      assert NaiveDateTime.diff(exp, NaiveDateTime.utc_now(), :second) in 3500..3601
      assert [%{"reason" => "done"}] = hist_events(ctx.b, "history_request_closed", rid)
      assert List.last(hist_events(ctx.a, "history_status", rid))["state"] == "done"
      assert req(rid).request_ciphertext == nil
    end

    test "without an ack the blob lives 48 h; uploads only by the accepted device", ctx do
      %{conv: conv, rid: rid, chan_a: chan_a} = accepted!(ctx)
      {201, blob} = upload(ctx.b, ctx.b_dev, conv, rid, "abc")

      [exp] =
        Repo.all(
          from b in "blobs",
            where: b.id == type(^blob["blob_id"], :binary_id),
            select: b.expires_at
        )

      assert NaiveDateTime.diff(exp, NaiveDateTime.utc_now(), :hour) in 47..48

      # Another device of the provider, another member, the requester, no device: 404.
      b2 = device!(ctx.b, @caps)
      assert {404, _} = upload(ctx.b, b2, conv, rid, "abc")
      assert {404, _} = upload(ctx.c, ctx.c_dev, conv, rid, "abc")
      assert {404, _} = upload(ctx.a, ctx.a_new, conv, rid, "abc")
      assert {404, _} = upload(ctx.b, nil, conv, rid, "abc")
      assert {400, _} = upload(ctx.b, ctx.b_dev, conv, "nope", "abc")

      # At most 20 blobs per request.
      for _ <- 2..20, do: assert({201, _} = upload(ctx.b, ctx.b_dev, conv, rid, "x"))
      assert {400, _} = upload(ctx.b, ctx.b_dev, conv, rid, "x")

      {status, usage} = api(:get, "/api/v1/blobs/usage", ctx.b.token)

      assert status == 200 and usage["history"]["used"] == 3 + 19 and
               usage["history"]["hourly_limit"] == 40

      # A cancel closes it: the blob is gone at once (404 for everyone).
      assert {:ok, _} = push!(chan_a, "history:cancel", %{"request_id" => rid})
      assert blob_status(ctx.a, blob["blob_id"]) == 404
      assert {404, _} = upload(ctx.b, ctx.b_dev, conv, rid, "abc")
    end

    test "the delivery deadline drops a silent provider; naming resumes", ctx do
      %{rid: rid, chan_b: chan_b, conv: conv} = accepted!(ctx)
      assert [_] = hist_events(ctx.c, "history_request", rid)
      assert_enqueued(worker: HistoryTimer, args: %{"kind" => "delivery", "request_id" => rid})

      # Not yet: a no-op.
      timer!("delivery", rid)
      assert req(rid).state == "accepted"

      past(rid, :accepted_at, 31 * 60)
      timer!("delivery", rid)
      r = req(rid)
      assert r.state == "waiting_for_member" and r.provider_device == nil
      assert [%{"reason" => "expired"}] = hist_events(ctx.b, "history_request_closed", rid)
      # c was told accepted_elsewhere, and is named again.
      assert length(hist_events(ctx.c, "history_request", rid)) == 2

      assert {:error, %{reason: "gone"}} =
               push!(chan_b, "history:deliver", deliver_payload(conv, rid, 1, 1))

      assert {:ok, %{}} = respond!(join!(ctx.c, ctx.c_dev), rid, "accept")

      # A delivered part resets the deadline.
      chan_c = join!(ctx.c, ctx.c_dev)
      past(rid, :accepted_at, 31 * 60)
      assert {:ok, %{}} = push!(chan_c, "history:deliver", deliver_payload(conv, rid, 1, 2))
      timer!("delivery", rid)
      assert req(rid).state == "receiving"
    end
  end

  ## Closing

  describe "closing (§17.4)" do
    test "a reset closes requests (expired)", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      {:ok, _} = RisiMe.Groups.Commit.reset(ctx.a.user.id, ctx.a_new, conv, %{"generation" => 1})
      assert req(rid).state == "expired"
      assert List.last(hist_events(ctx.a, "history_status", rid))["state"] == "expired"
      assert [%{"reason" => "expired"}] = hist_events(ctx.a, "history_request_closed", rid)
    end

    test "chat:clear by the requester's user cancels; the 48 h expiry", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)

      assert {:ok, _} =
               push!(chan, "chat:clear", %{
                 "conversation_id" => conv,
                 "upto" => RisiMe.TimeUUID.generate()
               })

      assert req(rid).state == "cancelled"

      {rid2, _} = request!(chan, conv)
      timer!("expire", rid2)
      assert req(rid2).state == "searching"
      past(rid2, :expires_at, 1)
      timer!("expire", rid2)
      assert req(rid2).state == "expired"
    end

    test "removal: the requester leaving cancels; a removed member is un-named", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      to_member_phase!(rid)
      assert Enum.any?(rows(rid), &(&1.user_id == ctx.c.user.id and &1.answer == nil))

      {204, _} =
        api(
          :delete,
          "/api/v1/groups/#{conv}/members/#{ctx.c.user.id}",
          ctx.a.token,
          nil,
          ctx.a_new
        )

      assert Enum.all?(rows(rid), &(&1.user_id != ctx.c.user.id or &1.answer == "dropped"))
      assert {:error, %{reason: "gone"}} = respond!(join!(ctx.c, ctx.c_dev), rid, "accept")

      # A removed device's own request is cancelled.
      Devices.delete(ctx.a.user.id, ctx.a_new)
      assert req(rid).state == "cancelled"

      # The removed member can't request.
      assert {:error, %{reason: "not_member"}} =
               push!(join!(ctx.c, ctx.c_dev), "history:request", req_payload(conv))
    end

    test "losing the capability un-names a device; the provider's removal drops it", ctx do
      %{rid: rid} = accepted!(ctx)

      {:ok, _} =
        Devices.register(ctx.c.user.id, ctx.c_dev, %{
          "platform" => "android",
          "mls" => %{
            "signature_key" =>
              Base.encode64(Repo.get_by!(Devices.Device, device_id: ctx.c_dev).mls_signature_key),
            "capabilities" => ["groups"]
          }
        })

      Devices.delete(ctx.b.user.id, ctx.b_dev)
      r = req(rid)
      assert r.provider_device == nil and r.state != "accepted"
      assert Enum.find(rows(rid), &(&1.device_id == ctx.c_dev)).answer in ["dropped", "elsewhere"]
    end
  end

  ## DMs

  describe "DMs (§17.4)" do
    setup ctx do
      conv = RisiMe.Messaging.conversation_id(ctx.a.user.id, ctx.b.user.id)
      now = DateTime.utc_now()

      Repo.insert_all("mls_groups", [
        %{
          conversation_id: conv,
          generation: 1,
          epoch: 1,
          e2ee_since: DateTime.add(now, -3600, :second),
          updated_at: now
        }
      ])

      rows =
        for {u, d} <- [{ctx.a, ctx.a_new}, {ctx.a, ctx.a_old}, {ctx.b, ctx.b_dev}],
            do: %{
              conversation_id: conv,
              user_id: Ecto.UUID.dump!(u.user.id),
              device_id: Ecto.UUID.dump!(d)
            }

      Repo.insert_all("mls_group_devices", rows)
      Repo.update_all("app_instances", set: [first_seen_at: DateTime.add(now, -7200, :second)])
      %{dm: conv}
    end

    test "`own` needs no friendship; `any` with a block is not_member", ctx do
      RisiMe.Social.unfriend(ctx.a.user, ctx.b.user.id)
      chan = join!(ctx.a, ctx.a_new)
      {rid, reply} = request!(chan, ctx.dm, sources: "own")
      assert [%{device_id: dev}] = reply.own_devices
      assert dev == ctx.a_old
      assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => rid})

      assert {:error, %{reason: "not_member"}} =
               push!(chan, "history:request", req_payload(ctx.dm))

      befriend!(ctx.a, ctx.b)
      RisiMe.Social.block(ctx.b.user, ctx.a.user.id)

      assert {:error, %{reason: "not_member"}} =
               push!(chan, "history:request", req_payload(ctx.dm))
    end

    test "`any`: the partner is named after the own phase with the DM interval", ctx do
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, ctx.dm)
      to_member_phase!(rid)
      assert [ev] = hist_events(ctx.b, "history_request", rid)
      assert ev["consent"] == "member" and ev["intervals"] != []

      # A plaintext DM is not_e2ee.
      e = logged_in_user(display_name: "E")
      befriend!(ctx.a, e)
      plain = RisiMe.Messaging.conversation_id(ctx.a.user.id, e.user.id)

      assert {:error, %{reason: "not_e2ee"}} =
               push!(chan, "history:request", %{req_payload(ctx.dm) | "conversation_id" => plain})
    end
  end

  ## Limits

  describe "limits (§17.10)" do
    test "3 per conversation per 24 h, counted from Postgres (survives a restart)", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)

      for _ <- 1..3 do
        {rid, _} = request!(chan, conv, sources: "own")
        assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => rid})
      end

      :ets.delete_all_objects(RisiMe.RateLimiter)

      assert {:error, %{reason: "rate_limited"}} =
               push!(chan, "history:request", req_payload(conv))
    end

    test "30 per user per 24 h", ctx do
      conv = group!(ctx)
      now = DateTime.utc_now()

      for _ <- 1..30 do
        Repo.insert!(%Request{
          request_id: Ecto.UUID.generate(),
          requester_user: ctx.a.user.id,
          requester_device: ctx.a_old,
          conversation_id: "grp:" <> Ecto.UUID.generate(),
          generation: 1,
          range_from: now,
          range_to: now,
          gap_count: 1,
          sources: "own",
          state: "cancelled",
          phase: "own",
          request_epoch: 1,
          created_at: now,
          expires_at: now
        })
      end

      chan = join!(ctx.a, ctx.a_new)

      assert {:error, %{reason: "rate_limited"}} =
               push!(chan, "history:request", req_payload(conv))
    end

    test "a member is named at most twice per requester and conversation in 7 days, never within 24 h of a decline, 10 a day",
         ctx do
      conv = group!(ctx, [:b, :c])
      chan = join!(ctx.a, ctx.a_new)
      chan_b = join!(ctx.b, ctx.b_dev)

      {r1, _} = request!(chan, conv)
      to_member_phase!(r1)
      assert {:ok, _} = respond!(chan_b, r1, "decline")
      assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => r1})

      # Declined within 24 h: b isn't named; c is.
      {r2, _} = request!(chan, conv)
      to_member_phase!(r2)
      assert hist_events(ctx.b, "history_request", r2) == []
      assert [_] = hist_events(ctx.c, "history_request", r2)
      assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => r2})

      # c was named twice by a in this conversation (r1, r2): not a third time.
      Repo.delete_all("history_requests")
      {r3, _} = request!(chan, conv)

      for _ <- 1..2 do
        rid = Ecto.UUID.generate()
        now = DateTime.utc_now()

        Repo.insert!(%Request{
          request_id: rid,
          requester_user: ctx.a.user.id,
          requester_device: ctx.a_old,
          conversation_id: conv,
          generation: 1,
          range_from: now,
          range_to: now,
          gap_count: 1,
          sources: "any",
          state: "cancelled",
          phase: "member",
          request_epoch: 1,
          created_at: DateTime.add(now, -2, :day),
          expires_at: now
        })

        Repo.insert!(%Candidate{
          request_id: rid,
          device_id: ctx.c_dev,
          user_id: ctx.c.user.id,
          phase: "member",
          named_at: DateTime.add(now, -2, :day)
        })
      end

      to_member_phase!(r3)
      assert hist_events(ctx.c, "history_request", r3) == []
      assert [_] = hist_events(ctx.b, "history_request", r3)

      # 10 a day across all requesters.
      for _ <- 1..10 do
        rid = Ecto.UUID.generate()
        now = DateTime.utc_now()
        other = logged_in_user(display_name: "X")

        Repo.insert!(%Request{
          request_id: rid,
          requester_user: other.user.id,
          requester_device: Ecto.UUID.generate(),
          conversation_id: "grp:" <> Ecto.UUID.generate(),
          generation: 1,
          range_from: now,
          range_to: now,
          gap_count: 1,
          sources: "any",
          state: "cancelled",
          phase: "member",
          request_epoch: 1,
          created_at: now,
          expires_at: now
        })

        Repo.insert!(%Candidate{
          request_id: rid,
          device_id: ctx.b_dev,
          user_id: ctx.b.user.id,
          phase: "member",
          named_at: now
        })
      end

      assert {:ok, _} = push!(chan, "history:cancel", %{"request_id" => r3})
      Repo.delete_all(from r in Request, where: r.request_id == ^r3)
      {r4, _} = request!(chan, conv)
      to_member_phase!(r4)
      assert hist_events(ctx.b, "history_request", r4) == []
    end

    test "rows are pruned 8 days after creation", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      assert History.prune() == 0

      Repo.update_all(from(r in Request, where: r.request_id == ^rid),
        set: [created_at: DateTime.add(DateTime.utc_now(), -9, :day)]
      )

      assert :ok = perform_job(HistoryTimer, %{"kind" => "prune"})
      assert Repo.get(Request, rid) == nil and rows(rid) == []
    end
  end

  ## Events

  describe "events (§17.5)" do
    test "48 h TTL, never messages, and only for history_share sockets", ctx do
      conv = group!(ctx)
      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)

      {:ok, events, _} = RisiMe.Messaging.fetch_events(ctx.a.user.id, nil)
      ev = Enum.find(events, &(&1.kind == "history_request"))

      {:ok, page} =
        Xandra.Cluster.execute(
          @cluster,
          "SELECT TTL(payload) AS ttl FROM inbox_events WHERE user_id = ? AND event_id = ?",
          [{"uuid", ctx.a.user.id}, {"timeuuid", ev.event_id}]
        )

      [%{"ttl" => ttl}] = Enum.to_list(page)
      assert ttl in 172_700..172_800

      # Not a message: no index row; msg:delete of it is `gone` like any unknown target.
      assert RisiMe.Messaging.Store.impl().get_message(ev.event_id) == :not_found

      # A device without the capability never sees history_* events.
      plain = device!(ctx.a, ["groups"])
      {:ok, sock} = connect(UserSocket, %{"token" => ctx.a.token, "device_id" => plain})
      {:ok, reply, _} = subscribe_and_join(sock, InboxChannel, "inbox:" <> ctx.a.user.id, %{})
      refute Enum.any?(reply.events, &String.starts_with?(&1.kind, "history_"))
      assert Enum.any?(events, &(&1.kind == "history_request"))
      _ = rid
    end

    test "the inbox wake goes to named users only (content-free)", ctx do
      conv = group!(ctx)
      test_push!()
      token = push_token()

      {:ok, _} =
        Devices.register(ctx.b.user.id, ctx.b_dev, %{
          "platform" => "android",
          "push_token" => token,
          "mls" => %{
            "signature_key" =>
              Base.encode64(Repo.get_by!(Devices.Device, device_id: ctx.b_dev).mls_signature_key),
            "capabilities" => @caps
          }
        })

      chan = join!(ctx.a, ctx.a_new)
      {rid, _} = request!(chan, conv)
      refute_receive {:push, ^token, _}, 200
      to_member_phase!(rid)
      assert_receive {:push, ^token, %{"type" => "inbox", "v" => "1"}}, 2000
    end
  end
end
