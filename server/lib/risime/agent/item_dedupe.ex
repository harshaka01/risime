defmodule RisiMe.Agent.ItemDedupe do
  @moduledoc """
  Near-duplicate items (item 10, 2026-10-09). Before a new commitment or ledger item is stored,
  Risi looks for a live one (proposed, confirmed or edited, from the last 30 days) that says the
  same thing; if it finds one, the new item is **merged into it** instead of becoming a second
  row (a second card, a second promise, a second reminder).

  Same item when all hold:

    * the same owner;
    * counterparts that agree: equal, or one set contains the other (the merge keeps the union);
    * overlapping text: of the content words (lower case, no stop words, no day/time words),
      Jaccard ≥ 0.5, or at least 2 shared words covering ≥ 60 % of the shorter text;
    * dues in the same window: both missing, one missing (the merge fills it), or at most 24 h
      apart.

  **The merge** unions the counterparts and source messages; a due the kept item lacked is
  taken (with its phrase, sealed) and its `needs_clarification` re-evaluated; a tracked item's
  reminders are rescheduled, and the item is considered for offers again (a vague item that
  became concrete gets its "Add to calendar?").
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Commitment, Commitments, LedgerReminders, Offers}
  alias RisiMe.Repo

  @live ~w(proposed confirmed edited)
  @window_s 24 * 3600
  @lookback_s 30 * 24 * 3600

  @stop ~w(a an the to for with and or of on in at by from into about my your our his her their
           its i we you he she they me us him them it this that these those be is are was were
           will would shall should can could may might must do does did have has had get got
           please also just then so up out over again all any some new next last before after
           due today tonight tomorrow yesterday morning afternoon evening night week weekend
           month monday tuesday wednesday thursday friday saturday sunday mon tue tues wed thu
           thur thurs fri sat sun jan feb mar apr may jun jul aug sep sept oct nov dec january
           february march april june july august september october november december am pm
           noon midnight oclock o'clock)

  @doc """
  The new items (maps with `owner`, `counterpart` or `counterparts`, `text`, `due`, `all_day`,
  `due_kind`, `due_text`, `source_message_ids`) that are **not** duplicates; each duplicate is
  merged into the item it repeats. Never raises (a failed check keeps the item).
  """
  def drop_merged(items), do: Enum.reject(items, &merged?/1)

  @doc "Merges `item` into a live duplicate if there is one: true when it was merged."
  def merged?(item) do
    case find(item) do
      nil ->
        false

      c ->
        merge(c, item)
        true
    end
  rescue
    e ->
      Logger.warning("Risi item dedupe failed: #{inspect(e.__struct__)}")
      false
  end

  @doc "The live item `item` repeats (opened), or nil."
  def find(item) do
    since = DateTime.add(DateTime.utc_now(), -@lookback_s, :second)
    counterparts = counterparts_of(item)

    Repo.all(
      from c in Commitment,
        where: c.owner_id == ^item.owner and c.state in ^@live and c.inserted_at >= ^since,
        order_by: [asc: c.inserted_at]
    )
    |> Enum.find_value(fn row ->
      with true <- counterparts_agree?(row.counterpart_ids, counterparts),
           true <- same_window?(row.due, item[:due]),
           {:ok, c} <- Commitment.open(row),
           true <- similar?(c.text, item.text) do
        c
      else
        _ -> nil
      end
    end)
  end

  defp counterparts_of(item), do: item[:counterpart] || item[:counterparts] || []

  @doc "Counterpart sets agree: equal, or one contains the other."
  def counterparts_agree?(a, b) do
    a = MapSet.new(a || [])
    b = MapSet.new(b || [])
    MapSet.subset?(a, b) or MapSet.subset?(b, a)
  end

  @doc "Dues in the same window: either missing, or at most 24 h apart."
  def same_window?(nil, _b), do: true
  def same_window?(_a, nil), do: true
  def same_window?(a, b), do: abs(DateTime.diff(a, b)) <= @window_s

  @doc "Overlapping text (see the module doc)."
  def similar?(a, b) do
    wa = words(a)
    wb = words(b)
    inter = MapSet.size(MapSet.intersection(wa, wb))
    union = MapSet.size(MapSet.union(wa, wb))
    shorter = min(MapSet.size(wa), MapSet.size(wb))

    cond do
      union == 0 or shorter == 0 -> String.downcase(a || "") == String.downcase(b || "")
      inter / union >= 0.5 -> true
      inter >= 2 and inter / shorter >= 0.6 -> true
      true -> false
    end
  end

  @doc "The content words of a text."
  def words(nil), do: MapSet.new()

  def words(text) do
    text
    |> String.downcase()
    |> String.split(~r/[^\p{L}\p{N}']+/u, trim: true)
    |> Enum.map(&String.trim(&1, "'"))
    |> Enum.reject(fn w ->
      w in @stop or String.length(w) < 2 or w =~ ~r/^\d+(?:st|nd|rd|th|am|pm|h)?$/ or
        w =~ ~r/^\d{1,2}[:.]\d\d(?:am|pm)?$/
    end)
    |> Enum.map(&stem/1)
    |> MapSet.new()
  end

  # A light plural stem ("quotes" → "quote"), enough for repeated phrasings.
  defp stem(w) do
    if String.length(w) > 3 and String.ends_with?(w, "s") and not String.ends_with?(w, "ss"),
      do: String.slice(w, 0..-2//1),
      else: w
  end

  ## The merge

  defp merge(%Commitment{} = c, item) do
    counterparts = Enum.uniq(c.counterpart_ids ++ counterparts_of(item)) -- [c.owner_id]

    sources =
      Enum.uniq(c.source_message_ids ++ (item[:source_message_ids] || [])) |> Enum.take(50)

    new_due? = c.due == nil and item[:due] != nil

    due_changes =
      if new_due? do
        all_day = item[:all_day] == true or item[:due_kind] == "date"
        {:ok, sealed} = Commitment.sealed_changes(c.id, c.text, item[:due_text])

        [
          due: usec(item.due),
          all_day: all_day,
          due_kind: if(all_day, do: "date", else: "datetime"),
          needs_clarification: Offers.vague?(item.due, item[:due_text]),
          schedule_v: c.schedule_v + 1
        ] ++ Keyword.drop(sealed, [:text, :text_sealed])
      else
        []
      end

    c =
      c
      |> Ecto.Changeset.change(
        [counterpart_ids: counterparts, source_message_ids: sources] ++ due_changes
      )
      |> Repo.update!()

    if new_due? do
      if c.state in Commitment.open_states(), do: reschedule(c)
      Offers.consider(c)
    end

    :ok
  end

  defp reschedule(c) do
    Commitments.cancel_timers(c.id)

    if Commitment.ledger?(c),
      do: LedgerReminders.schedule(c),
      else: Commitments.reschedule(c)
  end

  defp usec(%DateTime{microsecond: {us, _}} = dt), do: %{dt | microsecond: {us, 6}}
end
