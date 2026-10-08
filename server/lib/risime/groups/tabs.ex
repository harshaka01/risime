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
end
