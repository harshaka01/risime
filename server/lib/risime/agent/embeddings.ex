defmodule RisiMe.Agent.Embeddings do
  @moduledoc """
  Risi's text embeddings (contract v1.36 §35.1, decision 077: computed on spark2 only, never by a
  third party).

  The model today is **`risi-hash-384-v1`**: a deterministic, multilingual feature-hashing
  embedding computed in this VM (no download, no service). Features are each word and the
  character trigrams of each word (so Sinhala and Tamil inflections, Singlish spellings and
  partial names still overlap), hashed into 384 signed buckets and L2-normalised. It is a lexical
  model: retrieval combines it with the structured filters (conversation, range, people, kind).
  A neural model served on loopback can replace it later behind `embed/1` (the `model` column
  tells the two apart; rows of another model are re-embedded or ignored).

  `index/1` (facts, §24.12) stays a no-op: facts are covered by the memory items.
  """

  @dim 384
  @model "risi-hash-384-v1"

  @doc "The model name stored with each vector."
  def model, do: @model

  @doc "The vector size."
  def dim, do: @dim

  @doc "Embeds one fact's text (§24.12): `:disabled`, facts are covered by memory items."
  def index(%RisiMe.Agent.Fact{}), do: :disabled

  @doc "The embedding of `text`: a list of #{@dim} floats with norm 1 (all zeros for no words)."
  def embed(text) when is_binary(text) do
    counts =
      text
      |> features()
      |> Enum.reduce(%{}, fn {f, w}, acc ->
        h = :erlang.phash2(f, 4_294_967_296)
        i = rem(h, @dim)
        sign = if Bitwise.band(h, 0x80000000) == 0, do: 1.0, else: -1.0
        Map.update(acc, i, sign * w, &(&1 + sign * w))
      end)

    norm = counts |> Map.values() |> Enum.reduce(0.0, &(&1 * &1 + &2)) |> :math.sqrt()

    for i <- 0..(@dim - 1) do
      v = Map.get(counts, i, 0.0)
      if norm > 0, do: Float.round(v / norm, 6), else: 0.0
    end
  end

  @doc "The pgvector literal of a vector (`[0.1,…]`)."
  def literal(vec), do: "[" <> Enum.map_join(vec, ",", &Float.to_string(&1 * 1.0)) <> "]"

  @doc "True when the vector has no features (no words): it can't rank anything."
  def empty?(vec), do: Enum.all?(vec, &(&1 == 0.0))

  @doc "The word and trigram features of `text` with their weights."
  def features(text) do
    words =
      text
      |> :unicode.characters_to_nfc_binary()
      |> String.downcase()
      |> then(&Regex.scan(~r/[\p{L}\p{M}\p{N}]+/u, &1))
      |> List.flatten()
      |> Enum.reject(&(&1 in stopwords()))

    Enum.flat_map(words, fn w ->
      gs = String.graphemes(" " <> w <> " ")

      grams =
        if length(gs) >= 3,
          do: for(c <- Enum.chunk_every(gs, 3, 1, :discard), do: {"g:" <> Enum.join(c), 0.5}),
          else: []

      [{"w:" <> w, 1.0} | grams]
    end)
  end

  @stop ~w(a an the and or of to in on at for with by from is are was were be been i you we he she
           it they me my your our his her their this that these those what which who whom how when
           did do does done have has had will would can could shall should may might about please
           risi)

  defp stopwords, do: @stop

  @doc "Cosine similarity of two embeddings (both normalised)."
  def cosine(a, b), do: Enum.zip_reduce(a, b, 0.0, fn x, y, acc -> acc + x * y end)
end
