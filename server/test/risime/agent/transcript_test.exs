defmodule RisiMe.Agent.TranscriptTest do
  @moduledoc """
  v1.24 §24.12 (server S5): the sealed `risi_buffer` behind `RisiMe.Messaging.Store`, and when
  the agent tree may start. Needs no NIF.
  """
  use RisiMe.DataCase, async: false

  @moduletag capture_log: true

  alias RisiMe.Agent
  alias RisiMe.Agent.Transcript
  alias RisiMe.Messaging.Store
  alias RisiMe.{Risi, TimeUUID}

  setup do
    keys = [:risi, :risi_agent_check, :risi_mls_kek, :risi_data_key]
    old = for k <- keys, do: {k, Application.fetch_env(:risime, k)}

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end
    end)

    Application.put_env(:risime, :risi_data_key, Base.encode64(:crypto.strong_rand_bytes(32)))
    user = RisiMe.GroupHelpers.fast_user!()

    official =
      RisiMe.TabsHelpers.official_group!("grp:" <> Ecto.UUID.generate(), [{user.id, "admin"}])

    %{user: user, official: official}
  end

  defp msg(user, text),
    do: %{
      message_id: TimeUUID.generate(),
      sender_id: user.id,
      sender_device: nil,
      plaintext: text
    }

  test "put/list round trip; bodies are sealed and bound to their conversation and message",
       ctx do
    %{user: user, official: og} = ctx
    m1 = msg(user, "ship it friday")
    m2 = msg(user, "and the invoice")
    assert :ok = Transcript.put(og, m1)
    assert :ok = Transcript.put(og, m2)
    # Idempotent per message id.
    assert :ok = Transcript.put(og, m1)

    assert [%{plaintext: "ship it friday"}, %{plaintext: "and the invoice"}] = Transcript.list(og)
    assert [%{message_id: id2}] = Transcript.list(og, m1.message_id)
    assert id2 == m2.message_id

    rows = Store.impl().list_agent_messages(og, nil, 10)
    assert length(rows) == 2
    for r <- rows, do: assert(:binary.match(r.body, "ship it") == :nomatch)

    {:ok, key} = Agent.data_key()
    [r1 | _] = rows
    assert {:ok, _} = Transcript.open(key, og, r1.message_id, r1.body)
    # Moved to another message or conversation, or under another key: it fails to open.
    assert :error = Transcript.open(key, og, m2.message_id, r1.body)
    assert :error = Transcript.open(key, og <> "x", r1.message_id, r1.body)
    assert :error = Transcript.open(:crypto.strong_rand_bytes(32), og, r1.message_id, r1.body)
  end

  test "rows sealed under another RISI_DATA_KEY are skipped and dropped (P0 2026-10-08)", ctx do
    %{user: user, official: og} = ctx
    :ok = Transcript.put(og, msg(user, "old key"))
    Application.put_env(:risime, :risi_data_key, Base.encode64(:crypto.strong_rand_bytes(32)))
    :ok = Transcript.put(og, msg(user, "new key"))

    assert [%{plaintext: "new key"}] = Transcript.list(og)
    assert length(Store.impl().list_agent_messages(og, nil, 10)) == 1
  end

  test "§15 delete removes one row; purge removes the conversation's rows", ctx do
    %{user: user, official: og} = ctx
    [m1, m2, m3] = for t <- ~w(a b c), do: msg(user, t)
    for m <- [m1, m2, m3], do: :ok = Transcript.put(og, m)

    :ok = Transcript.delete(og, [m2.message_id])
    assert Enum.map(Transcript.list(og), & &1.plaintext) == ["a", "c"]

    :ok = Transcript.purge(og)
    assert Transcript.list(og) == []
    assert Store.impl().list_agent_messages(og, nil, 10) == []
  end

  test "a Private or DM conversation is never buffered", ctx do
    %{user: user} = ctx
    private = "grp:" <> Ecto.UUID.generate()

    Repo.insert!(%RisiMe.Groups.Group{
      id: private,
      created_by: user.id,
      client_group_id: Ecto.UUID.generate(),
      state: "active",
      generation: 1,
      created_at: DateTime.utc_now()
    })

    for conv <- [private, "dm:a_b", "grp:" <> Ecto.UUID.generate()] do
      assert {:error, :private_tab} = Transcript.put(conv, msg(user, "CANARY"))
      assert Store.impl().list_agent_messages(conv, nil, 10) == []
      refute Agent.official?(conv)
    end
  end

  test "the tree starts only with RISI=on, the NIF and both keys; otherwise Risi is unavailable" do
    Application.put_env(:risime, :risi, true)
    Application.delete_env(:risime, :risi_mls_kek)
    refute Agent.startable?()
    assert "RISI_MLS_KEK (base64, 32 bytes)" in Agent.missing()
    Application.put_env(:risime, :risi_mls_kek, Base.encode64("short"))
    refute Agent.startable?()

    Application.put_env(:risime, :risi_mls_kek, Base.encode64(:crypto.strong_rand_bytes(32)))
    assert Agent.startable?() == RisiMe.Agent.Mls.Nif.loaded?()
    Application.put_env(:risime, :risi, false)
    refute Agent.startable?()
    assert :risi_off in Agent.problems()

    # RISI=on but the tree not running: never available (§24.15 agent_unavailable).
    Application.put_env(:risime, :risi, true)
    Application.put_env(:risime, :risi_agent_check, true)
    refute Agent.running?()
    refute Risi.available?()
  end
end
