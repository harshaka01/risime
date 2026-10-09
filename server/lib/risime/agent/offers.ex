defmodule RisiMe.Agent.Offers do
  @moduledoc """
  **Proactive offers** of ledger items (items 8 and 10, 2026-10-09). When Risi finds an item with
  a concrete date and time (a commitment or a meeting: "interview with Shenika Monday 12 Oct
  2pm"), each person it involves gets, in their own Risi chat:

    * **"Add to calendar?"**: the §25.4 `confirm` card of a `calendar_add` (the P0 action card:
      [Add] runs it on the confirming phone with no model call, [Edit], [Cancel]), with
      `origin: "offer"` and `item_id`; it lives until the event (at most 14 days);
    * **"Remind me?"**: a `set_reminder` card for 15 min before, when the Reminders skill is on.

  **Gates:** `RISI_SKILLS=on` (`Skills.on?/0`), the user has a `risi_skills` device and an active
  Risi chat, and the user's Calendar skill (Reminders skill for the reminder card) is `ask` or
  `allowed`. A proactive card is **always asked**, even under "Allowed". The user must still be
  an active member of the item's conversation.

  **Who:** the owner; counterparts too once the item is tracked (a ledger item counterparts only
  hear about after the owner's ✓, §27.5), or for a v1.24 card (posted to everyone in Official).

  **Once:** `risi_item_offers` (item, user, kind) is claimed before a card is posted, so an item
  is never offered twice, whatever happens to its card (Add, Cancel, expiry); a card that
  couldn't be posted releases its claim.

  **Vague items** (`needs_clarification`: no due, or "sometime", "soon", …) get no calendar offer:
  their owner gets **one** `item_clarify` question card instead ([New date] sends §27.5
  `item_edit` for a ledger item). Once a due is set (an edit, a merged duplicate), the item is
  considered again.

  `backfill/1` offers the existing future-dated items (one-shot, idempotent).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{CalendarChoice, ClientTools, Clock, Commitment, Out, Reminders, Secretary}
  alias RisiMe.Agent.{Skills, Writes}
  alias RisiMe.{Repo, RisiChat}

  defmodule Offer do
    @moduledoc false
    use Ecto.Schema

    @primary_key false
    schema "risi_item_offers" do
      field :item_id, :binary_id, primary_key: true
      field :user_id, :binary_id, primary_key: true
      field :kind, :string, primary_key: true
      field :state, :string, default: "offered"
      field :write_id, :binary_id
      field :inserted_at, :utc_datetime_usec
      field :updated_at, :utc_datetime_usec
    end
  end

  @tracked ~w(confirmed edited)
  @live ~w(proposed confirmed edited)
  @remind_before_min 15
  @max_ttl_s 14 * 24 * 3600
  @min_ttl_s 3600

  @vague ~r/\b(?:some ?time|soon|later|asap|as soon as|at some point|eventually|one day|some ?day|in a (?:bit|while)|in the (?:coming|next) (?:few )?(?:days|weeks)|next few days|when(?:ever)? (?:i|we|you|they) (?:can|get)|no rush|tbd|tba)\b/i

  ## Vague items (item 10)

  @doc """
  True when an item can't be scheduled: no due, or a vague due phrase ("sometime", "soon",
  "later", "asap", "at some point", …).
  """
  def vague?(due, due_text),
    do: due == nil or (is_binary(due_text) and Regex.match?(@vague, due_text))

  ## Considering an item

  @doc """
  Considers one item (opened: its `text` readable) for offers: the clarification question for a
  vague item, else the calendar (and reminder) offers. Returns the number of cards posted.
  Never raises (offers never break the flow that found the item).
  """
  def consider(%Commitment{} = c) do
    cond do
      not Skills.on?() or c.state not in @live or not is_binary(c.text) -> 0
      c.needs_clarification -> clarify(c)
      timed_future?(c) -> Enum.sum(for u <- recipients(c), do: offer(c, u))
      true -> 0
    end
  rescue
    e ->
      Logger.warning("Risi offer failed: #{inspect(e.__struct__)}")
      0
  end

  @doc "`consider/1` over rows (sealed or opened); rows deleted meanwhile are skipped."
  def consider_all(rows) do
    Enum.sum(
      for r <- rows,
          %Commitment{} = c <- [Repo.get(Commitment, r.id)],
          {:ok, c} <- [Commitment.open(c)],
          do: consider(c)
    )
  end

  defp timed_future?(%Commitment{due: due} = c) do
    due != nil and c.all_day != true and c.due_kind in [nil, "datetime"] and
      DateTime.compare(due, Clock.now()) == :gt
  end

  defp recipients(c) do
    counterparts =
      if Commitment.ledger?(c) and c.item_state not in @tracked, do: [], else: c.counterpart_ids

    [c.owner_id | counterparts]
    |> Enum.uniq()
    |> Enum.filter(&Secretary.active_human?(c.conversation_id, &1))
  end

  defp gated(user, skill_id) do
    with true <- Skills.gated?(user),
         true <- Skills.state(user, skill_id) in ~w(ask allowed),
         rc when is_binary(rc) <- RisiChat.active_id(user) do
      {:ok, rc}
    else
      _ -> :no
    end
  end

  ## Calendar and reminder offers (item 8)

  defp offer(c, user) do
    case gated(user, "calendar") do
      {:ok, rc} -> calendar(c, user, rc) + reminder(c, user, rc)
      :no -> 0
    end
  end

  defp calendar(c, user, rc) do
    title = c.text |> String.trim() |> String.slice(0, 200)
    start = Clock.usec(c.due)
    stop = DateTime.add(start, 3600, :second)
    tz = Clock.user_tz(user)

    wire = %{
      "title" => title,
      "start" => Clock.ts(start),
      "end" => Clock.ts(stop),
      "all_day" => false
    }

    card = %{
      args: %{"wire" => wire, "title" => title},
      card_args: wire,
      summary: "Add \"#{title}\" to your calendar on #{ClientTools.span(start, stop, false, tz)}",
      when: %{"start" => wire["start"], "end" => wire["end"], "all_day" => false},
      text: title,
      personal: true,
      skill_id: "calendar",
      calendar: CalendarChoice.get(user),
      device_id: device(user)
    }

    post_card(c, user, rc, "calendar", ClientTools.calendar_add(), card, start)
  end

  defp reminder(c, user, rc) do
    at = DateTime.add(Clock.usec(c.due), -@remind_before_min * 60, :second)

    with true <- DateTime.diff(at, Clock.now()) > 60,
         {:ok, ^rc} <- gated(user, "reminders") do
      title = c.text |> String.trim() |> String.slice(0, 200)
      at_text = Calendar.strftime(Clock.local(at, Clock.user_tz(user)), "%a %-d %b at %H:%M")

      card = %{
        args: %{
          "when" => Clock.ts(at),
          "text" => title,
          "audience" => "me",
          "at_text" => at_text
        },
        summary: "Remind you on #{at_text}: #{title}",
        when: %{"start" => Clock.ts(at), "end" => nil, "all_day" => false},
        text: title,
        personal: true,
        skill_id: "reminders",
        device_id: device(user)
      }

      post_card(c, user, rc, "reminder", Reminders.tool(), card, at)
    else
      _ -> 0
    end
  end

  defp post_card(c, user, rc, kind, tool, card, until) do
    with :ok <- claim(c.id, user, kind),
         ttl = DateTime.diff(until, Clock.now()) |> max(@min_ttl_s) |> min(@max_ttl_s),
         {:ok, wid} <- Writes.offer(user, rc, tool, card, c.id, ttl) do
      set_write(c.id, user, kind, wid)
      1
    else
      :taken ->
        0

      {:error, reason} ->
        Logger.warning("Risi #{kind} offer not sent: #{inspect(reason)}")
        release(c.id, user, kind)
        0
    end
  end

  # The fallback device of an offered write (the confirming device is used when it can).
  defp device(user) do
    user
    |> RisiMe.Devices.risi_tools_device_ids()
    |> Enum.find(&RisiMe.Devices.risi_skills_device?(user, &1))
  end

  ## The clarification question (item 10)

  defp clarify(%Commitment{owner_id: owner} = c) do
    with true <- Secretary.active_human?(c.conversation_id, owner),
         true <- Skills.gated?(owner),
         rc when is_binary(rc) <- RisiChat.active_id(owner),
         :ok <- claim(c.id, owner, "clarify") do
      q = question(c)

      risi = %{
        "kind" => "item_clarify",
        "item_id" => c.id,
        "summary_id" => c.summary_id,
        "text" => c.text,
        "due_text" => c.due_text,
        "question" => q,
        # [New date] → §27.5 `item_edit` with the new `due` (ledger items only).
        "buttons" => if(Commitment.ledger?(c), do: ["new_date"], else: []),
        "notify" => [owner]
      }

      case Out.post(rc, q, risi) do
        {:ok, _} ->
          1

        {:error, reason} ->
          Logger.warning("Risi clarification not sent: #{inspect(reason)}")
          release(c.id, owner, "clarify")
          0
      end
    else
      _ -> 0
    end
  end

  @doc "The one clarification question of a vague item."
  def question(%Commitment{text: text, due_text: due_text}) do
    said =
      if is_binary(due_text) and String.trim(due_text) != "",
        do: " You said \"#{String.trim(due_text)}\".",
        else: ""

    "When is \"#{text}\" due?#{said} Give me a day and time and I'll track it."
  end

  ## The once-only claim

  defp claim(item, user, kind) do
    now = DateTime.utc_now()

    {n, _} =
      Repo.insert_all(
        Offer,
        [
          %{
            item_id: item,
            user_id: user,
            kind: kind,
            state: "offered",
            inserted_at: now,
            updated_at: now
          }
        ],
        on_conflict: :nothing,
        conflict_target: [:item_id, :user_id, :kind]
      )

    if n == 1, do: :ok, else: :taken
  end

  defp release(item, user, kind),
    do:
      Repo.delete_all(
        from o in Offer, where: o.item_id == ^item and o.user_id == ^user and o.kind == ^kind
      )

  defp set_write(item, user, kind, wid),
    do:
      Repo.update_all(
        from(o in Offer, where: o.item_id == ^item and o.user_id == ^user and o.kind == ^kind),
        set: [write_id: wid, updated_at: DateTime.utc_now()]
      )

  @doc "An offered card's write was done, cancelled or voided (from `RisiMe.Agent.Writes`)."
  def settled(write_id, state) do
    new =
      case state do
        "done" -> "added"
        "cancelled" -> "cancelled"
        "void" -> "void"
        _ -> nil
      end

    if new,
      do:
        Repo.update_all(from(o in Offer, where: o.write_id == ^write_id),
          set: [state: new, updated_at: DateTime.utc_now()]
        )

    :ok
  end

  @doc "The offers made for an item (tests, support)."
  def list(item_id), do: Repo.all(from o in Offer, where: o.item_id == ^item_id)

  ## Backfill (one-shot, idempotent)

  @doc """
  Offers every live item (proposed, confirmed or edited) with a concrete future time. Idempotent:
  an item already offered to a user is skipped (`risi_item_offers`). Returns counts only:
  `%{items: n, cards: m}` (`dry_run: true`: `cards` is 0, nothing is posted).
  """
  def backfill(opts \\ []) do
    now = Clock.now()

    rows =
      Repo.all(
        from c in Commitment,
          where:
            c.state in ^@live and not is_nil(c.due) and c.due > ^now and c.all_day == false and
              (is_nil(c.due_kind) or c.due_kind == "datetime") and
              c.needs_clarification == false,
          order_by: [asc: c.due]
      )

    cards = if Keyword.get(opts, :dry_run, false), do: 0, else: consider_all(rows)
    %{items: length(rows), cards: cards}
  end
end
