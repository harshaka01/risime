defmodule RisiMe.AgentTreeTest do
  @moduledoc """
  v1.24 §24.11/§24.12 (server S5): the `RisiMe.Agent` tree as Risi's MLS member client, end to
  end over the real NIF. A human device is a NIF handle attested by the server like an app; it
  creates an Official group with Risi through the real REST + commit path. Skipped when the NIF
  (with the test-only NIFs) isn't built (`scripts/build-mls-nif`).
  """
  use RisiMeWeb.ChannelCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query
  import ExUnit.CaptureLog
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers, only: [tabs_device!: 1]
  import RisiMe.RisiHelpers, only: [fake_llm!: 1, llm_requests: 0, ref_of: 2]

  alias RisiMe.Agent.{ConversationSup, Inbox, KeyPackages, Mls, Transcript}
  alias RisiMe.Agent.Mls.Nif
  alias RisiMe.{Groups, Messaging, Repo, Risi, TimeUUID}
  alias RisiMe.Messaging.Store
  alias RisiMe.MLS.{Attestation, Wire}

  @moduletag :mls_nif

  unless Nif.loaded?() and Nif.test_peer_enabled() do
    @moduletag skip: "NIF not built: run scripts/build-mls-nif"
  end

  setup :with_attestation_key

  setup do
    # A fresh Risi per test: its Cassandra inbox outlives the SQL sandbox.
    env = [
      risi: true,
      risi_agent_check: true,
      risi_user_id: Ecto.UUID.generate(),
      risi_device_id: Ecto.UUID.generate(),
      risi_mls_kek: Base.encode64(:crypto.strong_rand_bytes(32)),
      risi_data_key: Base.encode64(:crypto.strong_rand_bytes(32)),
      risi_hold_ms: 300,
      risi_retry_ms: 50,
      risi_sender: RisiMe.Agent.Send
    ]

    old = for {k, _} <- env, do: {k, Application.fetch_env(:risime, k)}
    for {k, v} <- env, do: Application.put_env(:risime, k, v)

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end
    end)

    assert RisiMe.Agent.startable?()
    start_supervised!(RisiMe.Agent.Supervisor)
    eventually(fn -> Risi.available?() end)

    [a, b] = for n <- ~w(Harsha Kamal), do: logged_in_user(display_name: n)
    befriend!(a, b)
    {a_h, a_dev} = human!(a)
    b_dev = tabs_device!(b)
    clear_legacy!()

    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]}

    %{"group" => %{"id" => private}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    {200, _} =
      api(
        :post,
        "/api/v1/mls/groups/#{private}/commit",
        a.token,
        create_commit([a.user.id, b.user.id], {a.user.id, a_dev}),
        a_dev
      )

    %{
      a: a,
      b: b,
      a_h: a_h,
      a_dev: a_dev,
      b_dev: b_dev,
      private: private,
      risi: Risi.user_id(),
      risi_dev: Risi.device_id()
    }
  end

  ## Helpers

  defp eventually(fun, tries \\ 200) do
    cond do
      fun.() -> :ok
      tries == 0 -> flunk("condition never held")
      true -> Process.sleep(25) && eventually(fun, tries - 1)
    end
  end

  # A human device: a NIF handle whose key the server attests like an app's (PUT /me/devices).
  defp human!(user) do
    dev = Ecto.UUID.generate()
    anchors = Enum.map(Attestation.public_keys(), &Jason.encode!/1)
    {:ok, h, _} = Nif.open(user.user.id, dev, anchors, :crypto.strong_rand_bytes(32), [])
    {:ok, pk, _} = Nif.signature_public_key(h)

    {:ok, att} =
      RisiMe.Devices.register(user.user.id, dev, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(pk),
          "capabilities" => ["groups", "member_devices", "tabs"]
        }
      })

    :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    {:ok, :ok, _} = Nif.set_attestation(h, att)
    {h, dev}
  end

  # §24.2: POST …/official, the §12.5 claim of Risi's key package, and epoch 0 made by the human
  # handle with the real Welcome.
  defp start_official!(ctx) do
    %{a: a, a_h: a_h, a_dev: a_dev, private: private, risi: risi} = ctx

    %{"group" => %{"id" => og}} =
      api(:post, "/api/v1/chats/#{private}/official", a.token, %{}, a_dev)
      |> assert_status(201)

    %{"devices" => devices} =
      api(
        :post,
        "/api/v1/mls/key_packages/claim",
        a.token,
        %{"user_ids" => [risi], "conversation_id" => og},
        a_dev
      )
      |> assert_status(200)

    [%{"key_package" => kp}] = Enum.filter(devices, &(&1["user_id"] == risi))
    gid = RisiMe.Agent.group_id(og, 1)

    meta =
      Jason.encode!(%{
        v: 1,
        name: "Site team",
        icon: nil,
        admins: [a.user.id, ctx.b.user.id],
        tab: "official",
        chat_id: private,
        agents: [risi]
      })

    {:ok, gc, _} = Nif.test_create_group(a_h, gid, [Base.decode64!(kp)], meta)

    added =
      for %{user_id: u, device_id: d} <-
            Groups.groups_devices([a.user.id, ctx.b.user.id, risi], "tabs"),
          {u, d} != {a.user.id, a_dev},
          do: ref(u, d)

    {200, %{"epoch" => 1}} =
      api(
        :post,
        "/api/v1/mls/groups/#{og}/commit",
        a.token,
        create_commit([], {a.user.id, a_dev}, %{
          "added" => added,
          "commit" => Base.encode64(gc.commit),
          "welcome" => Base.encode64(gc.welcome)
        }),
        a_dev
      )

    eventually(fn -> Mls.epoch(gid) == {:ok, Groups.epoch(og)} end)
    {og, gid}
  end

  defp send_human!(ctx, og, gid, text) do
    pt = Jason.encode!(%{v: 1, type: "text", body: text})
    {:ok, ct, _} = Nif.encrypt(ctx.a_h, gid, pt)

    {:ok, %{message_id: id}} =
      Messaging.send(
        ctx.a.user.id,
        %{
          "conversation_id" => og,
          "client_msg_id" => Ecto.UUID.generate(),
          "ciphertext" => Base.encode64(ct),
          "generation" => 1,
          "epoch" => Groups.epoch(og)
        },
        device_id: ctx.a_dev
      )

    {id, pt}
  end

  defp risi_messages(user_id, risi) do
    for e <- events(user_id, "message"), e["data"]["from"] == risi, do: e
  end

  defp decrypt!(h, gid, event) do
    {:ok, %{type: :application, plaintext: pt}, _} =
      Nif.process_detailed(h, gid, Base.decode64!(event["data"]["ciphertext"]))

    Jason.decode!(pt)
  end

  ## Tests

  test "Risi registers an agent-attested device and keeps its key packages topped up", ctx do
    d = Repo.get_by!(RisiMe.Devices.Device, user_id: ctx.risi, device_id: ctx.risi_dev)
    assert d.capabilities == ["groups", "tabs"]
    [_, claims, _] = String.split(d.mls_attestation, ".")
    claims = claims |> Base.url_decode64!(padding: false) |> Jason.decode!()
    assert %{"kind" => "agent", "user_id" => u, "device_id" => dv} = claims
    assert {u, dv} == {ctx.risi, ctx.risi_dev}
    assert {:ok, pk} = Mls.call(&Nif.signature_public_key/1)
    assert d.mls_signature_key == pk

    assert {:ok, n} = RisiMe.MLS.key_package_count(ctx.risi, ctx.risi_dev)
    assert n >= 20

    # Every row in the database is sealed: the public key never appears in the clear.
    rows = Repo.all(from r in "risi_mls_kv", select: r.value)
    assert rows != []
    refute Enum.any?(rows, &(:binary.match(&1, pk) != :nomatch))

    # Claims consume packages; the refill tops them up again.
    Repo.delete_all(
      from k in "mls_key_packages",
        where: k.device_ref == type(^d.id, :binary_id) and not k.last_resort
    )

    assert KeyPackages.refill_now() >= 20
    assert {:ok, m} = RisiMe.MLS.key_package_count(ctx.risi, ctx.risi_dev)
    assert m >= 20
  end

  test "end to end: join from the Welcome, buffer sealed, reply, restart, §15 delete, Official off",
       ctx do
    {og, gid} = start_official!(ctx)
    risi = ctx.risi

    # A member's message is decrypted into the buffer, sealed at rest.
    {m1, pt1} = send_human!(ctx, og, gid, "ship it friday")
    eventually(fn -> Transcript.list(og) != [] end)
    assert [%{message_id: ^m1, sender_id: sender, plaintext: ^pt1}] = Transcript.list(og)
    assert sender == ctx.a.user.id
    [raw] = Store.impl().list_agent_messages(og, nil, 10)
    assert :binary.match(raw.body, "ship it friday") == :nomatch

    # Risi replies through the normal send path; the human device decrypts it.
    risi_obj = %{"v" => 1, "kind" => "answer", "call_ref" => nil, "notify" => []}
    assert {:ok, %{message_id: r1}} = RisiMe.Agent.Send.text(og, "noted", risi_obj)
    [ev] = risi_messages(ctx.a.user.id, risi)
    assert ev["data"]["message_id"] == r1
    assert ev["data"]["from_device"] == ctx.risi_dev

    assert %{"type" => "text", "body" => "noted", "risi" => ^risi_obj} =
             decrypt!(ctx.a_h, gid, ev)

    # Risi never buffers its own messages.
    Inbox.drain_now()
    assert length(Transcript.list(og)) == 1

    # A member's commit (a self-update) is applied in order.
    {:ok, su, _} = Nif.self_update(ctx.a_h, gid)

    {200, %{"epoch" => 2}} =
      api(
        :post,
        "/api/v1/mls/groups/#{og}/commit",
        ctx.a.token,
        %{
          "generation" => 1,
          "epoch" => 1,
          "commit" => Base.encode64(su.commit),
          "added" => [],
          "removed" => []
        },
        ctx.a_dev
      )

    {:ok, 2, _} = Nif.commit_accepted(ctx.a_h, gid)
    eventually(fn -> Mls.epoch(gid) == {:ok, 2} end)

    # Restart: kill the MLS owner; the tree reopens from the rows at the same epoch.
    {:ok, epoch} = Mls.epoch(gid)
    old = Process.whereis(Mls)
    Process.exit(old, :kill)
    eventually(fn -> Process.whereis(Mls) not in [nil, old] and RisiMe.Agent.running?() end)
    assert {:ok, ^epoch} = Mls.epoch(gid)
    {m2, pt2} = send_human!(ctx, og, gid, "after restart")
    eventually(fn -> length(Transcript.list(og)) == 2 end)
    assert %{plaintext: ^pt2} = Enum.find(Transcript.list(og), &(&1.message_id == m2))

    # §15: a delete for everyone removes the target's buffer row.
    {:ok, control, _} =
      Nif.encrypt(
        ctx.a_h,
        gid,
        Jason.encode!(%{v: 1, type: "delete", targets: [m1]}),
        Wire.delete_aad([m1])
      )

    assert {:ok, _} =
             Messaging.Deletes.delete(
               ctx.a.user.id,
               %{
                 "client_msg_id" => Ecto.UUID.generate(),
                 "conversation_id" => og,
                 "scope" => "everyone",
                 "targets" => [m1],
                 "ciphertext" => Base.encode64(control),
                 "generation" => 1,
                 "epoch" => Groups.epoch(og)
               },
               device_id: ctx.a_dev
             )

    assert [%{message_id: ^m2}] = Transcript.list(og)

    # §24.4 Official off: the farewell first (while Risi is still active), then the buffer is
    # emptied at once; when the removal commit lands, the MLS state goes too.
    {200, _} =
      api(:patch, "/api/v1/chats/#{ctx.private}", ctx.a.token, %{"official" => "off"}, ctx.a_dev)

    farewell = ctx.a.user.id |> risi_messages(risi) |> List.last()
    assert %{"type" => "text", "body" => body} = envelope = decrypt!(ctx.a_h, gid, farewell)
    assert body == "Official was turned off by Harsha. I've deleted what I learned in this chat."
    refute Map.has_key?(envelope, "risi")
    assert Groups.member(og, risi).state == "pending_remove"
    assert Transcript.list(og) == []
    assert Store.impl().list_agent_messages(og, nil, 10) == []

    [off] =
      for e <- events(ctx.a.user.id, "chat_event"), e["data"]["action"] == "official_off", do: e

    assert TimeUUID.unix_ms(farewell["event_id"]) <= TimeUUID.unix_ms(off["event_id"])

    [op] = for op <- Groups.Ops.list(og), op.type == "remove", do: op

    {200, _} =
      api(
        :post,
        "/api/v1/mls/groups/#{og}/commit",
        ctx.a.token,
        %{
          "generation" => 1,
          "epoch" => Groups.epoch(og),
          "commit" => b64(),
          "added" => [],
          "removed" => [ref(risi, ctx.risi_dev)],
          "op_id" => op.op_id
        },
        ctx.a_dev
      )

    eventually(fn -> match?({:error, {:unknown_group, _}}, Mls.epoch(gid)) end)
    assert Store.impl().list_agent_messages(og, nil, 10) == []
    eventually(fn -> Registry.lookup(RisiMe.Agent.Registry, og) == [] end)
  end

  test "Private never reaches the agent (canary), even when an event is forced into its inbox",
       ctx do
    canary = "CANARY-" <> Ecto.UUID.generate()
    fake_llm!(fn _, _ -> %{"commitments" => []} end)
    {og, _gid} = start_official!(ctx)

    log =
      capture_log(fn ->
        # The real path: Risi is not a member of the Private group, so nothing is delivered.
        {:ok, _} =
          Messaging.send(
            ctx.a.user.id,
            %{
              "conversation_id" => ctx.private,
              "client_msg_id" => Ecto.UUID.generate(),
              "ciphertext" => Base.encode64(canary),
              "generation" => 1,
              "epoch" => Groups.epoch(ctx.private)
            },
            device_id: ctx.a_dev
          )

        # A simulated server bug: a Private message and a Private Welcome forced into Risi's
        # inbox. The tree refuses both before any MLS call.
        {:ok, [kp]} = Mls.call(&Nif.generate_key_packages(&1, 1))
        pgid = RisiMe.Agent.group_id(ctx.private, 1)

        meta =
          Jason.encode!(%{
            v: 1,
            name: canary,
            icon: nil,
            admins: [ctx.a.user.id],
            tab: "official",
            chat_id: ctx.private,
            agents: [ctx.risi]
          })

        {:ok, gc, _} = Nif.test_create_group(ctx.a_h, pgid, [kp], meta)

        forced = [
          %{
            event_id: TimeUUID.generate(),
            kind: "mls_welcome",
            data: %{
              "conversation_id" => ctx.private,
              "generation" => 1,
              "epoch" => 1,
              "welcome" => Base.encode64(gc.welcome),
              "to_devices" => [ctx.risi_dev]
            }
          },
          %{
            event_id: TimeUUID.generate(),
            kind: "message",
            data: %{
              "message_id" => TimeUUID.generate(),
              "conversation_id" => ctx.private,
              "from" => ctx.a.user.id,
              "from_device" => ctx.a_dev,
              "ciphertext" => Base.encode64(canary),
              "generation" => 1,
              "epoch" => 1
            }
          }
        ]

        for e <- forced do
          :ok = Store.impl().append_event(ctx.risi, e)
          Messaging.broadcast(ctx.risi, e)
        end

        last = List.last(forced).event_id
        eventually(fn -> Inbox.drain_now() == last end)

        assert {:error, {:unknown_group, _}} = Mls.epoch(pgid)
        assert Store.impl().list_agent_messages(ctx.private, nil, 10) == []

        # Every entry point refuses a Private conversation on its own.
        assert {:error, :private_tab} = RisiMe.Agent.Send.text(ctx.private, canary)
        assert {:error, :private_tab} = ConversationSup.ensure(ctx.private)
        assert {:error, :private_tab} = ConversationSup.ensure(ctx.private <> "x")

        assert {:error, :private_tab} =
                 Transcript.put(ctx.private, %{
                   message_id: TimeUUID.generate(),
                   sender_id: ctx.a.user.id,
                   plaintext: canary
                 })

        assert Store.impl().list_agent_messages(ctx.private, nil, 10) == []
        refute RisiMe.Agent.may_act?(ctx.private)
      end)

    assert log =~ "refused"
    refute log =~ canary
    # Nothing of the Private chat reached the Official buffer either.
    refute Enum.any?(Transcript.list(og), &String.contains?(&1.plaintext, canary))

    # S6/S7: no Risi job names the Private conversation, and whatever jobs exist run without the
    # canary ever reaching the model or a Risi table.
    jobs = all_enqueued(worker: RisiMe.Workers.Risi)
    refute Enum.any?(jobs, &(&1.args["conv"] == ctx.private))
    for j <- jobs, do: perform_job(RisiMe.Workers.Risi, j.args)
    refute inspect(llm_requests()) =~ canary

    for t <- ~w(risi_commitments risi_facts risi_chat_state oban_jobs) do
      %{rows: rows} = Repo.query!("SELECT * FROM #{t}")
      refute inspect(rows) =~ canary
    end
  end

  test "S6/S7 end to end: a promise becomes a Risi card the members decrypt; ✓ over MLS confirms it",
       ctx do
    {og, gid} = start_official!(ctx)
    risi = ctx.risi
    line = "I'll send the revised quote to Kamal by Friday 5pm"

    fake_llm!(fn "commitments", body ->
      %{
        "commitments" => [
          %{
            "text" => "Send the revised quote",
            "owner" => ref_of(body, "Harsha"),
            "counterparts" => [ref_of(body, "Kamal")],
            "due_local" => "#{Date.add(Date.utc_today(), 3)}T17:00",
            "due_text" => "by Friday 5pm",
            "source" => [ref_of(body, line)],
            "confidence" => 0.92
          }
        ]
      }
    end)

    {m1, _} = send_human!(ctx, og, gid, line)

    eventually(fn ->
      match?([%{args: %{"kind" => "extract"}}], all_enqueued(worker: RisiMe.Workers.Risi))
    end)

    [job] = all_enqueued(worker: RisiMe.Workers.Risi)
    assert job.args == %{"kind" => "extract", "conv" => og}
    assert :ok = perform_job(RisiMe.Workers.Risi, job.args)

    # The card arrives as an ordinary MLS message from Risi's device.
    card_ev = ctx.a.user.id |> risi_messages(risi) |> List.last()
    assert card_ev["data"]["from_device"] == ctx.risi_dev
    assert %{"type" => "text", "body" => body, "risi" => card} = decrypt!(ctx.a_h, gid, card_ev)
    assert body =~ "Harsha will: Send the revised quote"
    assert card["kind"] == "commitment" and card["state"] == "proposed"
    assert card["owner"] == ctx.a.user.id and card["counterpart"] == [ctx.b.user.id]
    assert card["source_message_ids"] == [m1]
    assert is_binary(card["call_ref"])

    # ✓ from the owner, as an MLS application message (risi_action) in Official.
    action =
      Jason.encode!(%{
        v: 1,
        type: "risi_action",
        target: card["commitment_id"],
        action: "confirm",
        edit: nil
      })

    {:ok, ct, _} = Nif.encrypt(ctx.a_h, gid, action)

    {:ok, %{message_id: am}} =
      Messaging.send(
        ctx.a.user.id,
        %{
          "conversation_id" => og,
          "client_msg_id" => Ecto.UUID.generate(),
          "ciphertext" => Base.encode64(ct),
          "generation" => 1,
          "epoch" => Groups.epoch(og)
        },
        device_id: ctx.a_dev
      )

    args = %{"kind" => "action", "conv" => og, "message_id" => am, "user_id" => ctx.a.user.id}
    eventually(fn -> all_enqueued(worker: RisiMe.Workers.Risi, args: args) != [] end)
    assert :ok = perform_job(RisiMe.Workers.Risi, args)

    up_ev = ctx.a.user.id |> risi_messages(risi) |> List.last()
    assert %{"risi" => up} = decrypt!(ctx.a_h, gid, up_ev)
    assert up["kind"] == "commitment_update" and up["state"] == "confirmed"
    assert up["by"] == ctx.a.user.id
    assert %{state: "confirmed"} = Repo.get(RisiMe.Agent.Commitment, card["commitment_id"])

    # Official off: everything Risi derived from the chat is gone at once.
    {200, _} =
      api(:patch, "/api/v1/chats/#{ctx.private}", ctx.a.token, %{"official" => "off"}, ctx.a_dev)

    assert Repo.get(RisiMe.Agent.Commitment, card["commitment_id"]) == nil
    assert Repo.all(from f in RisiMe.Agent.Fact, where: f.conversation_id == ^og) == []
    assert Transcript.list(og) == []
  end

  test "a Welcome into another conversation's MLS group is purged at once (logged)", ctx do
    {og, _} = start_official!(ctx)
    # A second Official conversation (inserted directly) with Risi active in it.
    conv =
      RisiMe.TabsHelpers.official_group!(
        "grp:" <> Ecto.UUID.generate(),
        [{ctx.a.user.id, "admin"}],
        agents: [ctx.risi],
        leaves: [{ctx.a.user.id, ctx.a_dev}, {ctx.risi, ctx.risi_dev}]
      )

    # The Welcome is delivered for `conv` but its MLS group is named after a third id.
    {:ok, [kp]} = Mls.call(&Nif.generate_key_packages(&1, 1))
    other = RisiMe.Agent.group_id("grp:" <> Ecto.UUID.generate(), 1)

    meta =
      Jason.encode!(%{
        v: 1,
        name: "x",
        icon: nil,
        admins: [ctx.a.user.id],
        tab: "official",
        chat_id: conv,
        agents: [ctx.risi]
      })

    {:ok, gc, _} = Nif.test_create_group(ctx.a_h, other, [kp], meta)

    e = %{
      event_id: TimeUUID.generate(),
      kind: "mls_welcome",
      data: %{
        "conversation_id" => conv,
        "generation" => 1,
        "epoch" => 1,
        "welcome" => Base.encode64(gc.welcome),
        "to_devices" => [ctx.risi_dev]
      }
    }

    log =
      capture_log(fn ->
        :ok = Store.impl().append_event(ctx.risi, e)
        Messaging.broadcast(ctx.risi, e)
        eventually(fn -> Inbox.drain_now() == e.event_id end)
      end)

    assert log =~ "purged a Welcome for another group"
    assert {:error, {:unknown_group, _}} = Mls.epoch(other)
    assert {:error, {:unknown_group, _}} = Mls.epoch(RisiMe.Agent.group_id(conv, 1))
    # The real Official group is untouched.
    assert {:ok, _} = Mls.epoch(RisiMe.Agent.group_id(og, 1))
  end

  test "§12.8 catch-up: missed commits are fetched from the server's log before decrypting",
       ctx do
    {og, gid} = start_official!(ctx)
    :ok = :sys.suspend(Inbox)

    for epoch <- [1, 2] do
      {:ok, su, _} = Nif.self_update(ctx.a_h, gid)

      {200, _} =
        api(
          :post,
          "/api/v1/mls/groups/#{og}/commit",
          ctx.a.token,
          %{
            "generation" => 1,
            "epoch" => epoch,
            "commit" => Base.encode64(su.commit),
            "added" => [],
            "removed" => []
          },
          ctx.a_dev
        )

      {:ok, _, _} = Nif.commit_accepted(ctx.a_h, gid)
    end

    # Risi never sees the two mls_commit events, only the message at epoch 3.
    missed =
      for e <- events(ctx.risi, "mls_commit"), e["data"]["epoch"] in [1, 2], do: e["event_id"]

    assert length(missed) == 2
    :ok = Store.impl().delete_events(ctx.risi, missed)
    {m, pt} = send_human!(ctx, og, gid, "after two commits")
    :ok = :sys.resume(Inbox)
    send(Process.whereis(Inbox), {:inbox_event, :wake})

    eventually(fn -> Transcript.list(og) != [] end)
    assert [%{message_id: ^m, plaintext: ^pt}] = Transcript.list(og)
    assert {:ok, 3} = Mls.epoch(gid)
  end
end
