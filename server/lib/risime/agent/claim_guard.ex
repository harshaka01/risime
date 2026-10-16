defmodule RisiMe.Agent.ClaimGuard do
  @moduledoc """
  **The claim guard** (Harsha 2026-10-15 20:01, P0 follow-up of his 3rd Call Risi call, where Risi
  said "I have added the racing discussion" with nothing added): Risi may say it added, sent,
  saved, booked, scheduled, set, deleted … something **only when the turn holds a successful tool
  result for that action** — a `risi_pending_writes` row of the turn's request in state `done`
  (its `write_id` is the result id). Structural, not a prompt rule.

  Called on every output path:
    * the §25 turn's answer before `Audience.deliver` (`turn/4`: the Risi chat, Official @Risi,
      spoken "Risi, …" in a voice note, answer cards and the Call Risi voice sink);
    * Call Risi's chat route and the model's rephrase of a spoken answer
      (`RisiMe.Agent.CallRisi.Brain`, `RisiMe.Agent.CallRisi.Spoken.checked/3`).

  A reply that claims an action without a matching result is rewritten to the honest status
  ("I've drafted it. Tap Add to confirm." for a card waiting for its tap, "That didn't go
  through, so nothing was added." for a failed one, …) and logged as a `claim_violation`: a
  warning with the path only, a telemetry event `[:risime, :risi, :claim_violation]` and one
  `risi_agent_runs` row (agent `claim_guard`, the path as its reason). **Never text.**
  """
  import Ecto.Query

  require Logger

  @verbs "added|saved|scheduled|set|sent|booked|created|put|blocked|reminded|updated|changed|moved|cancell?ed|cleared|deleted|removed|noted|entered|logged|inserted"
  @do_verbs "add|save|schedule|set|send|book|create|put|block|remind|update|change|move|cancel|delete|remove|note|enter|log|insert"

  @claims [
    Regex.compile!(
      "\\b(?:I|we)(?:'ve|\\s+have|\\s+had|'d)?\\s+(?:(?:just|now|already|successfully|also|gone\\s+ahead\\s+and)\\s+)*(?:#{@verbs})\\b",
      "iu"
    ),
    Regex.compile!(
      "\\b(?:it|that|this|they|these|those|(?:the|your|my)\\s+(?:\\w+\\s+){0,3}?(?:entry|event|meeting|appointment|reminder|discussion|message|item|slot|block|card))(?:'s|\\s+is|\\s+has\\s+been|'s\\s+been|\\s+was|\\s+are|\\s+were|\\s+have\\s+been|\\s+will\\s+be)\\s+(?:now\\s+|been\\s+|all\\s+)?(?:#{@verbs}|in\\s+your\\s+(?:risi\\s+)?calendar|on\\s+your\\s+(?:risi\\s+)?calendar)\\b",
      "iu"
    ),
    Regex.compile!(
      "\\b(?:I|we)(?:'ll|\\s+will|\\s+am\\s+going\\s+to|'m\\s+going\\s+to)\\s+(?:(?:go\\s+ahead\\s+and|now|also|just)\\s+)*(?:#{@do_verbs})\\b",
      "iu"
    ),
    ~r/\byou\s+should\s+(?:now\s+)?(?:be\s+able\s+to\s+)?see\b/iu,
    ~r/\b(?:is|are)\s+now\s+(?:in|on)\s+your\b/iu,
    ~r/^\W*(?:done|all\s+set|all\s+done)\b/iu
  ]

  # A tool-less voice answer (Call Risi's chat route) can't keep "I can add that for you" either.
  @offers Regex.compile!(
            "\\b(?:I|we)\\s+(?:can|could)\\s+(?:(?:go\\s+ahead\\s+and|now|also|just)\\s+)*(?:#{@do_verbs})\\b",
            "iu"
          )

  # Not a claim of this turn: a listing of what exists ("Here's what I've set up for you:"), a
  # question ("Shall I add it?"), a negation ("I haven't added it").
  @not_claims [
    ~r/\bwhat\s+I'?\s*(?:have|'ve)?\s*(?:set\s+up|added|saved|scheduled|noted)\s+for\s+you\b/iu,
    ~r/^\W*(?:shall|should|can|could|do\s+you\s+want)\s+(?:I|we)\b.*\?\s*$/iu,
    ~r/\b(?:I|we)\s+(?:haven'?t|have\s+not|didn'?t|did\s+not|can'?t|cannot|won'?t)\b/iu
  ]

  @doc """
  True when `text` claims (or promises) that Risi did an action. `strict`: an offer ("I can add
  that for you") counts too (a reply with no tools, where nothing can be done).
  """
  def claim?(text, strict \\ false)

  def claim?(text, strict) when is_binary(text) do
    text
    |> sentences()
    |> Enum.any?(&(claim_sentence?(&1) or (strict and offer_sentence?(&1))))
  end

  def claim?(_, _), do: false

  defp offer_sentence?(s),
    do: Regex.match?(@offers, s) and not Enum.any?(@not_claims, &Regex.match?(&1, s))

  defp claim_sentence?(s),
    do:
      Enum.any?(@claims, &Regex.match?(&1, s)) and
        not Enum.any?(@not_claims, &Regex.match?(&1, s))

  defp sentences(text), do: Regex.split(~r/(?<=[.!?…])\s+|\n+/u, text, trim: true)

  ## The turn's results

  @doc """
  The write results of `request_id`: `[%{write_id, state, tool, via}]` (newest first). Tests may
  set `config :risime, :risi_claim_results` to a 1-arity function.
  """
  def results(nil), do: []

  def results(request_id) do
    case Application.get_env(:risime, :risi_claim_results) do
      f when is_function(f, 1) -> f.(request_id)
      _ -> db_results(request_id)
    end
  end

  defp db_results(request_id) do
    case Ecto.UUID.cast(request_id) do
      {:ok, rid} ->
        RisiMe.Repo.all(
          from w in RisiMe.Agent.Writes.Write,
            where: w.request_id == ^rid,
            order_by: [desc: w.inserted_at],
            select: %{write_id: w.write_id, state: w.state, tool: w.tool, via: w.via}
        )

      _ ->
        []
    end
  rescue
    _ -> []
  end

  @doc "True when the turn holds a successful result (a write of the request is `done`)."
  def done?(request_id), do: Enum.any?(results(request_id), &(&1.state == "done"))

  @doc "The honest status of the request's action (no successful result)."
  def honest(request_id) do
    rows = results(request_id)
    states = Enum.map(rows, & &1.state)
    # Harsha's call 75ec8683: a message card's buttons are Send / Edit / Cancel, never Add.
    message? = rows != [] and Enum.all?(rows, &(&1[:tool] == "send_message"))

    cond do
      message? and "pending" in states ->
        "I've drafted it. Tap Send to send it, or Cancel."

      message? and Enum.any?(states, &(&1 in ~w(void confirmed))) ->
        "That didn't go through, so nothing was sent."

      message? ->
        "Nothing was sent."

      "pending" in states ->
        "I've drafted it. Tap Add to confirm."

      "clarify" in states ->
        "I haven't done it yet: I need one more detail from you."

      "running" in states ->
        "It's still going through, so it isn't done yet."

      Enum.any?(states, &(&1 in ~w(void confirmed))) ->
        "That didn't go through, so nothing was added."

      states != [] ->
        "Nothing was added."

      true ->
        "I haven't done that yet."
    end
  end

  @doc """
  `text` with every claiming sentence replaced (the first by `honest`, the rest dropped) unless
  the request holds a successful result. `{text, :ok}` or `{rewritten, :violation}` (logged under
  `path`). `opts`: `honest` (the line to use instead of `honest/1`).
  """
  def guard(text, request_id, path, opts \\ [])

  def guard(text, request_id, path, opts) when is_binary(text) do
    cond do
      not claim?(text) ->
        {text, :ok}

      done?(request_id) ->
        {text, :ok}

      true ->
        line = Keyword.get_lazy(opts, :honest, fn -> honest(request_id) end)
        violation(path)
        {rewrite(text, line), :violation}
    end
  end

  def guard(text, _request_id, _path, _opts), do: {text, :ok}

  # Line by line (a card's text keeps its other lines); in a line, sentence by sentence.
  defp rewrite(text, line) do
    {lines, _} =
      text
      |> String.split("\n")
      |> Enum.map_reduce(false, fn l, said? ->
        {parts, said?} =
          l
          |> String.split(~r/(?<=[.!?…])\s+/u, trim: true)
          |> Enum.map_reduce(said?, fn s, said? ->
            cond do
              # The honest line already asks for the tap: the model's own "please tap Add" goes.
              s != line and String.contains?(line, ["Tap Add", "Tap Send"]) and
                  Regex.match?(~r/\btap\b.*\b(?:add|confirm|send)\b/iu, s) ->
                {nil, said?}

              not claim_sentence?(s) ->
                {s, said?}

              said? ->
                {nil, true}

              true ->
                {line, true}
            end
          end)

        {parts |> Enum.reject(&is_nil/1) |> Enum.join(" "), said?}
      end)

    lines |> Enum.join("\n") |> String.replace(~r/\n{3,}/u, "\n\n") |> String.trim()
  end

  @doc """
  The §25 turn's answer (`RisiMe.Agent.Turn`, before `Audience.deliver`): `{answer, answer_risi}`
  guarded (the card's own `answer` too). `skip?`: a server-built answer (a schedule read, Risi's
  items) that lists what exists and claims nothing of this turn.
  """
  def turn(answer, answer_risi, ctx, skip? \\ false)

  def turn(answer, answer_risi, _ctx, true), do: {answer, answer_risi}

  def turn(answer, answer_risi, ctx, _skip?) when is_binary(answer) do
    rid = Map.get(ctx, :request_id)

    case guard(answer, rid, "turn") do
      {_, :ok} ->
        {answer, answer_risi}

      {safe, :violation} ->
        risi =
          if is_map(answer_risi) and is_binary(answer_risi["answer"]),
            do:
              Map.put(
                answer_risi,
                "answer",
                elem(guard(answer_risi["answer"], rid, "turn_card"), 0)
              ),
            else: answer_risi

        {safe, risi}
    end
  end

  def turn(answer, answer_risi, _ctx, _skip?), do: {answer, answer_risi}

  @doc "Records one claim violation on `path` (no text)."
  def violation(path) do
    Logger.warning("risi claim_violation path=#{path}")
    :telemetry.execute([:risime, :risi, :claim_violation], %{count: 1}, %{path: path})

    Task.start(fn ->
      runs().record(%{
        agent: "claim_guard",
        mode: "on",
        status: "violation",
        reason: "claim_violation",
        by: to_string(path)
      })
    end)

    :ok
  end

  defp runs, do: Application.get_env(:risime, :risi_claim_runs, RisiMe.Agents.Runs)
end
