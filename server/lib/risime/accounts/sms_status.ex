defmodule RisiMe.Accounts.SmsStatus do
  @moduledoc """
  SMS health for `/health` (`checks.sms`, decision 021) and the boot checks.

  `/health` never calls Notify.lk. With `SMS_MODE=notifylk` this process polls Notify.lk's
  status endpoint every 10 min. `check/0` returns one of:
  `ok | inactive | low_balance | error | demo_sender_blocked | log`. **The balance amount is
  never exposed;** it is logged (info), with a warning below `SMS_BALANCE_WARN` (default 100).
  `error` also covers the time before the first poll.

  At boot it warns while the NotifyDEMO guard blocks OTPs, and logs an error when
  `PHONE_VERIFICATION=required` but no SMS sender is available (a misconfiguration).
  """
  use GenServer

  require Logger

  alias RisiMe.Accounts.OtpSender
  alias RisiMe.Accounts.OtpSender.NotifyLk

  @key {__MODULE__, :status}
  @poll_every :timer.minutes(10)

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "The `checks.sms` value."
  def check do
    case Application.get_env(:risime, :sms_mode, :log) do
      :notifylk ->
        if NotifyLk.demo_blocked?(),
          do: "demo_sender_blocked",
          else: Atom.to_string(:persistent_term.get(@key, :error))

      _ ->
        "log"
    end
  end

  @doc "Polls Notify.lk now (tests and operators). Returns the new status atom."
  def poll_now, do: GenServer.call(__MODULE__, :poll, 30_000)

  @doc false
  def classify({:ok, %{active: false}}, _threshold), do: :inactive

  def classify({:ok, %{active: true, balance: balance}}, threshold)
      when is_number(balance) and balance < threshold,
      do: :low_balance

  def classify({:ok, %{active: true}}, _threshold), do: :ok
  def classify({:error, _}, _threshold), do: :error

  @impl true
  def init(:ok) do
    boot_checks()
    if Application.get_env(:risime, :sms_mode) == :notifylk, do: send(self(), :poll)
    {:ok, nil}
  end

  @impl true
  def handle_info(:poll, state) do
    poll()
    Process.send_after(self(), :poll, @poll_every)
    {:noreply, state}
  end

  @impl true
  def handle_call(:poll, _from, state), do: {:reply, poll(), state}

  defp poll do
    threshold = Application.get_env(:risime, :sms_balance_warn, 100)
    result = NotifyLk.status()

    case result do
      {:ok, %{balance: balance, active: active}} ->
        Logger.info("Notify.lk account: active=#{active} balance=#{inspect(balance)}")

        if is_number(balance) and balance < threshold,
          do: Logger.warning("Notify.lk balance is below SMS_BALANCE_WARN (#{threshold})")

      {:error, reason} ->
        Logger.warning("Notify.lk status check failed: #{reason}")
    end

    status = classify(result, threshold)
    :persistent_term.put(@key, status)
    status
  end

  @doc false
  def boot_checks do
    mode = Application.get_env(:risime, :sms_mode, :log)

    if mode == :notifylk do
      cond do
        not NotifyLk.configured?() ->
          Logger.error("SMS_MODE=notifylk but NOTIFYLK_USER_ID/API_KEY/SENDER_ID aren't all set")

        NotifyLk.demo_blocked?() ->
          Logger.warning("SMS OTP disabled until an approved sender ID is set (NotifyDEMO)")

        System.get_env("NOTIFYLK_ALLOW_DEMO_OTP") == "true" ->
          Logger.warning("NOTIFYLK_ALLOW_DEMO_OTP=true: OTPs may go out from the demo sender ID")

        true ->
          :ok
      end
    end

    if RisiMe.Accounts.phone_verification_required?() do
      case OtpSender.sms_sender() do
        {:ok, _} ->
          :ok

        {:error, reason} ->
          Logger.error(
            "PHONE_VERIFICATION=required but no SMS can be sent (#{reason}): " <>
              "every Keycloak user is locked out until this is fixed"
          )
      end
    end
  end
end
