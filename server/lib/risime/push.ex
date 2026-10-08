defmodule RisiMe.Push do
  @moduledoc """
  Data-only push wake-ups (contract v1.5 §8, decisions 026 and 028).

  A push carries exactly `#{inspect(%{"type" => "inbox", "v" => "1"})}` (or, v1.13 §16.8, the
  call wake-up `#{inspect(%{"type" => "call", "v" => "1"})}`): never a body, sender, phone, name
  or call id. The app syncs over its channel and builds the notification itself.

  Senders implement this behaviour: `RisiMe.Push.FCM` (`FCM_ENABLED=true`) and
  `RisiMe.Push.Test`. With no sender configured, push is off and nothing is sent; device
  registration still works.
  """

  @payload %{"type" => "inbox", "v" => "1"}
  # v1.13 §16.8: the call wake-up, device-level, content-free (no caller, no call_id).
  @call_payload %{"type" => "call", "v" => "1"}

  @doc "Sends the wake-up to one push token."
  @callback deliver(push_token :: String.t(), payload :: map) ::
              :ok | {:error, :unregistered | :retryable | :failed}

  @doc "The inbox wake-up payload (§8.2)."
  def payload, do: @payload

  @doc "The call wake-up payload (§16.8, `push_call.json`)."
  def call_payload, do: @call_payload

  @doc "The configured sender module, or nil when push is off."
  def sender, do: Application.get_env(:risime, :push_sender)

  @doc """
  Called for every stored inbox event of `user_id` (see `RisiMe.Push.Dispatcher`). `scope`
  `:tabs` (v1.24 §24.7: an Official event or a `chat_event`) wakes only `tabs` devices.
  """
  defdelegate notify(user_id, scope \\ :all), to: RisiMe.Push.Dispatcher
end
