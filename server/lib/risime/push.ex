defmodule RisiMe.Push do
  @moduledoc """
  Data-only push wake-ups (contract v1.5 §8, decisions 026 and 027).

  A push carries exactly `#{inspect(%{"type" => "inbox", "v" => "1"})}`: never a body, sender,
  phone or name. The app syncs over its channel and builds the notification itself.

  Senders implement this behaviour: `RisiMe.Push.FCM` (`FCM_ENABLED=true`) and
  `RisiMe.Push.Test`. With no sender configured, push is off and nothing is sent; device
  registration still works.
  """

  @payload %{"type" => "inbox", "v" => "1"}

  @doc "Sends the wake-up to one push token."
  @callback deliver(push_token :: String.t(), payload :: map) ::
              :ok | {:error, :unregistered | :retryable | :failed}

  @doc "The only payload a push ever carries."
  def payload, do: @payload

  @doc "The configured sender module, or nil when push is off."
  def sender, do: Application.get_env(:risime, :push_sender)

  @doc "Called for every stored inbox event of `user_id` (see `RisiMe.Push.Dispatcher`)."
  defdelegate notify(user_id), to: RisiMe.Push.Dispatcher
end
