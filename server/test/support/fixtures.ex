defmodule RisiMe.Fixtures do
  @moduledoc "Test data: unique allowlist entries and logged-in users."

  alias RisiMe.Accounts

  def unique_phone do
    n = rem(System.unique_integer([:positive, :monotonic]) + :rand.uniform(1_000_000), 10_000_000)
    "+9477" <> String.pad_leading(Integer.to_string(n), 7, "0")
  end

  def allowlist_entry(attrs \\ %{}) do
    phone = attrs[:phone] || unique_phone()

    {:ok, entry} =
      Accounts.allow(
        Map.merge(
          %{
            phone: phone,
            email: "u#{String.trim_leading(phone, "+")}@example.com",
            display_name: "User #{phone}",
            company: "CodeGen"
          },
          Map.new(attrs)
        )
      )

    entry
  end

  @doc "Allowlists someone and logs them in through the OTP flow. Returns %{user, token, entry}."
  def logged_in_user(attrs \\ %{}) do
    entry = allowlist_entry(attrs)
    :ok = Accounts.request_otp(entry.phone, entry.email)
    code = receive_code()
    {:ok, token, user} = Accounts.verify_otp(entry.phone, code, "test")
    %{user: user, token: token, entry: entry}
  end

  @doc "Reads the code from the OTP email the Swoosh test adapter sent to this process."
  def receive_code do
    receive do
      {:email, %Swoosh.Email{subject: "Your RisiMe code: " <> code}} -> code
    after
      1000 -> raise "no OTP email received"
    end
  end
end
