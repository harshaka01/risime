defmodule RisiMe.Groups.TwoTabsS1Test do
  @moduledoc """
  v1.24 §24.6 storage (server S1): the additive migration (every existing group becomes its
  chat's Private tab with `chat_id = id`, nothing else changes), the Risi seed, `PATCH /me` `tz`.
  """
  use RisiMeWeb.ConnCase, async: false

  import Ecto.Query
  import RisiMe.Fixtures

  alias RisiMe.Repo

  @version 20_261_010_100_000
  @migration Path.expand("../../../priv/repo/migrations/20261010100000_two_tabs.exs", __DIR__)

  setup_all do
    unless Code.ensure_loaded?(RisiMe.Repo.Migrations.TwoTabs), do: Code.require_file(@migration)
    :ok
  end

  describe "migration (hard rule 9)" do
    test "existing groups become Private with chat_id = id; every other column is unchanged" do
      a = logged_in_user(display_name: "A").user

      # Down, insert pre-v1.24 rows, up again: all inside the test's sandbox transaction.
      :ok =
        Ecto.Migrator.down(Repo, @version, RisiMe.Repo.Migrations.TwoTabs,
          log: false,
          migration_lock: false
        )

      refute "chat_id" in columns("groups")
      refute "kind" in columns("users")

      now = DateTime.utc_now()

      rows =
        for i <- 1..3 do
          %{
            id: "grp:" <> Ecto.UUID.generate(),
            created_by: Ecto.UUID.dump!(a.id),
            client_group_id: Ecto.UUID.dump!(Ecto.UUID.generate()),
            state: if(i == 3, do: "creating", else: "active"),
            generation: i,
            created_at: now
          }
        end

      Repo.insert_all("groups", rows)

      for r <- rows,
          do:
            Repo.insert_all("group_members", [
              %{
                group_id: r.id,
                user_id: Ecto.UUID.dump!(a.id),
                role: "admin",
                kind: "user",
                state: "active",
                inserted_at: now
              }
            ])

      before = snapshot(rows)
      members_before = Repo.aggregate("group_members", :count)

      :ok =
        Ecto.Migrator.up(Repo, @version, RisiMe.Repo.Migrations.TwoTabs,
          log: false,
          migration_lock: false
        )

      for r <- rows do
        g = Repo.get!(RisiMe.Groups.Group, r.id)
        assert g.chat_id == r.id
        assert g.tab == "private"
        assert g.chat_kind == "group"
      end

      # Nothing else changed: same rows, same values, same members; no chats rows.
      assert snapshot(rows) == before
      assert Repo.aggregate("group_members", :count) == members_before
      assert Repo.aggregate("chats", :count) == 0
      assert Repo.get!(RisiMe.Accounts.User, a.id).kind == "user"
      assert Repo.get!(RisiMe.Accounts.User, a.id).tz == nil
    end

    test "a group inserted without chat_id (pre-v1.24 code) gets chat_id = id" do
      a = logged_in_user(display_name: "A").user
      id = "grp:" <> Ecto.UUID.generate()

      Repo.insert!(%RisiMe.Groups.Group{
        id: id,
        created_by: a.id,
        client_group_id: Ecto.UUID.generate(),
        state: "active",
        created_at: DateTime.utc_now()
      })

      assert %{chat_id: ^id, tab: "private", chat_kind: "group"} =
               Repo.get!(RisiMe.Groups.Group, id)
    end

    test "the (chat_id, tab) index allows one Private and one Official per chat" do
      a = logged_in_user(display_name: "A").user
      chat = "grp:" <> Ecto.UUID.generate()
      insert_group(a.id, chat, chat, "private")
      insert_group(a.id, "grp:" <> Ecto.UUID.generate(), chat, "official")

      assert_raise Postgrex.Error, ~r/unique/, fn ->
        insert_group(a.id, "grp:" <> Ecto.UUID.generate(), chat, "official")
      end
    end

    test "POST /groups writes chat_id = id, tab private, chat_kind group" do
      a = logged_in_user(display_name: "A")
      b = logged_in_user(display_name: "B")
      befriend!(a, b)
      RisiMe.MLSHelpers.with_attestation_key()
      a_dev = RisiMe.GroupHelpers.groups_device!(a)
      RisiMe.GroupHelpers.groups_device!(b)
      RisiMe.GroupHelpers.clear_legacy!()

      {201, %{"group" => %{"id" => id}}} =
        RisiMe.GroupHelpers.api(
          :post,
          "/api/v1/groups",
          a.token,
          %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]},
          a_dev
        )

      g = Repo.get!(RisiMe.Groups.Group, id)
      assert {g.chat_id, g.tab, g.chat_kind} == {id, "private", "group"}
    end
  end

  describe "Risi seed" do
    test "seeds nothing while RISI is off" do
      Application.put_env(:risime, :risi, false)
      assert RisiMe.Risi.seed() == :off
      refute Repo.get(RisiMe.Accounts.User, RisiMe.Risi.user_id())
    end

    test "seeds the agent user and device while RISI is on (idempotent)" do
      Application.put_env(:risime, :risi, true)
      on_exit(fn -> Application.delete_env(:risime, :risi) end)

      assert RisiMe.Risi.seed() == :ok
      assert RisiMe.Risi.seed() == :ok
      u = Repo.get!(RisiMe.Accounts.User, RisiMe.Risi.user_id())
      assert {u.kind, u.display_name} == {"agent", "Risi"}
      assert RisiMe.Risi.agent?(u.id)

      [d] = Repo.all(from d in RisiMe.Devices.Device, where: d.user_id == ^u.id)
      assert d.device_id == RisiMe.Risi.device_id()
      assert d.capabilities == ["groups", "tabs"]
      # No MLS key yet (the agent tree registers it): not available.
      refute RisiMe.Risi.available?()

      # The 60-day prune never removes it.
      RisiMe.Devices.prune(DateTime.add(DateTime.utc_now(), 400, :day))
      assert Repo.get_by(RisiMe.Devices.Device, device_id: RisiMe.Risi.device_id())
    end
  end

  describe "PATCH /me tz (§24.11)" do
    test "sets a known zone, rejects an unknown one, keeps display_name", %{conn: conn} do
      a = logged_in_user(display_name: "Harsha")
      auth = fn -> put_req_header(conn, "authorization", "Bearer " <> a.token) end

      body = auth.() |> patch("/api/v1/me", %{"tz" => "Asia/Colombo"}) |> json_response(200)
      assert body["user"]["tz"] == "Asia/Colombo"
      assert body["user"]["display_name"] == "Harsha"
      assert Repo.get!(RisiMe.Accounts.User, a.user.id).tz == "Asia/Colombo"

      for bad <- ["Mars/Olympus", "../etc/passwd", "", 5] do
        e = auth.() |> patch("/api/v1/me", %{"tz" => bad}) |> json_response(422)
        assert e["error"]["code"] == "bad_request"
      end

      assert auth.() |> get("/api/v1/me") |> json_response(200) |> get_in(["user", "tz"]) ==
               "Asia/Colombo"

      body = auth.() |> patch("/api/v1/me", %{"display_name" => "H"}) |> json_response(200)
      assert body["user"]["display_name"] == "H" and body["user"]["tz"] == "Asia/Colombo"
    end
  end

  defp columns(table) do
    Repo.query!(
      "SELECT column_name FROM information_schema.columns WHERE table_name = $1",
      [table]
    ).rows
    |> List.flatten()
  end

  defp snapshot(rows) do
    ids = Enum.map(rows, & &1.id)

    Repo.all(
      from g in "groups",
        where: g.id in ^ids,
        order_by: g.id,
        select: {g.id, g.created_by, g.client_group_id, g.state, g.generation, g.created_at}
    )
  end

  defp insert_group(by, id, chat, tab) do
    Repo.insert_all(RisiMe.Groups.Group, [
      %{
        id: id,
        created_by: by,
        client_group_id: Ecto.UUID.generate(),
        state: "active",
        generation: 1,
        created_at: DateTime.utc_now(),
        chat_id: chat,
        tab: tab,
        chat_kind: "group"
      }
    ])
  end
end
