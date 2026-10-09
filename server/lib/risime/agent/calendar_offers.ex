defmodule RisiMe.Agent.CalendarOffers do
  @moduledoc """
  **Offers become invites** (contract v1.29 §29.12, amends §28.5): an item (a ledger item or a
  v1.24 commitment) with a concrete future time, involving at least one calendar user, gets
  **one proposed Risi Calendar event** (`created_by: "risi"`, owner = the item owner,
  participants = owner ∪ counterparts, a neutral title, 1 h, `source.item_id`; claimed once per
  item in `risi_item_offers` kind `risi_event`) and each participant a `calendar_invite`: the
  owner at extraction, the counterparts once the item is tracked (the §27.5 rule;
  `RisiMe.Agent.Offers.recipients/1`), each once (kind `risi_invite`). A participant who isn't a
  calendar user keeps the §28.5 cards and their invite is held (`RisiMe.Agent.CalendarCards`).

  When every participant is a calendar user, the item's Official conversation gets **one**
  `event_card` `mode: "official"` (kind `event_card`), replacing §26.7 for that chat; for a ledger
  item only once it is tracked.

  Limits: 3 Risi-made events per Official chat per day, 1 per (title, start). Never from Private:
  only items of Official conversations (Risi receives nothing from Private, §24.5).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, CalendarCards, Clock, Commitment, Offers}
  alias RisiMe.Agent.Calendar.Event
  alias RisiMe.Repo

  @per_chat_day 3
  @tracked ~w(confirmed edited)

  @doc """
  Routes the calendar users among `recipients` of item `c` (opened) to Risi Calendar.
  Returns `{cards_posted, calendar_users_handled}`; the caller sends §28.5 offers to the others.
  Never raises.
  """
  def consider(%Commitment{} = c, recipients) do
    with true <- Calendar.on?(),
         true <- RisiMe.Agent.official?(c.conversation_id),
         false <- RisiMe.Groups.Tabs.risi_chat?(c.conversation_id),
         cal when cal != [] <- Calendar.calendar_users(recipients),
         {:ok, e} <- event_for(c) do
      {e, parts} = Calendar.load(e.event_id)
      ids = Enum.map(parts, & &1.user_id)

      invites =
        for u <- recipients, u in ids, claim(c.id, u, "risi_invite") == :ok do
          CalendarCards.invite(e, parts, u, "new", nil)
        end

      official = official_card(c, e, parts)
      {Enum.count(invites, &(&1 == :sent)) + official, cal}
    else
      _ -> {0, []}
    end
  rescue
    err ->
      Logger.warning("Risi calendar offer failed: #{inspect(err.__struct__)}")
      {0, []}
  end

  # The item's one Risi-made event: created now (claimed), or the one made before.
  defp event_for(c) do
    case claim(c.id, c.owner_id, "risi_event") do
      :ok ->
        case create(c) do
          {:ok, id} ->
            {:ok, %Event{event_id: id}}

          error ->
            release(c.id, c.owner_id, "risi_event")
            error
        end

      :taken ->
        case Repo.one(
               from e in Event,
                 where: e.source_item_id == ^c.id and e.created_by == "risi",
                 order_by: [desc: e.created_at],
                 limit: 1
             ) do
          %Event{state: "active"} = e -> {:ok, e}
          _ -> :none
        end
    end
  end

  defp create(c) do
    start = Clock.usec(c.due)
    stop = DateTime.add(start, 3600, :second)
    people = Enum.uniq([c.owner_id | c.counterpart_ids])
    names = CalendarCards.names(people) |> Map.values()
    title = neutral_title(c.text, names)

    with :ok <- room?(c.conversation_id, title, start) do
      attrs = %{
        title: title,
        notes: nil,
        tz: Clock.user_tz(c.owner_id),
        start: start,
        stop: stop,
        all_day: false,
        with: c.counterpart_ids,
        drop: true,
        reminder_min: :default,
        source: %{
          conversation_id: c.conversation_id,
          message_ids: uuids(c.source_message_ids),
          item_id: c.id
        },
        created_by: "risi"
      }

      case Calendar.create(c.owner_id, attrs) do
        {:ok, view} -> {:ok, view["event_id"]}
        error -> error
      end
    end
  end

  defp uuids(ids) do
    ids
    |> List.wrap()
    |> Enum.flat_map(fn id ->
      case Ecto.UUID.cast(id) do
        {:ok, u} -> [u]
        _ -> []
      end
    end)
    |> Enum.take(20)
  end

  # §29.12: 3 Risi-made events per Official chat per day; 1 per (title, start).
  defp room?(conv, title, start) do
    since = DateTime.add(DateTime.utc_now(), -86_400, :second)

    today =
      Repo.all(
        from e in Event,
          where:
            e.source_conversation_id == ^conv and e.created_by == "risi" and
              e.created_at > ^since
      )

    same? =
      Repo.all(
        from e in Event,
          where:
            e.source_conversation_id == ^conv and e.created_by == "risi" and
              e.state == "active" and e.start_at == ^start
      )
      |> Enum.any?(&(Calendar.open_title(&1) == {:ok, title}))

    cond do
      length(today) >= @per_chat_day -> {:error, :rate_limited}
      same? -> {:error, :duplicate}
      true -> :ok
    end
  end

  defp official_card(c, e, parts) do
    ids = Enum.map(parts, & &1.user_id)

    with true <- not Commitment.ledger?(c) or c.item_state in @tracked,
         true <- length(Calendar.calendar_users(ids)) == length(ids),
         :ok <- claim(c.id, c.owner_id, "event_card") do
      case CalendarCards.official(e, parts, c.conversation_id) do
        :ok ->
          1

        _ ->
          release(c.id, c.owner_id, "event_card")
          0
      end
    else
      _ -> 0
    end
  end

  ## Neutral titles (§29.2)

  @doc """
  A Risi-made title that reads right for every participant: a leading "<Name>:" and a trailing
  "with <participant>" are stripped (`"Shenika: interview with Harsha"` → `"Interview"`).
  """
  def neutral_title(text, names) do
    t = String.trim(text || "")

    forms =
      names
      |> Enum.flat_map(fn n -> [n, n |> String.split() |> List.first()] end)
      |> Enum.reject(&(&1 in [nil, ""]))
      |> Enum.uniq()
      |> Enum.sort_by(&(-String.length(&1)))
      |> Enum.map(&Regex.escape/1)

    neutral =
      if forms == [] do
        t
      else
        alt = Enum.join(forms, "|")

        t
        |> String.replace(Regex.compile!("^(?:#{alt})\\s*:\\s*", "iu"), "")
        |> String.replace(
          Regex.compile!("\\s+with\\s+(?:(?:#{alt})(?:\\s*(?:,|and|&)\\s*)?)+[.!]?$", "iu"),
          ""
        )
        |> String.trim()
      end

    case neutral do
      "" -> String.slice(t, 0, 200)
      n -> n |> capitalise() |> String.slice(0, 200)
    end
  end

  defp capitalise(<<c::utf8, rest::binary>>), do: String.upcase(<<c::utf8>>) <> rest
  defp capitalise(s), do: s

  ## Claims (`risi_item_offers`, the §28.5 once-only rule)

  defp claim(item, user, kind) do
    now = DateTime.utc_now()

    {n, _} =
      Repo.insert_all(
        Offers.Offer,
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
        from o in Offers.Offer,
          where: o.item_id == ^item and o.user_id == ^user and o.kind == ^kind
      )

  ## Backfill (one-shot, idempotent)

  @doc """
  §29.12: every live tracked item (or v1.24 commitment) with a concrete future time gets its
  proposed event and invites (only the calendar path; no §28.5 cards). Idempotent through the
  claims. Counts only: `%{items: n, cards: m}` (`dry_run: true`: nothing is created).
  """
  def backfill(opts \\ []) do
    now = Clock.now()

    rows =
      Repo.all(
        from c in Commitment,
          where:
            c.state in ["proposed", "confirmed", "edited"] and
              (is_nil(c.item_state) or c.item_state in ^@tracked) and not is_nil(c.due) and
              c.due > ^now and c.all_day == false and
              (is_nil(c.due_kind) or c.due_kind == "datetime") and
              c.needs_clarification == false,
          order_by: [asc: c.due]
      )

    cards =
      if Keyword.get(opts, :dry_run, false) or not Calendar.on?() do
        0
      else
        Enum.sum(
          for r <- rows,
              {:ok, c} <- [Commitment.open(r)] do
            {n, _} = consider(c, Offers.recipients(c))
            n
          end
        )
      end

    %{items: length(rows), cards: cards}
  end
end
