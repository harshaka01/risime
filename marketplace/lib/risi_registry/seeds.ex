defmodule RisiRegistry.Seeds do
  @moduledoc """
  Seed data. RisiWork Ask is the first Official entry, **as data only** (architect 22:05):
  it is `proposed` and `unverified` with no `card_hash`, so it is not discoverable and no call
  is made until RisiWork publishes its signed card and a person approves it and activates it.

  Values come from `contracts/ask-skill.md` section 1 in the RisiBridge.
  """
  alias RisiRegistry.{AllowList, Registry}

  @ask %{
    agent_id: "risicloud.ai/risiwork-ask",
    name: "RisiWork Ask",
    description:
      "Answers questions about your company's bills, spending and reports from RisiWork's own books.",
    owner: %{"name" => "RisiWork", "contact" => "via the RisiBridge"},
    domain: "risicloud.ai",
    card_url: "https://risicloud.ai/.well-known/agent-card.json",
    tier: "official",
    skills: [
      %{"id" => "ask.bills", "description" => "A2A skill: questions about bills and spending"},
      %{"id" => "ask_bills", "description" => "MCP tool: questions about bills and spending"}
    ],
    scopes: [
      %{"id" => "ask.bills", "label" => "Reads your company's bills, spending and reports"}
    ],
    data_classes: ["own_figures"],
    limits: %{"per_person_per_hour" => 20},
    consequential: false
  }

  def ask, do: @ask

  @doc "Idempotent: inserts Ask if missing and the configured allow-list entry if any."
  def run do
    ask =
      case Registry.get(@ask.agent_id) do
        nil ->
          {:ok, agent} = Registry.register(@ask)
          agent

        agent ->
          agent
      end

    {ask, AllowList.seed_from_config()}
  end
end
