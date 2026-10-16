defmodule RisiRegistry.RegistryTest do
  use RisiRegistry.DataCase, async: false
  alias RisiRegistry.{Audit, HTTPStub, Registry}
  alias RisiRegistry.Cards.{Keys, Verifier}
  alias RisiRegistry.Registry.OwnerKey

  describe "registration record and status" do
    test "a new registration starts proposed and unverified" do
      {agent, _} = active_agent("agent.test", "new", approve: false)
      assert agent.status == "proposed"
      assert agent.verification == "unverified"
      assert agent.card_hash == nil
    end

    test "private_chats is never an allowed data class" do
      assert {:error, cs} =
               Registry.register(%{
                 agent_id: "x.test/a",
                 name: "A",
                 domain: "x.test",
                 card_url: "https://x.test/.well-known/agent-card.json",
                 tier: "listed",
                 data_classes: ["private_chats"]
               })

      assert %{data_classes: [_]} = errors_on(cs)
    end

    test "agent_id must start with the owner domain, and the card URL must be https" do
      assert {:error, cs} =
               Registry.register(%{
                 agent_id: "other.test/a",
                 name: "A",
                 domain: "x.test",
                 card_url: "http://x.test/card",
                 tier: "listed"
               })

      assert %{agent_id: _, card_url: _} = errors_on(cs)
    end

    test "an agent cannot be activated before its card is approved" do
      {agent, _} = active_agent("agent.test", "new", approve: false)

      assert {:error, :card_not_approved} =
               Registry.set_status(agent.agent_id, "active", "admin:test", "try")
    end

    test "transitions are limited and need who and why" do
      {agent, _} = active_agent()

      assert {:error, {:bad_transition, "active", "proposed"}} =
               Registry.set_status(agent.agent_id, "proposed", "admin:test", "x")

      assert {:error, :actor_and_reason_required} =
               Registry.set_status(agent.agent_id, "suspended", "", "")
    end
  end

  describe "card hash and signature" do
    test "the hash excludes the signature, so re-signing keeps it" do
      jwk1 = owner_key()
      jwk2 = owner_key()
      c = card()
      assert Verifier.card_hash(sign(c, jwk1, "k1")) == Verifier.card_hash(sign(c, jwk2, "k2"))
      assert Verifier.card_hash(c) == Verifier.card_hash(sign(c, jwk1, "k1"))
    end

    test "approval stores the hash, key id and the card's skills and scopes" do
      {agent, _} = active_agent()
      agent = Registry.get(agent.agent_id)
      assert agent.card_hash =~ ~r/\A[0-9a-f]{64}\z/
      assert agent.key_id == "k1"
      assert agent.verification == "verified"
      assert [%{"id" => "test.ask"}] = agent.skills
      assert [%{"label" => "Reads your test data"}] = agent.scopes
    end

    test "a card signed by a key the owner does not list is refused" do
      {agent, _} = active_agent("agent.test", "new", approve: false)
      publish_card("agent.test", sign(card(), owner_key(), "k1"))

      assert {:error, :bad_signature} =
               Registry.approve_card(agent.agent_id, "admin:test", "approve")
    end

    test "an unsigned card is refused" do
      {agent, _} = active_agent("agent.test", "new", approve: false)
      publish_card("agent.test", card())
      assert {:error, :no_signature} = Registry.approve_card(agent.agent_id, "admin:test", "x")
    end

    test "a disallowed alg is refused" do
      {agent, _jwk} = active_agent("agent.test", "new", approve: false)
      header = Base.url_encode64(~s({"alg":"HS256","kid":"k1"}), padding: false)
      forged = Map.put(card(), "signatures", [%{"protected" => header, "signature" => "AAAA"}])
      publish_card("agent.test", forged)

      assert {:error, :alg_not_allowed} =
               Registry.approve_card(agent.agent_id, "admin:test", "x")
    end
  end

  describe "owner keys with rotation" do
    test "a key set that publishes private material is refused" do
      jwk = owner_key()
      {_, private} = JOSE.JWK.to_map(jwk)
      publish_keys("leak.test", [Map.put(private, "kid", "k1")])
      assert {:error, :private_key_published} = Keys.refresh("leak.test")
    end

    test "re-signing with another listed key, content unchanged, stays verified" do
      {agent, jwk1} = active_agent()
      jwk2 = owner_key()

      publish_keys("agent.test", [public_entry(jwk1, "k1"), public_entry(jwk2, "k2")])
      publish_card("agent.test", sign(card(), jwk2, "k2"))
      RisiRegistry.Cards.Cache.clear()

      assert {:ok, %{verification: "verified"}} = Registry.check(agent.agent_id)
      assert Registry.get(agent.agent_id).key_id == "k2"
      assert [%{action: "key_rotated"}] = Audit.recent(action: "key_rotated")
    end

    test "keys dropped from the set are retired and no longer verify" do
      {agent, jwk1} = active_agent()
      jwk2 = owner_key()
      publish_keys("agent.test", [public_entry(jwk2, "k2")])
      {:ok, usable} = Keys.refresh("agent.test")
      assert Enum.map(usable, & &1.kid) == ["k2"]
      assert %OwnerKey{retired_at: %DateTime{}} = Repo.get_by(OwnerKey, kid: "k1")

      publish_card("agent.test", sign(card(), jwk1, "k1"))
      assert {:error, :unverified} = Registry.check(agent.agent_id)
    end

    test "an expired key does not verify" do
      {agent, jwk} = active_agent("agent.test", "new", approve: false)
      publish_keys("agent.test", [public_entry(jwk, "k1", "2020-01-01T00:00:00Z")])
      Keys.refresh("agent.test")

      assert {:error, :key_not_usable} =
               Registry.approve_card(agent.agent_id, "admin:test", "x")
    end
  end

  describe "the per-call check" do
    test "status is read every call; the card is cached for 5 minutes" do
      {agent, _} = active_agent()
      url = card_url("agent.test")
      RisiRegistry.Cards.Cache.clear()
      before = HTTPStub.calls_to(url)

      assert {:ok, %{card: :fresh}} = Registry.check(agent.agent_id)
      assert {:ok, %{card: :cached}} = Registry.check(agent.agent_id)
      assert HTTPStub.calls_to(url) == before + 1

      # Status is not cached: suspending takes effect on the very next check.
      {:ok, _} = Registry.suspend(agent.agent_id, "admin:test", "maintenance")
      assert {:error, {:not_active, "suspended"}} = Registry.check(agent.agent_id)
    end

    test "after the cache window the card is fetched again" do
      {agent, _} = active_agent()
      old = Application.get_env(:risi_registry, :registry)
      Application.put_env(:risi_registry, :registry, Keyword.put(old, :card_cache_ttl_ms, 0))
      on_exit(fn -> Application.put_env(:risi_registry, :registry, old) end)

      url = card_url("agent.test")
      before = HTTPStub.calls_to(url)
      assert {:ok, _} = Registry.check(agent.agent_id)
      assert {:ok, %{card: :fresh}} = Registry.check(agent.agent_id)
      assert HTTPStub.calls_to(url) == before + 2
    end

    test "a changed card drops the agent to unverified, with an audit row, and no call" do
      {agent, jwk} = active_agent()
      RisiRegistry.Cards.Cache.clear()

      changed =
        card(%{"x-risi" => %{"scopes" => [%{"id" => "read_all", "label" => "Reads everything"}]}})

      publish_card("agent.test", sign(changed, jwk, "k1"))

      assert {:error, :unverified} = Registry.check(agent.agent_id)
      assert Registry.get(agent.agent_id).verification == "unverified"

      assert [%{action: "card_unverified", result_code: "hash_mismatch"}] =
               Audit.recent(action: "card_unverified")

      # Stays unverified even if the old card comes back, until a person approves again.
      publish_card("agent.test", sign(card(), jwk, "k1"))
      assert {:error, :unverified} = Registry.check(agent.agent_id)
      assert {:ok, _} = Registry.approve_card(agent.agent_id, "admin:test", "reviewed")
      assert {:ok, _} = Registry.check(agent.agent_id)
    end

    test "an unreachable card fails the check but does not change the record" do
      {agent, _} = active_agent()
      RisiRegistry.Cards.Cache.clear()
      HTTPStub.put(card_url("agent.test"), {:error, :econnrefused})

      assert {:error, :unverified} = Registry.check(agent.agent_id)
      assert Registry.get(agent.agent_id).verification == "verified"
    end

    test "an oversized card is refused" do
      {agent, jwk} = active_agent()
      RisiRegistry.Cards.Cache.clear()
      big = card(%{"description" => String.duplicate("x", 70_000)})
      publish_card("agent.test", sign(big, jwk, "k1"))
      assert {:error, :unverified} = Registry.check(agent.agent_id)

      assert [%{result_code: "too_large"}] = Audit.recent(action: "card_unverified")
    end
  end

  describe "delisting" do
    test "one switch, an audit row with who and why, effective on the next check" do
      {agent, _} = active_agent()
      assert {:ok, _} = Registry.check(agent.agent_id)

      assert {:ok, %{status: "delisted"}} =
               Registry.delist(agent.agent_id, "admin:harsha", "owner asked")

      assert {:error, {:not_active, "delisted"}} = Registry.check(agent.agent_id)
      assert Registry.discover() == []

      assert [%{actor: "admin:harsha", reason: "owner asked", result_code: "active->delisted"}] =
               Audit.recent(action: "status_change", agent_id: agent.agent_id, limit: 1)
    end

    test "a delisted agent can only go back to proposed" do
      {agent, _} = active_agent()
      {:ok, _} = Registry.delist(agent.agent_id, "admin:test", "x")

      assert {:error, {:bad_transition, "delisted", "active"}} =
               Registry.set_status(agent.agent_id, "active", "admin:test", "x")

      assert {:ok, %{status: "proposed"}} =
               Registry.set_status(agent.agent_id, "proposed", "admin:test", "re-review")
    end
  end

  describe "discovery" do
    test "only active, verified agents, filtered by skill, tier and text" do
      {a, _} = active_agent("a.test", "good")
      {_b, _} = active_agent("b.test", "pending", approve: false)

      assert [%{agent_id: id}] = Registry.discover()
      assert id == a.agent_id
      assert [_] = Registry.discover(%{"skill" => "test.ask"})
      assert [] = Registry.discover(%{"skill" => "other"})
      assert [] = Registry.discover(%{"tier" => "official"})
      assert [_] = Registry.discover(%{"q" => "test agent"})
      assert [] = Registry.discover(%{"q" => "%"})
    end
  end

  defp errors_on(changeset) do
    Ecto.Changeset.traverse_errors(changeset, fn {msg, _} -> msg end)
  end
end
