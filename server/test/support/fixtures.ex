defmodule RisiMe.Fixtures do
  @moduledoc "Test data: unique allowlist entries and logged-in users."

  import Bitwise

  alias RisiMe.Accounts

  @doc """
  A phone number unique within this VM: a random base drawn once per VM plus a monotonic
  counter (no per-call randomness, so two calls never collide before 10^7 draws).
  """
  def unique_phone do
    n = rem(vm_base(:phone) + next(), 10_000_000)
    "+9477" <> String.pad_leading(Integer.to_string(n), 7, "0")
  end

  @doc "A client IP (10.x.y.z) unique within this VM, for per-IP limits and auth-log matching."
  def unique_ip do
    n = rem(vm_base(:ip) + next(), 1 <<< 24)
    {10, n >>> 16 &&& 255, n >>> 8 &&& 255, n &&& 255}
  end

  defp next, do: System.unique_integer([:positive, :monotonic])

  @doc "Draws the per-VM random bases (test_helper.exs, before any test runs)."
  def init_bases! do
    for {kind, range} <- [phone: 10_000_000, ip: 1 <<< 24],
        do: :persistent_term.put({__MODULE__, :base, kind}, :rand.uniform(range) - 1)

    :ok
  end

  defp vm_base(kind), do: :persistent_term.get({__MODULE__, :base, kind})

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
