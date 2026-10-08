defmodule RisiMe.Agent.Mls.NifTest do
  # The Risi member client's NIF (contract v1.24 §24.11). Runs in the gate whenever
  # `scripts/build-mls-nif` has put the library (with the test-only NIFs) in priv/native;
  # skipped otherwise, so the gate needs no Rust.
  use ExUnit.Case, async: true

  alias RisiMe.Agent.Mls.Nif

  @moduletag :mls_nif

  unless Nif.loaded?() and Nif.test_peer_enabled() do
    @moduletag skip: "NIF not built: run scripts/build-mls-nif"
  end

  @seed :binary.copy(<<7>>, 32)

  # The database, as Elixir sees it: key => sealed value.
  defp persist(rows, journal) do
    Enum.reduce(journal, rows, fn
      {k, :delete}, acc -> Map.delete(acc, k)
      {k, v}, acc when is_binary(v) -> Map.put(acc, k, v)
    end)
  end

  defp device(user, dev, kek, jwk, agent?, rows \\ %{}) do
    fresh? = rows == %{}
    {:ok, h, j} = Nif.open(user, dev, [jwk], kek, Map.to_list(rows))
    rows = persist(rows, j)

    if fresh? do
      {:ok, pk, j} = Nif.signature_public_key(h)
      rows = persist(rows, j)
      {:ok, jws} = Nif.test_attest(@seed, user, dev, pk, agent?)
      {:ok, :ok, j} = Nif.set_attestation(h, jws)
      {h, persist(rows, j)}
    else
      {h, rows}
    end
  end

  defp gid, do: "grp:" <> Ecto.UUID.generate() <> "#1"

  defp meta(admin, tab, agents) do
    Jason.encode!(%{
      v: 1,
      name: "Site team",
      icon: nil,
      admins: [admin],
      tab: tab,
      chat_id: "grp:" <> Ecto.UUID.generate(),
      agents: agents
    })
  end

  setup do
    {:ok, jwk} = Nif.test_attestor_jwk(@seed)

    %{
      jwk: jwk,
      kek: :crypto.strong_rand_bytes(32),
      risi: Ecto.UUID.generate(),
      risi_dev: Ecto.UUID.generate(),
      alice: Ecto.UUID.generate()
    }
  end

  test "joins an Official group, decrypts, persists the journal and reloads", ctx do
    {risi, rows} = device(ctx.risi, ctx.risi_dev, ctx.kek, ctx.jwk, true)
    {alice, _} = device(ctx.alice, Ecto.UUID.generate(), ctx.kek, ctx.jwk, false)
    assert rows != %{}
    # Only sealed values reach the "database": no row holds the signature key in the clear.
    {:ok, pk, []} = Nif.signature_public_key(risi)
    refute Enum.any?(rows, fn {_k, v} -> :binary.match(v, pk) != :nomatch end)

    {:ok, kps, j} = Nif.generate_key_packages(risi, 2)
    rows = persist(rows, j)
    assert length(kps) == 2

    g = gid()
    meta = meta(ctx.alice, "official", [ctx.risi])
    {:ok, gc, _} = Nif.test_create_group(alice, g, [hd(kps)], meta)
    assert is_binary(gc.welcome)

    {:ok, joined, j} = Nif.join_from_welcome(risi, gc.welcome)
    rows = persist(rows, j)
    assert joined.group_id == g
    assert Enum.any?(joined.members, &(&1.user_id == ctx.risi and &1.kind == :agent))
    assert Enum.any?(joined.members, &(&1.user_id == ctx.alice and &1.kind == :user))

    {:ok, ct, _} = Nif.encrypt(alice, g, "ship it friday")
    {:ok, msg, j} = Nif.process_detailed(risi, g, ct)
    rows = persist(rows, j)
    assert %{type: :application, plaintext: "ship it friday", sender: %{user_id: user}} = msg
    assert user == ctx.alice
    {:ok, epoch, _} = Nif.epoch(risi, g)

    # Reload from the persisted rows only.
    {risi2, rows} = device(ctx.risi, ctx.risi_dev, ctx.kek, ctx.jwk, true, rows)
    assert {:ok, ^pk, _} = Nif.signature_public_key(risi2)
    assert {:ok, ^epoch, _} = Nif.epoch(risi2, g)

    {:ok, m, _} = Nif.group_meta(risi2, g)
    assert %{tab: :official, agents: [agent], admins: [admin], name: "Site team"} = m
    assert agent == ctx.risi and admin == ctx.alice

    {:ok, ct, _} = Nif.encrypt(alice, g, "after reload")
    assert {:ok, %{plaintext: "after reload"}, j} = Nif.process_detailed(risi2, g, ct)
    rows = persist(rows, j)

    {:ok, out, j} = Nif.encrypt(risi2, g, "noted")
    rows = persist(rows, j)

    assert {:ok, %{type: :application, plaintext: "noted"}, _} =
             Nif.process_detailed(alice, g, out)

    # Risi's self-update: accepted by the "server", processed by Alice.
    {:ok, su, j} = Nif.self_update(risi2, g)
    rows = persist(rows, j)
    {:ok, e, j} = Nif.commit_accepted(risi2, g)
    _rows = persist(rows, j)

    assert {:ok, %{epoch: ^e, applied: [%{type: :commit}]}, _} =
             Nif.process_commits(alice, g, [su.commit])
  end

  test "a tampered row fails open; a bad KEK is refused without echoing it", ctx do
    {_risi, rows} = device(ctx.risi, ctx.risi_dev, ctx.kek, ctx.jwk, true)
    [{k, v} | _] = Enum.sort(rows)
    <<first, rest::binary>> = v
    tampered = Map.put(rows, k, <<Bitwise.bxor(first, 1), rest::binary>>)

    assert {:error, {:tampered, _}} =
             Nif.open(ctx.risi, ctx.risi_dev, [ctx.jwk], ctx.kek, Map.to_list(tampered))

    assert {:error, {:tampered, _}} =
             Nif.open(
               ctx.risi,
               ctx.risi_dev,
               [ctx.jwk],
               :crypto.strong_rand_bytes(32),
               Map.to_list(rows)
             )

    assert {:error, {:bad_kek, msg}} = Nif.open(ctx.risi, ctx.risi_dev, [ctx.jwk], "short", [])
    refute msg =~ "short"
  end

  test "never joins a Private group (private_tab), and the refusal changes nothing", ctx do
    # A user-attested device: the Welcome itself is valid, so only the NIF's check refuses it.
    {bob, rows} = device(Ecto.UUID.generate(), Ecto.UUID.generate(), ctx.kek, ctx.jwk, false)
    {alice, _} = device(ctx.alice, Ecto.UUID.generate(), ctx.kek, ctx.jwk, false)
    {:ok, [kp], j} = Nif.generate_key_packages(bob, 1)
    _rows = persist(rows, j)

    g = gid()
    {:ok, gc, _} = Nif.test_create_group(alice, g, [kp], meta(ctx.alice, "private", []))
    assert {:error, {:private_tab, _}} = Nif.join_from_welcome(bob, gc.welcome)
    assert {:error, {:unknown_group, _}} = Nif.epoch(bob, g)
    assert {:ok, _, []} = Nif.signature_public_key(bob)
  end
end
