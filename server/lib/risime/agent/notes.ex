defmodule RisiMe.Agent.Notes do
  @moduledoc """
  **Risi Notes** (contract v1.29 §30, decision 073): the record of an Official discussion, per
  person, in the Risi chat.

  * **Switch:** `RISI_NOTES` (`on?/0`, only with `RISI_LEDGER`). A **notes user** has the switch
    on and a device advertising `risi_notes` (which needs `risi_events`).
  * **One discussion, one note:** a note is made from the same §27.2 `discussion_summarise`
    extraction as the summary and has the same id (`note_id` = `summary_id`). Its items are the
    ledger items (`risi_commitments.summary_id = note_id`, always read live), its meetings the
    proposed Risi Calendar events (`risi_events.source_note_id`).
  * **Storage** (`risi_notes`): ids, times and participants in the clear; topic, language and key
    points only in `body_sealed` (AAD `risi_notes:<note_id>:body`, `RISI_DATA_KEY`). There is no
    plaintext text column. `risi_note_recipients` says who keeps it (`deleted` per person); the
    row is purged when nobody keeps it, after 365 days, or with the chat's Risi data (§24.4).
  * **REST** (`/api/v1/risi/notes…`, `RisiMeWeb.RisiNotesController`): list/search (over the
    caller's 500 most recent notes, opened in memory: no plaintext index, no embedding), get,
    delete one, delete all.

  Note text never goes to logs, job args, inbox events, push or metrics: only into the sealed row,
  the MLS-encrypted Risi cards (`RisiMe.Agent.NotesOut`) and the REST replies over TLS.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, CalendarCards, CalendarOffers, Clock, Commitment, Seal}
  alias RisiMe.Agent.Calendar.{Event, Participant}
  alias RisiMe.{Devices, Repo}

  @table "risi_notes"
  @keep_s 365 * 86_400
  @search_window 500
  @default_limit 20

  defmodule Note do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:note_id, :binary_id, autogenerate: false}
    schema "risi_notes" do
      field :conversation_id, :string
      field :chat_id, :string
      field :source, :string
      field :call_id, :binary_id
      field :media, :string
      field :duration_s, :integer
      field :started_at, :utc_datetime_usec
      field :ended_at, :utc_datetime_usec
      field :participants, {:array, :binary_id}, default: []
      field :body_sealed, :binary
      field :made_by, :map
      field :call_ref, :binary_id
      field :created_at, :utc_datetime_usec
      field :topic, :string, virtual: true
      field :language, :string, virtual: true
      field :key_points, {:array, :string}, virtual: true
    end
  end

  defmodule Recipient do
    @moduledoc false
    use Ecto.Schema

    @primary_key false
    schema "risi_note_recipients" do
      field :note_id, :binary_id, primary_key: true
      field :user_id, :binary_id, primary_key: true
      field :deleted, :boolean, default: false
      field :ended_at, :utc_datetime_usec
    end
  end

  ## Switch and users

  @doc "True while `RISI_NOTES=on` (and the ledger is on), §30.1."
  def on?, do: RisiMe.Risi.notes_on?()

  @doc "The notes users among `user_ids` (switch on and a `risi_notes` device)."
  def users(user_ids) do
    if on?(), do: Devices.risi_notes_users(Enum.uniq(user_ids)), else: []
  end

  @doc "True for a notes user."
  def user?(user_id) when is_binary(user_id), do: users([user_id]) != []
  def user?(_), do: false

  ## Sealing

  @doc "Seals topic, language and key points into `body_sealed`: `{:ok, note}` or `:error`."
  def seal(%Note{note_id: id} = n) do
    with {:ok, key} <- Seal.data() do
      body = %{"topic" => n.topic, "language" => n.language, "key_points" => n.key_points}
      {:ok, %{n | body_sealed: Seal.seal(key, Seal.aad(@table, id, "body"), body)}}
    end
  end

  @doc "Fills topic, language and key points: `{:ok, note}` or `:error` (no/wrong key)."
  def open(%Note{note_id: id} = n) do
    with {:ok, key} <- Seal.data(),
         {:ok, %{"topic" => t, "language" => l, "key_points" => kp}} <-
           Seal.open(key, Seal.aad(@table, id, "body"), n.body_sealed) do
      {:ok, %{n | topic: t, language: l, key_points: kp}}
    else
      _ -> :error
    end
  end

  ## Storing (from RisiMe.Agent.Ledger, inside its transaction)

  @doc """
  The note of discussion `d` (the same id) with the extraction's `topic`, `language` and key
  points, sealed: `{:ok, note}` or `:error` (no key).
  """
  def build(d, found) do
    seal(%Note{
      note_id: d.summary_id,
      conversation_id: d.conversation_id,
      chat_id: d.chat_id,
      source: d.source,
      call_id: d.call_id,
      media: d.media,
      duration_s: d.duration_s,
      started_at: d.started_at,
      ended_at: d.ended_at,
      participants: d.participants,
      made_by: d.made_by,
      call_ref: d.call_ref,
      created_at: d.created_at,
      topic: found.topic,
      language: found.language,
      key_points: found.key_points
    })
  end

  @doc "Inserts a sealed note and its recipients (raises; call inside a transaction)."
  def insert!(%Note{} = n, recipients) do
    Repo.insert!(n)

    Repo.insert_all(
      Recipient,
      for(
        u <- Enum.uniq(recipients),
        do: %{note_id: n.note_id, user_id: u, ended_at: n.ended_at}
      ),
      on_conflict: :nothing
    )

    :ok
  end

  @doc "The note's recipients (who still keep it or not)."
  def recipients(note_id),
    do: Repo.all(from r in Recipient, where: r.note_id == ^note_id, select: r.user_id)

  ## Meetings → proposed Risi Calendar events (§30.2, the §29.12 path)

  @doc """
  Creates one proposed Risi-made event per agreed meeting of the note (`source.note_id`), and
  sends each participant a `calendar_invite` (held for those who aren't calendar users yet).
  Only while `RISI_EVENTS` is on and at least one participant is a calendar user; the §29.12
  limits apply (3 Risi-made events per chat per day, 1 per (title, start)). A meeting at the
  time of one of the note's items is left to that item's own event. Returns the event ids.
  Never raises.
  """
  def make_events(%Note{} = n, meetings, items) do
    if Calendar.on?() do
      dues = for i <- items, i.due != nil and not i.all_day, do: DateTime.to_unix(i.due)

      meetings
      |> Enum.reject(fn m -> Enum.any?(dues, &(abs(&1 - DateTime.to_unix(m.start)) < 60)) end)
      |> Enum.flat_map(&make_event(n, &1))
    else
      []
    end
  rescue
    err ->
      Logger.warning("Risi note meeting not created: #{inspect(err.__struct__)}")
      []
  end

  defp make_event(n, m) do
    people = Enum.uniq([m.owner | m.with])

    with true <- Calendar.calendar_users(people) != [],
         names = people |> CalendarCards.names() |> Map.values(),
         title = CalendarOffers.neutral_title(m.title, names),
         :ok <- CalendarOffers.room?(n.conversation_id, title, m.start),
         {:ok, view} <-
           Calendar.create(m.owner, %{
             title: title,
             notes: nil,
             tz: Clock.user_tz(m.owner),
             start: m.start,
             stop: m.stop,
             all_day: false,
             with: m.with,
             drop: true,
             reminder_min: :default,
             source: %{
               conversation_id: n.conversation_id,
               message_ids: Enum.take(m.source_message_ids, 20),
               item_id: nil,
               note_id: n.note_id
             },
             created_by: "risi"
           }) do
      {e, parts} = Calendar.load(view["event_id"])
      for p <- parts, do: CalendarCards.invite(e, parts, p.user_id, "new", nil)
      [e.event_id]
    else
      _ -> []
    end
  end

  ## The wire Note (§30.3), always with live items and events

  @doc "The §30.3 `Note` of an opened note as `viewer` sees it (live items and events)."
  def note_json(%Note{} = n, viewer) do
    items = items(n.note_id)
    events = events(n.note_id, viewer)

    %{
      "note_id" => n.note_id,
      "conversation_id" => n.conversation_id,
      "chat_id" => n.chat_id,
      "source" => n.source,
      "call_id" => n.call_id,
      "media" => n.media,
      "duration_s" => n.duration_s,
      "started_at" => Clock.ts(n.started_at),
      "ended_at" => Clock.ts(n.ended_at),
      "with" => n.participants,
      "topic" => n.topic,
      "language" => n.language,
      "key_points" => n.key_points,
      "items" => Enum.map(items, &RisiMe.Agent.LedgerOut.item_json/1),
      "events" => events,
      "created_at" => Clock.ts(n.created_at),
      "call_ref" => n.call_ref,
      "made_by" => n.made_by
    }
  end

  @doc "The note's items (opened, live state), oldest first; [] when they can't be opened."
  def items(note_id) do
    Repo.all(
      from c in Commitment,
        where: c.summary_id == ^note_id,
        order_by: [asc: c.inserted_at, asc: c.id]
    )
    |> Commitment.open_all()
    |> case do
      {:ok, cs} -> cs
      :error -> []
    end
  end

  @doc "The note's active events as `EventRef`s for `viewer` (`my_status` nil if not theirs)."
  def events(note_id, viewer) do
    es =
      Repo.all(
        from e in Event,
          where: e.source_note_id == ^note_id and e.state == "active",
          order_by: [asc: e.start_at, asc: e.event_id]
      )

    mine =
      if es == [] or viewer == nil,
        do: %{},
        else:
          Repo.all(
            from p in Participant,
              where:
                p.event_id in ^Enum.map(es, & &1.event_id) and p.user_id == ^viewer and
                  p.removed == false,
              select: {p.event_id, p.status}
          )
          |> Map.new()

    for e <- es, {:ok, title} <- [Calendar.open_title(e)] do
      %{
        "event_id" => e.event_id,
        "title" => title,
        "start" => Clock.ts(e.start_at),
        "end" => Clock.ts(e.end_at),
        "all_day" => e.all_day,
        "my_status" => mine[e.event_id]
      }
    end
  end

  ## REST (§30.6)

  @doc """
  `GET /risi/notes`: the caller's notes, newest first, `q` a case-insensitive substring over
  topic, key points, item texts and participants' display names (over the caller's 500 most
  recent notes), `before` a note id (the page after it), `limit` 1–50 (20).
  `{:ok, %{"notes", "has_more"}}`, `{:error, :bad_request}` or `{:error, :agent_unavailable}`.
  """
  def list(user, params) do
    with {:ok, q} <- query_param(params["q"]),
         {:ok, limit} <- limit_param(params["limit"]),
         {:ok, before} <- before_param(params["before"]),
         {:ok, notes} <- recent(user) do
      notes =
        case before do
          nil -> notes
          id -> notes |> Enum.drop_while(&(&1.note_id != id)) |> Enum.drop(1)
        end

      ids = Enum.map(notes, & &1.note_id)
      items = items_by_note(ids)
      names = if q, do: CalendarCards.names(Enum.flat_map(notes, & &1.participants)), else: %{}
      notes = if q, do: Enum.filter(notes, &hit?(&1, q, items, names)), else: notes
      page = Enum.take(notes, limit)
      events = events_count(Enum.map(page, & &1.note_id))

      {:ok,
       %{
         "notes" => Enum.map(page, &summary(&1, items, events)),
         "has_more" => length(notes) > limit
       }}
    end
  end

  defp query_param(nil), do: {:ok, nil}

  defp query_param(q) when is_binary(q) do
    if String.length(q) in 1..100, do: {:ok, String.downcase(q)}, else: {:error, :bad_request}
  end

  defp query_param(_), do: {:error, :bad_request}

  defp limit_param(nil), do: {:ok, @default_limit}

  defp limit_param(l) when is_binary(l) do
    case Integer.parse(l) do
      {n, ""} when n in 1..50 -> {:ok, n}
      _ -> {:error, :bad_request}
    end
  end

  defp limit_param(l) when is_integer(l) and l in 1..50, do: {:ok, l}
  defp limit_param(_), do: {:error, :bad_request}

  defp before_param(nil), do: {:ok, nil}

  defp before_param(b) do
    case Ecto.UUID.cast(b) do
      {:ok, id} -> {:ok, id}
      :error -> {:error, :bad_request}
    end
  end

  # The caller's 500 most recent kept notes, opened.
  defp recent(user) do
    Repo.all(
      from n in Note,
        join: r in Recipient,
        on: r.note_id == n.note_id,
        where: r.user_id == ^user and r.deleted == false,
        order_by: [desc: r.ended_at, desc: n.note_id],
        limit: @search_window
    )
    |> open_all()
  end

  defp open_all([]), do: {:ok, []}

  defp open_all(notes) do
    case Seal.open_each(notes, &open/1) do
      {:ok, ns} ->
        {:ok, ns}

      :error ->
        Logger.warning("Risi notes unavailable: missing_key or wrong RISI_DATA_KEY")
        {:error, :agent_unavailable}
    end
  end

  defp items_by_note([]), do: %{}

  defp items_by_note(ids) do
    Repo.all(from c in Commitment, where: c.summary_id in ^ids)
    |> Commitment.open_all()
    |> case do
      {:ok, cs} -> cs
      :error -> []
    end
    |> Enum.group_by(& &1.summary_id)
  end

  defp events_count([]), do: %{}

  defp events_count(ids) do
    Repo.all(
      from e in Event,
        where: e.source_note_id in ^ids and e.state == "active",
        group_by: e.source_note_id,
        select: {e.source_note_id, count(e.event_id)}
    )
    |> Map.new()
  end

  defp hit?(n, q, items, names) do
    texts =
      [n.topic | n.key_points || []] ++
        Enum.map(Map.get(items, n.note_id, []), & &1.text) ++
        Enum.map(n.participants, &names[&1])

    Enum.any?(texts, &(is_binary(&1) and String.contains?(String.downcase(&1), q)))
  end

  defp summary(n, items, events) do
    is = Map.get(items, n.note_id, [])

    %{
      "note_id" => n.note_id,
      "conversation_id" => n.conversation_id,
      "with" => n.participants,
      "topic" => n.topic,
      "ended_at" => Clock.ts(n.ended_at),
      "source" => n.source,
      "items_count" => length(is),
      "open_items_count" => Enum.count(is, &((&1.item_state || &1.state) != "done")),
      "events_count" => Map.get(events, n.note_id, 0)
    }
  end

  @doc "`GET /risi/notes/{id}`: `{:ok, Note}`, `{:error, :not_found}` or `{:error, :agent_unavailable}`."
  def get(user, id) do
    with {:ok, n} <- kept(user, id) do
      case open(n) do
        {:ok, n} -> {:ok, note_json(n, user)}
        :error -> {:error, :agent_unavailable}
      end
    end
  end

  @doc "A note `user` keeps (not opened): `{:ok, note}` or `{:error, :not_found}`."
  def kept(user, id) do
    with {:ok, id} <- Ecto.UUID.cast(id),
         %Note{} = n <-
           Repo.one(
             from n in Note,
               join: r in Recipient,
               on: r.note_id == n.note_id,
               where: n.note_id == ^id and r.user_id == ^user and r.deleted == false
           ) do
      {:ok, n}
    else
      _ -> {:error, :not_found}
    end
  end

  @doc "The ids among `note_ids` that `user` still keeps (My promises' `note_id`)."
  def kept_ids(_user, []), do: MapSet.new()

  def kept_ids(user, note_ids) do
    Repo.all(
      from r in Recipient,
        where: r.user_id == ^user and r.deleted == false and r.note_id in ^Enum.uniq(note_ids),
        select: r.note_id
    )
    |> MapSet.new()
  end

  @doc """
  `DELETE /risi/notes/{id}`: off the caller's list only (items, promises and events stay); the
  row is purged when no recipient keeps it. `:ok` or `{:error, :not_found}`.
  """
  def delete(user, id) do
    with {:ok, id} <- Ecto.UUID.cast(id),
         {1, _} <-
           Repo.update_all(
             from(r in Recipient,
               where: r.note_id == ^id and r.user_id == ^user and r.deleted == false
             ),
             set: [deleted: true]
           ) do
      purge_unkept([id])
      :ok
    else
      _ -> {:error, :not_found}
    end
  end

  @doc "`DELETE /risi/notes`: all of the caller's notes. `:ok`."
  def delete_all(user) do
    {_, ids} =
      Repo.update_all(
        from(r in Recipient, where: r.user_id == ^user and r.deleted == false, select: r.note_id),
        set: [deleted: true]
      )

    purge_unkept(ids)
    :ok
  end

  defp purge_unkept([]), do: :ok

  defp purge_unkept(ids) do
    kept =
      from r in Recipient,
        where: r.note_id == parent_as(:note).note_id and r.deleted == false

    Repo.delete_all(
      from n in Note,
        as: :note,
        where: n.note_id in ^ids and not exists(subquery(kept))
    )

    :ok
  end

  ## Deletion and retention

  @doc "§30.7: Official off (§24.4) deletes the chat's notes (the cards on phones stay)."
  def forget(conv) do
    Repo.delete_all(from n in Note, where: n.conversation_id == ^conv)
    :ok
  end

  @doc "Hourly: notes older than 365 days, and notes nobody keeps, are purged."
  def prune(now \\ DateTime.utc_now()) do
    old = DateTime.add(now, -@keep_s, :second)
    Repo.delete_all(from n in Note, where: n.created_at < ^old)

    kept =
      from r in Recipient,
        where: r.note_id == parent_as(:note).note_id and r.deleted == false

    Repo.delete_all(from n in Note, as: :note, where: not exists(subquery(kept)))
    :ok
  end

  ## Requests (§30.2 trigger 3)

  @doc "The conversation's most recent note made within `s` seconds (or nil)."
  def recent_in(conv, s) do
    since = DateTime.add(Clock.now(), -s, :second)

    Repo.one(
      from n in Note,
        where: n.conversation_id == ^conv and n.created_at > ^since,
        order_by: [desc: n.created_at],
        limit: 1
    )
  end
end
