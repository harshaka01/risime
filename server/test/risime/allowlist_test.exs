defmodule RisiMe.AllowlistTest do
  use RisiMe.DataCase, async: true

  alias RisiMe.Accounts

  @attrs %{phone: "+94770000101", email: "A@Example.com", display_name: "A", company: "CodeGen"}

  test "allow/1 inserts, normalises email and upserts by phone" do
    assert {:ok, e} = Accounts.allow(@attrs)
    assert e.email == "a@example.com"
    assert {:ok, e2} = Accounts.allow(%{@attrs | company: "Rise"})
    assert e2.id == e.id
    assert [%{company: "Rise"}] = Accounts.list_allowlist()
  end

  test "allow/1 rejects bad phone and email" do
    assert {:error, cs} = Accounts.allow(%{@attrs | phone: "0771234567", email: "nope"})
    assert %{phone: [_], email: [_]} = errors_on(cs)
  end

  test "an email can be on the allowlist only once, case-insensitively" do
    assert {:ok, _} = Accounts.allow(@attrs)

    assert {:error, cs} =
             Accounts.allow(%{@attrs | phone: "+94770000102", email: "a@EXAMPLE.com"})

    assert %{email: ["has already been taken"]} = errors_on(cs)
  end

  test "rebind/1 clears a user's Keycloak binding" do
    %{user: user} = RisiMe.Fixtures.logged_in_user()

    Repo.update_all(from(u in RisiMe.Accounts.User, where: u.id == ^user.id),
      set: [keycloak_sub: "sub-1"]
    )

    assert {:ok, "sub-1"} = Accounts.rebind(user.phone)
    assert Accounts.get_user(user.id).keycloak_sub == nil
    assert {:error, :not_found} = Accounts.rebind("+94770009999")
  end
end
