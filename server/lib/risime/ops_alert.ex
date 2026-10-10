defmodule RisiMe.OpsAlert do
  @moduledoc """
  Contract v1.32 §32: the pilot watchdog's alert, posted as a rule-made `ops_alert` Risi message
  into the own active Risi chat of each user whose phone is in `OPS_ALERT_PHONES`.

  Nothing here logs a token or a phone number. A user Risi can't reach (Risi off, no active Risi
  chat, a failed post) counts as `held`; the watchdog then sends its e-mail fallback.
  """
  require Logger

  alias RisiMe.Agent.{Clock, Out}
  alias RisiMe.{Repo, RisiChat}
  alias RisiMe.Accounts.User

  @states ~w(restarted rolled_back gave_up public_down recovered)
  @checks ~w(local public)
  @max_detail 300

  def states, do: @states

  @doc "Validates the request body: `{:ok, {state, check, detail}}` or `:error`."
  def parse(%{"state" => s, "check" => c, "detail" => d})
      when s in @states and c in @checks and is_binary(d),
      do: {:ok, {s, c, String.slice(d, 0, @max_detail)}}

  def parse(_), do: :error

  @doc "The configured token, or nil when the route is off."
  def token do
    case Application.get_env(:risime, :ops_alert, [])[:token] do
      t when is_binary(t) and t != "" -> t
      _ -> nil
    end
  end

  @doc "The rate-limit key (one bucket; tests give each test its own)."
  def bucket_key, do: Application.get_env(:risime, :ops_alert, [])[:bucket_key] || :all

  def phones, do: Application.get_env(:risime, :ops_alert, [])[:phones] || []

  @doc "The English line every client can show."
  def body("restarted", detail),
    do:
      line(
        "RisiMe server was not answering on spark2. I restarted it and it is healthy again.",
        detail
      )

  def body("rolled_back", detail),
    do:
      line(
        "RisiMe server was not answering on spark2. It is healthy again only after I went back one release.",
        detail
      )

  def body("gave_up", detail),
    do:
      line(
        "RisiMe server is still unhealthy and I stopped restarting it. Please check spark2.",
        detail
      )

  def body("public_down", detail),
    do: line("RisiMe is healthy on spark2 but not reachable at risime.risicloud.ai.", detail)

  def body("recovered", detail), do: line("RisiMe is reachable again.", detail)

  defp line(text, ""), do: text
  defp line(text, detail), do: text <> " " <> detail

  @doc "The `risi` object for one recipient."
  def risi(state, check, detail, user_id, at) do
    %{
      "kind" => "ops_alert",
      "state" => state,
      "check" => check,
      "detail" => detail,
      "at" => Clock.ts(at),
      "notify" => [user_id]
    }
  end

  @doc "Sends to every configured operator. `%{sent: n, held: n}`."
  def send_alert(state, check, detail, at \\ DateTime.utc_now()) do
    results = Enum.map(phones(), &deliver(&1, state, check, detail, at))
    sent = Enum.count(results, &(&1 == :sent))
    # An operator phone with no user counts as held too: nobody got it.
    %{sent: sent, held: length(results) - sent}
  end

  defp deliver(phone, state, check, detail, at) do
    with %User{id: uid} <- Repo.get_by(User, phone: phone),
         true <- Out.ready?(),
         rc when not is_nil(rc) <- RisiChat.active_id(uid),
         {:ok, _} <- Out.post(rc, body(state, detail), risi(state, check, detail, uid, at)) do
      :sent
    else
      other ->
        Logger.warning("ops alert held: #{reason(other)}")
        :held
    end
  rescue
    e ->
      Logger.warning("ops alert held: #{inspect(e.__struct__)}")
      :held
  end

  defp reason(nil), do: "no user or no active Risi chat"
  defp reason(false), do: "Risi not ready"
  defp reason({:error, r}) when is_atom(r), do: "post failed: #{r}"
  defp reason(_), do: "post failed"
end
