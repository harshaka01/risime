defmodule RisiMe.Agent.Embeddings do
  @moduledoc """
  `risi_fact_embeddings` (§24.12: pgvector over the **fact text only**). A stub until an
  embedding model is served on loopback next to `risi-l1`: `index/1` writes nothing and returns
  `:disabled`. Deletion needs no code: rows cascade with their fact.
  """

  @doc "Embeds one fact's text and stores it; `:disabled` while no embedding model is served."
  def index(%RisiMe.Agent.Fact{}), do: :disabled
end
