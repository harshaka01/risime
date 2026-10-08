defmodule RisiMe.Agent do
  @moduledoc """
  The Risi agent tree: Risi's MLS member client inside the server (contract v1.24 §24.11,
  decisions 065, 066, 067; server S5). No model calls yet (S6).

  ## When it runs
  `RisiMe.Agent.Supervisor` is started by the application only when **all** of these hold
  (`startable?/0`); otherwise it is not started and `RisiMe.Risi.available?/0` is false, so every
  chat reports `agent_unavailable` (§24.15):

    * `RISI=on`;
    * the MLS NIF is loaded (`RisiMe.Agent.Mls.Nif.loaded?/0`, `scripts/build-mls-nif`);
    * `RISI_MLS_KEK`: base64 of 32 bytes (seals Risi's MLS state in `risi_mls_kv`);
    * `RISI_DATA_KEY`: base64 of 32 bytes (seals the `risi_buffer` bodies).

  It is a `:temporary` child: a tree that keeps crashing stops (Risi becomes unavailable) and
  never takes the server down.

  ## The tree (`rest_for_one`)
  `Registry` → `Agent.Mls` (the NIF handle, one serial lane) → `Agent.KeyPackages` →
  `Agent.ConversationSup` (one `Agent.Conversation` per Official group) → `Agent.Inbox` (Risi's
  inbox, drained like a client's).

  ## Private is never touched (§24.0, §24.5)
  Every entry point checks a **server query** on `groups.tab = 'official'` (`official?/1`), and
  the inbox additionally the §24.5 query (`RisiMe.Groups.Tabs.agent_conversation?/2`), before any
  MLS call or any buffer write; the NIF refuses non-Official groups on its own as well. Nothing in
  the tree ever logs plaintext.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.Mls.Nif
  alias RisiMe.{Repo, Risi}

  @doc "True when the tree may start: RISI on, the NIF loaded, both keys present and valid."
  def startable?, do: missing() == []

  @doc "Why the tree can't start (names only, never values)."
  def missing do
    [
      {Risi.enabled?(), "RISI=on"},
      {Nif.loaded?(), "the MLS NIF (scripts/build-mls-nif)"},
      {match?({:ok, _}, kek()), "RISI_MLS_KEK (base64, 32 bytes)"},
      {match?({:ok, _}, data_key()), "RISI_DATA_KEY (base64, 32 bytes)"}
    ]
    |> Enum.reject(&elem(&1, 0))
    |> Enum.map(&elem(&1, 1))
  end

  @doc false
  def kek, do: key(:risi_mls_kek)

  @doc false
  def data_key, do: key(:risi_data_key)

  defp key(name) do
    with b64 when is_binary(b64) <- Application.get_env(:risime, name),
         {:ok, <<_::binary-size(32)>> = k} <- Base.decode64(String.trim(b64)) do
      {:ok, k}
    else
      _ -> :error
    end
  end

  @doc "The application's children for the tree: `[]` unless `startable?/0`."
  def children do
    case {Risi.enabled?(), missing()} do
      {_, []} ->
        [Supervisor.child_spec(RisiMe.Agent.Supervisor, restart: :temporary)]

      {true, missing} ->
        Logger.warning("Risi agent not started: missing #{Enum.join(missing, ", ")}")
        []

      {false, _} ->
        []
    end
  end

  @doc "True while the whole tree is up (its last child, the inbox, is running)."
  def running?, do: GenServer.whereis(RisiMe.Agent.Inbox) != nil

  @doc """
  The server-side defence (§24.5): true only for a `grp:` conversation whose `groups.tab` is
  `'official'` (a direct query, never a cache or a client claim).
  """
  def official?("grp:" <> _ = conv),
    do:
      Repo.exists?(
        from g in RisiMe.Groups.Group, where: g.id == ^conv and g.tab == "official", select: 1
      )

  def official?(_conv), do: false

  @doc """
  True when Risi may act in `conv` now: Official, the chat on, Risi an active member (§24.5).
  """
  def may_act?(conv),
    do: official?(conv) and RisiMe.Groups.Tabs.agent_conversation?(Risi.user_id(), conv)

  @doc "The MLS group id of a `grp:` conversation at a generation (§12.0)."
  def group_id(conv, generation), do: "#{conv}##{generation}"

  ## Official off (§24.4)

  @doc """
  Official is being turned off by `actor`: while Risi is still an active member and the chat is
  still on, Risi posts its farewell line (an ordinary Risi `text`, §24.4). Called by
  `RisiMe.Chats.toggle/4` **before** the off transaction marks Risi `pending_remove`. Best effort:
  `:ok` whatever happens (the toggle never waits more than `timeout`).
  """
  def official_off(conv, actor, timeout \\ 15_000) do
    if running?() and may_act?(conv) do
      name = actor_name(actor)

      body =
        "Official was turned off by #{name}. I've deleted what I learned in this chat."

      case RisiMe.Agent.Send.text(conv, body, nil, timeout) do
        {:ok, _} -> :ok
        {:error, reason} -> Logger.warning("Risi farewell not sent: #{inspect(reason)}")
      end
    end

    :ok
  catch
    :exit, _ ->
      Logger.warning("Risi farewell not sent: timeout")
      :ok
  end

  @doc """
  After Official was turned off (§24.4): delete what Risi holds for the conversation now (its
  buffer rows; facts and jobs from S6 on). Its MLS state goes when the removal commit lands.
  """
  def forget(conv) do
    RisiMe.Agent.Transcript.purge(conv)
  rescue
    e -> Logger.warning("Risi forget failed: #{Exception.message(e)}")
  end

  defp actor_name(actor) do
    case Repo.get(RisiMe.Accounts.User, actor) do
      %{display_name: n} when is_binary(n) and n != "" -> n
      _ -> "a member"
    end
  end
end
