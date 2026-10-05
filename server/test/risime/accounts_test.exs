defmodule RisiMe.AccountsTest do
  use RisiMe.DataCase, async: true

  import Ecto.Query
  import RisiMe.Fixtures
  import Swoosh.TestAssertions

  alias RisiMe.Accounts
  alias RisiMe.Accounts.OtpChallenge

  describe "OTP" do
    test "happy path creates the user from the allowlist and returns a working token" do
      entry = allowlist_entry(company: "Rise")
      assert :ok = Accounts.request_otp(entry.phone, String.upcase(entry.email))
      code = receive_code()
      assert code =~ ~r/^\d{6}$/

      assert {:ok, token, user} = Accounts.verify_otp(entry.phone, code, "Pixel 8")
      assert user.phone == entry.phone and user.company == "Rise"
      assert {^user, _} = Accounts.fetch_by_token(token)

      # single use
      assert {:error, :invalid_code} = Accounts.verify_otp(entry.phone, code, "Pixel 8")
    end

    test "the code hash is stored, never the code" do
      entry = allowlist_entry()
      :ok = Accounts.request_otp(entry.phone, entry.email)
      code = receive_code()
      challenge = Repo.one!(from c in OtpChallenge, where: c.phone == ^entry.phone)
      refute challenge.code_hash =~ code
      assert byte_size(challenge.code_hash) == 32
    end

    test "wrong code" do
      entry = allowlist_entry()
      :ok = Accounts.request_otp(entry.phone, entry.email)
      code = receive_code()
      assert {:error, :invalid_code} = Accounts.verify_otp(entry.phone, wrong(code), nil)
      assert {:error, :invalid_code} = Accounts.verify_otp(entry.phone, nil, nil)
      assert {:ok, _, _} = Accounts.verify_otp(entry.phone, code, nil)
    end

    test "expiry" do
      entry = allowlist_entry()
      :ok = Accounts.request_otp(entry.phone, entry.email)
      code = receive_code()

      Repo.update_all(from(c in OtpChallenge, where: c.phone == ^entry.phone),
        set: [expires_at: DateTime.add(DateTime.utc_now(), -1, :second)]
      )

      assert {:error, :expired} = Accounts.verify_otp(entry.phone, code, nil)
    end

    test "attempt limit: after 5 wrong codes even the right one is refused" do
      entry = allowlist_entry()
      :ok = Accounts.request_otp(entry.phone, entry.email)
      code = receive_code()

      for _ <- 1..5,
          do: assert({:error, :invalid_code} = Accounts.verify_otp(entry.phone, wrong(code), nil))

      assert {:error, :too_many_attempts} = Accounts.verify_otp(entry.phone, code, nil)
    end

    test "a new request supersedes the old code" do
      entry = allowlist_entry()
      :ok = Accounts.request_otp(entry.phone, entry.email)
      old = receive_code()
      :ok = Accounts.request_otp(entry.phone, entry.email)
      new = receive_code()

      if old != new,
        do: assert({:error, :invalid_code} = Accounts.verify_otp(entry.phone, old, nil))

      assert {:ok, _, _} = Accounts.verify_otp(entry.phone, new, nil)
    end

    test "a non-allowlisted pair gets :ok but no email" do
      entry = allowlist_entry()
      assert :ok = Accounts.request_otp(entry.phone, "someone.else@example.com")
      assert :ok = Accounts.request_otp(unique_phone(), entry.email)
      assert_no_email_sent()
    end

    test "input validation" do
      assert {:error, :invalid_phone} = Accounts.request_otp("0771234567", "a@b.co")
      assert {:error, :invalid_phone} = Accounts.request_otp(nil, "a@b.co")
      assert {:error, :invalid_email} = Accounts.request_otp(unique_phone(), "nope")
    end

    test "rate limit: 3 requests per phone per 15 minutes" do
      phone = unique_phone()
      for _ <- 1..3, do: assert(:ok = Accounts.request_otp(phone, "x@example.com"))
      assert {:error, :rate_limited} = Accounts.request_otp(phone, "x@example.com")
    end
  end

  describe "tokens" do
    test "revoke" do
      %{token: token} = logged_in_user()
      {_user, record} = Accounts.fetch_by_token(token)
      assert :ok = Accounts.revoke_token(record)
      assert Accounts.fetch_by_token(token) == nil
    end

    test "the token is stored only as a sha256 hash" do
      %{token: token} = logged_in_user()
      {_user, record} = Accounts.fetch_by_token(token)
      assert record.token_hash == :crypto.hash(:sha256, token)
      assert {:ok, raw} = Base.url_decode64(token, padding: false)
      assert byte_size(raw) == 32
    end
  end

  describe "contacts" do
    test "other allowlisted people, registered or not, sorted by name" do
      %{user: me} = logged_in_user(display_name: "Mira")
      %{user: kamal} = logged_in_user(display_name: "kamal", company: "Rise")
      pending = allowlist_entry(display_name: "Zed")

      contacts = Accounts.list_contacts(me)
      names = Enum.map(contacts, & &1.display_name)
      assert "Mira" not in names
      assert Enum.sort_by(names, &String.downcase/1) == names

      assert %{user_id: id, registered: true, company: "Rise"} =
               Enum.find(contacts, &(&1.phone == kamal.phone))

      assert id == kamal.id

      assert %{user_id: nil, registered: false} =
               Enum.find(contacts, &(&1.phone == pending.phone))
    end
  end

  defp wrong(code),
    do:
      code
      |> String.to_integer()
      |> Kernel.+(1)
      |> rem(1_000_000)
      |> Integer.to_string()
      |> String.pad_leading(6, "0")
end
