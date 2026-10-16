defmodule RisiMe.Agent.CallSendTest do
  @moduledoc """
  Contract v1.55 §41.11 (decision 094): Risi sends Official messages on a voice yes during a Call
  Risi (`RisiMe.Agent.CallSend`). The gate's unit-level behaviour: "sent" only with a stored post,
  the guard (money / HR / legal / accusations / deletion keep the tap, with the model and with the
  backstop alone), Private / no Official never in reach, Undo inside and after 2 minutes, the
  `undo_send` action only from the caller's own leaf, a typed "undo that", and `voice_send` only
  while `RISI_CALL_SEND` is on for the user.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5]

  alias RisiMe.Agent.CallSend
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    restore_on_exit([
      :risi_agent_flags,
      :risi_dev_users,
      :risi_call_send_guard,
      :risi_sender,
      :risi_learning_log
    ])

    Application.put_env(:risime, :risi_learning_log, __MODULE__.Log)
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    nimali = RisiMe.Fixtures.logged_in_user(display_name: "Nimali Perera")
    RisiMe.Fixtures.befriend!(harsha, nimali)
    dm = RisiMe.Messaging.conversation_id(harsha.user.id, nimali.user.id)
    og = risi_chat!([harsha.user, nimali.user], chat_id: dm)
    rc = own_risi_chat!(harsha.user)
    Application.put_env(:risime, :risi_dev_users, [harsha.user.id])
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_CALL_SEND" => "canary"})
    %{harsha: harsha, nimali: nimali, dm: dm, og: og, rc: rc}
  end

  defmodule Log do
    def record(e) do
      if pid = Application.get_env(:risime, :risi_test_pid), do: send(pid, {:log, e})
      :ok
    end
  end

  defmodule NoAck do
    def text(_conv, _body, _risi), do: {:ok, %{conversation_id: "x"}}
    def delete(_conv, _ids), do: {:ok, %{}}
  end

  defp send!(ctx, text \\ "I called you a couple of times. Please call me back.") do
    CallSend.send(%{
      user_id: ctx.harsha.user.id,
      recipient_user_id: ctx.nimali.user.id,
      conv: ctx.og,
      name: "Nimali Perera",
      text: text,
      call_id: Ecto.UUID.generate(),
      request_id: Ecto.UUID.generate()
    })
  end

  describe "send" do
    test "Risi's own labelled post, then message_sent via risi with a 2-minute undo", ctx do
      assert {:ok, %{write_id: wid, message_id: mid, undo_until: until}} = send!(ctx)
      assert is_binary(mid)
      og = ctx.og
      rc = ctx.rc
      nid = ctx.nimali.user.id
      hid = ctx.harsha.user.id

      assert_receive {:risi_post, ^og, body, %{"kind" => "sent_for"} = r}

      assert body ==
               "I called you a couple of times. Please call me back.\n— Sent by Risi for Harsha"

      assert %{"on_behalf_of" => %{"user_id" => ^hid, "name" => "Harsha"}, "write_id" => ^wid} = r
      refute Map.has_key?(r, "for")
      assert r["notify"] == [nid] and Map.has_key?(r, "call_ref")
      refute Map.has_key?(r, "text")

      assert_receive {:risi_post, ^rc, line, %{"kind" => "message_sent"} = m}
      assert line == "✓ Sent to Nimali Perera (Official) by Risi · Undo"

      assert %{
               "tab" => "official",
               "state" => "sent",
               "via" => "risi",
               "write_id" => ^wid,
               "conversation_id" => ^og,
               "to" => %{"name" => "Nimali Perera", "user_id" => ^nid},
               "notify" => [^hid]
             } = m

      {:ok, u, 0} = DateTime.from_iso8601(m["undo_until"])
      {:ok, at, 0} = DateTime.from_iso8601(m["made_by"]["at"])
      assert DateTime.diff(u, at) in 119..120
      assert abs(DateTime.diff(until, u)) <= 1
      refute Map.has_key?(m, "text")

      assert_receive {:log, %{task: "call_send", meta: %{"outcome" => "sent"}} = l}
      refute inspect(l) =~ "couple of times"
    end

    test "no stored post (no message_id) → an error, no record, outcome declined", ctx do
      Application.put_env(:risime, :risi_sender, NoAck)
      assert {:error, :no_ack} = send!(ctx)
      refute_received {:risi_post, _, _, %{"kind" => "message_sent"}}
      assert_receive {:log, %{task: "call_send", meta: %{"outcome" => "declined"}}}
      assert RisiMe.Repo.aggregate("risi_call_sends", :count) == 0
    end

    test "a conversation Risi can't act in → nothing sent", ctx do
      private = ctx.dm

      assert {:error, _} =
               CallSend.send(%{user_id: ctx.harsha.user.id, conv: private, name: "N", text: "hi"})

      refute_received {:risi_post, _, _, _}
    end
  end

  describe "target" do
    test "the Official 1:1 of a friend; never Private; no friend → no_chat", ctx do
      og = ctx.og

      assert {:ok, %{conv: ^og, name: "Nimali Perera"}} =
               CallSend.target(ctx.harsha.user.id, ctx.nimali.user.id)

      # A friend whose 1:1 has no Official group (Private only).
      kasun = RisiMe.Fixtures.logged_in_user(display_name: "Kasun Senaratne")
      RisiMe.Fixtures.befriend!(ctx.harsha, kasun)
      assert {:error, :official_off} = CallSend.target(ctx.harsha.user.id, kasun.user.id)

      # A co-member who isn't a friend.
      ruwan = RisiMe.Fixtures.logged_in_user(display_name: "Ruwan")
      _ = risi_chat!([ctx.harsha.user, ruwan.user])
      assert {:error, :no_chat} = CallSend.target(ctx.harsha.user.id, ruwan.user.id)
    end

    test "an Official group Risi isn't a member of → official_off", ctx do
      kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
      RisiMe.Fixtures.befriend!(ctx.harsha, kamal)
      dm = RisiMe.Messaging.conversation_id(ctx.harsha.user.id, kamal.user.id)

      RisiMe.TabsHelpers.official_group!(dm, [
        {ctx.harsha.user.id, "admin"},
        {kamal.user.id, "member"}
      ])

      assert {:error, :official_off} = CallSend.target(ctx.harsha.user.id, kamal.user.id)
    end
  end

  describe "the guard (never by voice)" do
    @adversarial [
      "Please transfer Rs. 50,000 to the supplier today",
      "Can you pay the invoice for the printer?",
      "Your salary will be late this month",
      "We're going to fire Ruwan on Friday",
      "Please send me your resignation letter",
      "Our lawyer will call you about the contract",
      "The police are coming about the break-in",
      "You stole the client's files and lied about it",
      "I want to complain about your behaviour, it's unacceptable",
      "Delete all the files on the shared drive",
      "Remove Kamal's account from the system",
      "Send me the bank account number for the deposit"
    ]

    test "the backstop alone holds each one back" do
      for t <- @adversarial, do: assert(CallSend.backstop?(t), t)
    end

    test "with the model saying ok, the backstop still wins; with the model's label too" do
      Application.put_env(:risime, :risi_call_send_guard, fn _ -> "ok" end)
      for t <- @adversarial, do: assert(CallSend.needs_tap?(t), t)

      # Words the backstop doesn't know: the model's label decides.
      subtle = "Tell him he messed up the client meeting again"
      refute CallSend.backstop?(subtle)
      Application.put_env(:risime, :risi_call_send_guard, fn _ -> "accusation" end)
      assert CallSend.needs_tap?(subtle)
      Application.put_env(:risime, :risi_call_send_guard, fn _ -> "unclear" end)
      assert CallSend.needs_tap?(subtle)
      Application.put_env(:risime, :risi_call_send_guard, fn _ -> "something else" end)
      assert CallSend.needs_tap?(subtle)
    end

    test "everyday talk goes by voice; a failed model means the tap" do
      Application.put_env(:risime, :risi_call_send_guard, fn _ -> "ok" end)
      refute CallSend.needs_tap?("I called you a couple of times. Please call me back.")
      refute CallSend.needs_tap?("Running ten minutes late, see you at the gate")
      assert CallSend.needs_tap?("")

      Application.put_env(:risime, :risi_call_send_guard, fn _ -> raise "down" end)
      assert CallSend.needs_tap?("Running ten minutes late")
    end
  end

  describe "undo" do
    test "within 2 minutes: Risi deletes its post and posts undone; twice → expired", ctx do
      {:ok, %{write_id: wid, message_id: mid}} = send!(ctx)
      og = ctx.og
      rc = ctx.rc

      assert {:ok, %{name: "Nimali Perera"}} = CallSend.undo(ctx.harsha.user.id, wid)
      assert_receive {:risi_delete, ^og, [^mid]}
      assert_receive {:risi_post, ^rc, "Undone: the message to Nimali Perera was deleted.", m}

      assert %{"kind" => "message_sent", "state" => "undone", "via" => "risi", "write_id" => ^wid} =
               m

      refute Map.has_key?(m, "undo_until")
      assert_receive {:log, %{task: "call_send", meta: %{"outcome" => "undone"}}}

      assert {:error, :expired} = CallSend.undo(ctx.harsha.user.id, wid)
    end

    test "after 2 minutes → expired, nothing deleted; another user's write → none", ctx do
      {:ok, %{write_id: wid}} = send!(ctx)
      past = DateTime.add(DateTime.utc_now(), -1, :second)
      RisiMe.Repo.update_all("risi_call_sends", set: [undo_until: past])

      assert {:error, :expired} = CallSend.undo(ctx.harsha.user.id, wid)
      assert {:error, :expired} = CallSend.undo(ctx.harsha.user.id, :last)
      refute_received {:risi_delete, _, _}
      assert {:error, :none} = CallSend.undo(ctx.nimali.user.id, wid)
      refute CallSend.typed_undo?("undo that", ctx.harsha.user.id)
    end

    test "the undo_send action: only the caller's own leaf in their Risi chat", ctx do
      {:ok, %{write_id: wid}} = send!(ctx)
      env = %{"v" => 1, "type" => "risi_action", "action" => "undo_send", "target" => wid}

      # Another user (not the Risi chat's owner): ignored.
      assert :ok = CallSend.act(ctx.rc, ctx.nimali.user.id, env)
      # The right user in another conversation: ignored.
      assert :ok = CallSend.act(ctx.og, ctx.harsha.user.id, env)
      refute_received {:risi_delete, _, _}

      id = envelope!(ctx.rc, ctx.harsha.user, env)
      [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
      assert :ok = perform_job(Job, job.args)
      og = ctx.og
      assert_receive {:risi_delete, ^og, [_]}
    end

    test "a typed \"undo that\" only while a send is undoable; never a longer request", ctx do
      user = ctx.harsha.user.id
      refute CallSend.typed_undo?("undo that", user)
      {:ok, _} = send!(ctx)
      assert CallSend.typed_undo?("Undo that", user)
      assert CallSend.typed_undo?("Risi, undo that message please", user)
      assert CallSend.typed_undo?("unsend it", user)
      refute CallSend.typed_undo?("undo that and send Ruwan hello", user)
      refute CallSend.typed_undo?("cancel that", user)
    end
  end

  describe "typed undo in the Risi chat" do
    test "\"undo that\" as a Risi request deletes the post, with no model call", ctx do
      RisiMe.TabsHelpers.risi_tools_on!()
      {:ok, %{message_id: mid}} = send!(ctx)
      fake_llm!(fn _, _ -> {:status, 500} end)
      rid = Ecto.UUID.generate()

      envelope!(ctx.rc, ctx.harsha.user, %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => "undo that"
      })

      [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
      perform_job(Job, job.args)
      og = ctx.og
      assert_receive {:risi_delete, ^og, [^mid]}
      assert llm_requests() == []
    end
  end

  describe "the voice_send setting" do
    test "present only while RISI_CALL_SEND is on for the user; default true for canary users",
         ctx do
      h = ctx.harsha
      {200, body} = api(:get, "/api/v1/me/risi/voice", h.token, nil, nil)
      assert body["voice_send"] == true
      assert CallSend.enabled_for?(h.user.id)

      {200, body} =
        api(
          :put,
          "/api/v1/me/risi/voice",
          h.token,
          %{"transcribe" => true, "action_items" => true, "voice_send" => false},
          nil
        )

      assert body["voice_send"] == false
      refute CallSend.enabled_for?(h.user.id)

      # A user the canary doesn't cover: absent, and a PUT is ignored.
      n = ctx.nimali
      {200, body} = api(:get, "/api/v1/me/risi/voice", n.token, nil, nil)
      refute Map.has_key?(body, "voice_send")

      {200, _} =
        api(
          :put,
          "/api/v1/me/risi/voice",
          n.token,
          %{"transcribe" => true, "action_items" => true, "voice_send" => true},
          nil
        )

      refute CallSend.enabled_for?(n.user.id)

      # Switch on for everyone: present, default false for a non-canary user.
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_CALL_SEND" => "on"})
      assert CallSend.voice_send(ctx.nimali.user.id) == false
      {200, body} = api(:get, "/api/v1/me/risi/voice", n.token, nil, nil)
      assert body["voice_send"] == false

      # Switch off: absent for everyone (v1.54 byte-identical).
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_CALL_SEND" => "off"})
      {200, body} = api(:get, "/api/v1/me/risi/voice", h.token, nil, nil)
      refute Map.has_key?(body, "voice_send")
      refute CallSend.enabled_for?(h.user.id)
    end

    test "a non-boolean voice_send is a 422", ctx do
      assert {422, _} =
               api(
                 :put,
                 "/api/v1/me/risi/voice",
                 ctx.harsha.token,
                 %{"transcribe" => true, "action_items" => true, "voice_send" => "yes"},
                 nil
               )
    end
  end
end
