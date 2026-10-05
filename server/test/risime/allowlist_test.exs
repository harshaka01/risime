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
end
