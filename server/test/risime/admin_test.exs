defmodule RisiMe.AdminTest do
  @moduledoc """
  Decision 089: admins in the database, the one-time seed, the append-only audit log and the
  runtime settings (table > env > default, 5-s cache).
  """
  use RisiMe.DataCase, async: false

  import RisiMe.Fixtures

  alias RisiMe.Accounts
  alias RisiMe.Admin
  alias RisiMe.Admin.{AuditEntry, Settings}

  setup do
    Settings.refresh()
    on_exit(&Settings.refresh/0)
    :ok
  end

  defp user(email) do
    entry = allowlist_entry(%{email: email})
    {:ok, user} = Accounts.map_identity(%{sub: Ecto.UUID.generate(), email: email})
    assert user.phone == entry.phone
    user
  end

  defp audits, do: Repo.all(from e in AuditEntry, order_by: e.id)

  describe "seed/1" do
    test "with no admin, the account with the seed email becomes admin (audit-logged)" do
      u = user("first.admin@example.com")
      refute Admin.admin?(u.id)

      assert {:ok, id} = Admin.seed("  First.Admin@Example.com ")
      assert id == u.id
      assert Admin.admin?(u.id)

      assert [%AuditEntry{actor_id: nil, action: "admin_seed", target: target}] = audits()
      assert target == "user:" <> u.id
    end

    test "does nothing once any admin exists, without a setting, or without a matching account" do
      assert Admin.seed(nil) == :noop
      assert Admin.seed("") == :noop
      assert Admin.seed("nobody@example.com") == :noop

      a = user("a@example.com")
      b = user("b@example.com")
      assert {:ok, _} = Admin.seed("a@example.com")
      assert Admin.seed("b@example.com") == :noop
      refute Admin.admin?(b.id)
      assert Admin.admin?(a.id)
    end

    test "a removed admin still counts: the seed never re-runs" do
      a = user("a2@example.com")
      b = user("b2@example.com")
      {:ok, _} = Admin.seed("a2@example.com")
      :ok = Admin.add_admin(b.id, a.id)
      :ok = Admin.remove_admin(a.id, b.id)
      refute Admin.admin?(a.id)
      assert Admin.seed("a2@example.com") == :noop
      refute Admin.admin?(a.id)
    end

    test "a disabled account is never seeded" do
      u = user("off@example.com")

      Repo.update_all(from(x in Accounts.User, where: x.id == ^u.id),
        set: [disabled_at: DateTime.utc_now()]
      )

      assert Admin.seed("off@example.com") == :noop
    end
  end

  describe "seed_signin/2" do
    setup do
      old = Application.get_env(:risime, :admin_seed_email)
      on_exit(fn -> Application.put_env(:risime, :admin_seed_email, old) end)
    end

    test "the signed-in Keycloak email equal to the setting makes the first admin" do
      u = user("seed@example.com")
      Application.put_env(:risime, :admin_seed_email, "SEED@example.com")
      Admin.seed_signin(u, "seed@example.com")
      assert Admin.admin?(u.id)
      assert [%AuditEntry{action: "admin_seed"}] = audits()
    end

    test "another email, or an existing admin, changes nothing" do
      a = user("a3@example.com")
      b = user("b3@example.com")
      Application.put_env(:risime, :admin_seed_email, "a3@example.com")
      Admin.seed_signin(b, "b3@example.com")
      refute Admin.admin?(b.id)

      Admin.seed_signin(a, "a3@example.com")
      assert Admin.admin?(a.id)

      Application.put_env(:risime, :admin_seed_email, "b3@example.com")
      Admin.seed_signin(b, "b3@example.com")
      refute Admin.admin?(b.id)
    end
  end

  describe "add/remove" do
    test "only an admin adds or removes; never the last admin; all audit-logged" do
      a = user("a4@example.com")
      b = user("b4@example.com")
      {:ok, _} = Admin.seed("a4@example.com")

      assert Admin.add_admin(a.id, b.id) == {:error, :forbidden}
      assert Admin.add_admin(b.id, a.id) == :ok
      assert Admin.add_admin(b.id, a.id) == {:error, :already_admin}
      assert Admin.remove_admin(b.id, a.id) == :ok
      assert Admin.remove_admin(a.id, a.id) == {:error, :last_admin}
      # Re-adding after a removal works (the old row is kept).
      assert Admin.add_admin(b.id, a.id) == :ok
      assert Admin.admin?(b.id)

      assert Enum.map(audits(), & &1.action) ==
               ["admin_seed", "admin_add", "admin_remove", "admin_add"]

      assert [_, _] = Admin.list_admins()
    end

    test "a disabled user is not an admin" do
      a = user("a5@example.com")
      {:ok, _} = Admin.seed("a5@example.com")

      Repo.update_all(from(x in Accounts.User, where: x.id == ^a.id),
        set: [disabled_at: DateTime.utc_now()]
      )

      refute Admin.admin?(a.id)
      refute Admin.admin?("not-a-uuid")
      refute Admin.admin?(nil)
    end
  end

  describe "audit log" do
    test "is append-only: UPDATE and DELETE are refused by the database" do
      :ok = Admin.audit(nil, "test_action", "target", "b", "a")

      assert_raise Postgrex.Error, ~r/append-only/, fn ->
        Repo.update_all(AuditEntry, set: [action: "changed"])
      end
    end

    test "delete is refused too" do
      :ok = Admin.audit(nil, "test_action", "target")

      assert_raise Postgrex.Error, ~r/append-only/, fn -> Repo.delete_all(AuditEntry) end
    end

    test "summaries are truncated to 500 characters" do
      :ok = Admin.audit(nil, "x", String.duplicate("t", 900))
      assert [%{target: t}] = audits()
      assert String.length(t) == 500
    end
  end

  describe "settings" do
    test "table > env > default, audit-logged, refreshed at once on this node" do
      a = user("a6@example.com")
      assert Settings.get("speech_route_si", "auto") == "auto"
      assert Settings.source("speech_route_si") == :default

      Application.put_env(:risime, :admin_settings_env, true)
      System.put_env("RISI_SPEECH_ROUTE_SI", "from-env")

      on_exit(fn ->
        System.delete_env("RISI_SPEECH_ROUTE_SI")
        Application.put_env(:risime, :admin_settings_env, false)
      end)

      assert Settings.get("speech_route_si", "auto") == "from-env"
      assert Settings.source("speech_route_si") == :env

      :ok = Settings.put("speech_route_si", "from-table", a.id)
      assert Settings.get("speech_route_si", "auto") == "from-table"
      assert Settings.source("speech_route_si") == :table

      :ok = Settings.clear("speech_route_si", a.id)
      assert Settings.get("speech_route_si", "auto") == "from-env"

      assert [
               %{action: "setting_set", target: "speech_route_si", before: "from-env"},
               %{action: "setting_clear", after: "(default)"}
             ] = audits()
    end
  end
end
