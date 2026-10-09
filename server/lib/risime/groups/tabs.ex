defmodule RisiMe.Groups.Tabs do
  @moduledoc """
  Contract v1.24 §24.7: which conversations are Official, and the `tabs` delivery filter.

  A group's `tab` is fixed at insert (§24.1 rule 1), so `official?/1` caches every answer for an
  existing group in a public ETS table (`init/0`, called once at application start): the filter
  runs for every event a non-`tabs` socket receives and costs one lookup.

  Events of Official conversations and every `chat_event` reach only `tabs` sockets and devices
  (live pushes, join/sync replies, push wake-ups); `GET /groups` and `GET /groups/{id}` hide
  Official groups from other devices.
  """
  import Ecto.Query

  alias RisiMe.Repo
  alias RisiMe.Groups.Group

  @table :risime_group_tabs

  @doc "Creates the cache table (application start)."
  def init do
    if :ets.whereis(@table) == :undefined,
      do: :ets.new(@table, [:named_table, :public, :set, read_concurrency: true])

    :ok
  end

  @doc "True if `conv` is an Official `grp:` conversation."
  def official?("grp:" <> _ = conv), do: match?({"official", _}, info(conv))
  def official?(_), do: false

  @doc "v1.25 §25.2: true if `conv` is a Risi chat (Official, `chat_kind: \"risi\"`)."
  def risi_chat?("grp:" <> _ = conv), do: info(conv) == {"official", "risi"}
  def risi_chat?(_), do: false

  # {tab, chat_kind} of an existing group (both fixed at insert), or nil.
  defp info(conv) do
    case lookup(conv) do
      {:ok, info} ->
        info

      :miss ->
        case Repo.one(from g in Group, where: g.id == ^conv, select: {g.tab, g.chat_kind}) do
          nil ->
            nil

          {tab, kind} = info ->
            put(conv, tab, kind)
            info
        end
    end
  end

  @doc "Records a group's tab and chat kind (at insert)."
  def put(conv, tab, kind \\ nil) do
    :ets.insert(@table, {conv, {tab, kind}})
    :ok
  rescue
    ArgumentError -> :ok
  end

  defp lookup(conv) do
    case :ets.lookup(@table, conv) do
      [{_, {_, _} = info}] -> {:ok, info}
      _ -> :miss
    end
  rescue
    ArgumentError -> :miss
  end

  @doc """
  True if the event (an inbox event or a signal, `%{kind, data}`) may reach only `tabs` sockets
  and devices: every `chat_event`, and anything of an Official conversation.
  """
  def tabs_only?(%{kind: "chat_event"}), do: true
  def tabs_only?(%{"kind" => "chat_event"}), do: true
  def tabs_only?(%{data: data}) when is_map(data), do: official?(conv_of(data))
  def tabs_only?(%{"data" => data}) when is_map(data), do: official?(conv_of(data))
  def tabs_only?(_), do: false

  defp conv_of(data), do: data["conversation_id"] || data["group_id"]

  @risi_kinds ~w(risi_tool_call risi_progress)

  @doc """
  v1.25 §25.2, §25.10: true if the event (an inbox event or a signal) may reach only
  `risi_tools` sockets and devices: `risi_tool_call`, `risi_progress` and anything of a Risi
  chat (`chat_event`s naming it included).
  """
  def risi_only?(%{kind: k}) when k in @risi_kinds, do: true
  def risi_only?(%{"kind" => k}) when k in @risi_kinds, do: true
  def risi_only?(%{data: data}) when is_map(data), do: risi_chat?(risi_conv_of(data))
  def risi_only?(%{"data" => data}) when is_map(data), do: risi_chat?(risi_conv_of(data))
  def risi_only?(_), do: false

  defp risi_conv_of(data), do: conv_of(data) || data["chat_id"]

  @doc "The push scope of an event: `:risi_tools` (§25.2), `:tabs` (§24.7) or `:all`."
  def scope(event) do
    cond do
      risi_only?(event) -> :risi_tools
      tabs_only?(event) -> :tabs
      true -> :all
    end
  end

  @doc """
  §24.5: what an agent's sockets may receive. An event of a conversation only while that
  conversation is an Official group whose chat is on and where the agent is an active member
  (a server query, never a client claim); events without a conversation pass; `chat_event`s
  never.
  """
  def agent_may_see?(_agent_id, %{kind: "chat_event"}), do: false

  def agent_may_see?(agent_id, %{data: data}) when is_map(data) do
    case conv_of(data) do
      nil -> true
      conv -> agent_conversation?(agent_id, conv)
    end
  end

  def agent_may_see?(_agent_id, _event), do: true

  @doc "The §24.5 query: an Official group with the chat on and the agent active in it."
  def agent_conversation?(agent_id, "grp:" <> _ = conv) do
    Repo.exists?(
      from g in Group,
        join: m in RisiMe.Groups.Member,
        on: m.group_id == g.id,
        left_join: c in RisiMe.Groups.Chat,
        on: c.chat_id == g.chat_id,
        where:
          g.id == ^conv and g.tab == "official" and m.user_id == ^agent_id and
            m.state == "active" and (is_nil(c.chat_id) or c.official == "on")
    )
  end

  def agent_conversation?(_agent_id, _conv), do: false

  @doc """
  `agent_may_see?/2` for a page of events (join/sync replies) with one query: returns the
  predicate for those events.
  """
  def agent_filter(agent_id, events) do
    convs =
      for %{data: data} when is_map(data) <- events,
          "grp:" <> _ = conv <- [conv_of(data)],
          uniq: true,
          do: conv

    allowed = agent_conversations(agent_id, convs)

    fn
      %{kind: "chat_event"} ->
        false

      %{data: data} when is_map(data) ->
        case conv_of(data) do
          nil -> true
          conv -> MapSet.member?(allowed, conv)
        end

      _ ->
        true
    end
  end

  defp agent_conversations(_agent_id, []), do: MapSet.new()

  defp agent_conversations(agent_id, convs) do
    Repo.all(
      from g in Group,
        join: m in RisiMe.Groups.Member,
        on: m.group_id == g.id,
        left_join: c in RisiMe.Groups.Chat,
        on: c.chat_id == g.chat_id,
        where:
          g.id in ^convs and g.tab == "official" and m.user_id == ^agent_id and
            m.state == "active" and (is_nil(c.chat_id) or c.official == "on"),
        select: g.id
    )
    |> MapSet.new()
  end
end
