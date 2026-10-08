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
  def official?("grp:" <> _ = conv) do
    case lookup(conv) do
      {:ok, tab} ->
        tab == "official"

      :miss ->
        case Repo.one(from g in Group, where: g.id == ^conv, select: g.tab) do
          nil ->
            false

          tab ->
            put(conv, tab)
            tab == "official"
        end
    end
  end

  def official?(_), do: false

  @doc "Records a group's tab (at insert)."
  def put(conv, tab) do
    :ets.insert(@table, {conv, tab})
    :ok
  rescue
    ArgumentError -> :ok
  end

  defp lookup(conv) do
    case :ets.lookup(@table, conv) do
      [{_, tab}] -> {:ok, tab}
      [] -> :miss
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
end
