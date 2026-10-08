defmodule RisiMe.Agent.ConversationSup do
  @moduledoc """
  One `RisiMe.Agent.Conversation` per Official group Risi handles, started on demand and named
  in `RisiMe.Agent.Registry` by conversation id. Only Official conversations are ever started
  (`RisiMe.Agent.official?/1`).
  """
  use DynamicSupervisor

  alias RisiMe.Agent.Conversation

  def start_link(_opts \\ []), do: DynamicSupervisor.start_link(__MODULE__, :ok, name: __MODULE__)

  @impl true
  def init(:ok), do: DynamicSupervisor.init(strategy: :one_for_one)

  @doc "The conversation's process, started if needed: `{:ok, pid}` or `{:error, :private_tab}`."
  def ensure("grp:" <> _ = conv) do
    case Registry.lookup(RisiMe.Agent.Registry, conv) do
      [{pid, _}] ->
        {:ok, pid}

      [] ->
        if RisiMe.Agent.official?(conv) do
          case DynamicSupervisor.start_child(__MODULE__, {Conversation, conv}) do
            {:ok, pid} -> {:ok, pid}
            {:error, {:already_started, pid}} -> {:ok, pid}
            {:error, _} = e -> e
          end
        else
          {:error, :private_tab}
        end
    end
  end

  def ensure(_conv), do: {:error, :private_tab}

  @doc "The running conversation processes."
  def running,
    do: for({_, pid, _, _} <- DynamicSupervisor.which_children(__MODULE__), do: pid)
end
