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

  @doc """
  Switches push to `RisiMe.Push.Test` for the calling test (off again on exit). Use with
  `push_token/1`: pushes reach a test only through tokens it registered itself.
  """
  def test_push!(_ctx \\ %{}) do
    Application.put_env(:risime, :push_sender, RisiMe.Push.Test)
    ExUnit.Callbacks.on_exit(fn -> Application.put_env(:risime, :push_sender, nil) end)
    :ok
  end

  @doc """
  A push token unique to the calling test (`prefix-N`; prefixes `unregistered`/`retry` keep
  their `RisiMe.Push.Test` meaning). Its pushes arrive here as `{:push, token, payload}`; a
  late push from an earlier test can never match it.
  """
  def push_token(prefix \\ "fcm") do
    token = "#{prefix}-#{System.unique_integer([:positive])}"
    :ok = RisiMe.Push.Test.register(token)
    token
  end

  @doc "Makes two users friends (v1.6). Accepts users or `logged_in_user/1` maps."
  def befriend!(a, b), do: RisiMe.Social.make_friends!(id_of(a), id_of(b))

  defp id_of(%{user: %{id: id}}), do: id
  defp id_of(%{id: id}), do: id
  defp id_of(id) when is_binary(id), do: id
end
