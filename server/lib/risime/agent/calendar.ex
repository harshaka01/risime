defmodule RisiMe.Agent.Calendar do
  @moduledoc """
  **Risi Calendar** (contract v1.29 §29, decision 073): RisiMe's own per-user calendar on the
  server, sealed at rest with `RISI_DATA_KEY` (not end-to-end encrypted: the server can read it).

  * **Switch:** `RISI_EVENTS` (`on?/0`, `/auth/config` `risi_events`). A **calendar user** has the
    switch on and a device advertising `risi_events` (`calendar_user?/1`).
  * **Events** (`risi_events`): `title_sealed` / `notes_sealed` only (AAD
    `risi_events:<event_id>:title|notes`); no plaintext text column anywhere. Participants
    (`risi_event_participants`) carry each person's status, own reminder and `removed`.
  * **Versions:** `version` counts the owner's changes (title, notes, time, zone, participants,
    cancel). A participant's own answer or reminder is not a new version, so an answer sent for
    the current version is never refused because someone else answered first.
  * **Sync:** every change writes one `risi_calendar_log` row per affected user (ids only, a
    per-user order under an advisory lock) and then the content-free `risi_calendar_changed`
    inbox event (≤ 1 per user per 5 s, coalesced, no push wake; `notify_changed/1`).
  * **Cards** (invites, updates, suggestions, reminders) are `RisiMe.Agent.CalendarCards`;
    reminders `RisiMe.Agent.CalendarReminders`.

  Titles never appear in logs, job args, inbox events or metrics: only in the sealed row, the
  REST replies and the MLS-encrypted Risi cards.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{CalendarCards, CalendarReminders, Clock, Seal}
  alias RisiMe.{Devices, Repo}

  defmodule Event do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:event_id, :binary_id, autogenerate: false}
    schema "risi_events" do
      field :owner, :binary_id
      field :title_sealed, :binary
      field :notes_sealed, :binary
      field :start_at, :utc_datetime_usec
      field :end_at, :utc_datetime_usec
      field :all_day, :boolean
      field :tz, :string
      field :created_by, :string
      field :state, :string, default: "active"
      field :version, :integer, default: 1
      field :source_conversation_id, :string
      field :source_message_ids, {:array, :binary_id}, default: []
      field :source_item_id, :binary_id
      field :source_note_id, :binary_id
      field :created_at, :utc_datetime_usec
      field :updated_at, :utc_datetime_usec
      field :cancelled_at, :utc_datetime_usec
    end
  end

  defmodule Participant do
    @moduledoc false
    use Ecto.Schema

    @primary_key false
    schema "risi_event_participants" do
      field :event_id, :binary_id, primary_key: true
      field :user_id, :binary_id, primary_key: true
      field :status, :string
      field :responded_at, :utc_datetime_usec
      field :reminder_min, :integer
      field :removed, :boolean, default: false
      field :schedule_v, :integer, default: 0
      field :inserted_at, :utc_datetime_usec
    end
  end

  defmodule Suggestion do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:suggestion_id, :binary_id, autogenerate: false}
    schema "risi_event_suggestions" do
      field :event_id, :binary_id
      field :user_id, :binary_id
      field :start_at, :utc_datetime_usec
      field :end_at, :utc_datetime_usec
      field :all_day, :boolean
      field :state, :string, default: "open"
      field :created_at, :utc_datetime_usec
    end
  end

  defmodule Settings do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:user_id, :binary_id, autogenerate: false}
    schema "risi_calendar_settings" do
      field :default_reminder_min, :integer, default: 30
      field :default_duration_min, :integer, default: 60
      field :digest_events, :boolean, default: true
      field :updated_at, :utc_datetime_usec
    end
  end

  @max_participants 20
  @max_events 5000
  @max_span_s 14 * 86_400
  @max_range_s 92 * 86_400
  @log_keep_s 30 * 86_400
  @purge_after_s 7 * 86_400
  @client_id_keep_s 24 * 3600
  @max_open_suggestions 3
  @changed_every_ms 5_000
  @default_settings %{
    "default_reminder_min" => 30,
    "default_duration_min" => 60,
    "digest_events" => true
  }

  ## Switch and users

  @doc "True while `RISI_EVENTS=on` (§29.1)."
  def on?, do: RisiMe.Risi.events_on?()

  @doc "A calendar user: the switch on and a `risi_events` device (§29.1)."
  def calendar_user?(user_id) when is_binary(user_id),
    do: on?() and Devices.risi_events_users([user_id]) != []

  def calendar_user?(_), do: false

  @doc "The calendar users among `user_ids`."
  def calendar_users(user_ids) do
    if on?(), do: Devices.risi_events_users(Enum.uniq(user_ids)), else: []
  end

  ## Settings

  @doc "The caller's settings (defaults when none are stored)."
  def settings(user_id) do
    case Repo.get(Settings, user_id) do
      nil ->
        @default_settings

      s ->
        %{
          "default_reminder_min" => s.default_reminder_min,
          "default_duration_min" => s.default_duration_min,
          "digest_events" => s.digest_events
        }
    end
  end

  @doc "`PATCH /risi/calendar/settings`: `{:ok, settings}` or `{:error, :bad_request}`."
  def put_settings(user_id, %{} = body) do
    keys = Map.keys(body)

    ok? =
      keys != [] and keys -- Map.keys(@default_settings) == [] and
        Enum.all?(body, fn
          {"default_reminder_min", v} -> is_nil(v) or (is_integer(v) and v in 0..10_080)
          {"default_duration_min", v} -> is_integer(v) and v in 5..1440
          {"digest_events", v} -> is_boolean(v)
        end)

    if ok? do
      s = Map.merge(settings(user_id), body)

      Repo.insert!(
        %Settings{
          user_id: user_id,
          default_reminder_min: s["default_reminder_min"],
          default_duration_min: s["default_duration_min"],
          digest_events: s["digest_events"],
          updated_at: DateTime.utc_now()
        },
        on_conflict:
          {:replace, [:default_reminder_min, :default_duration_min, :digest_events, :updated_at]},
        conflict_target: [:user_id]
      )

      {:ok, s}
    else
      {:error, :bad_request}
    end
  end

  def put_settings(_user_id, _body), do: {:error, :bad_request}

  ## Reading

  @doc """
  `GET /risi/calendar/events?from&to`: `{:ok, %{"events", "cursor"}}` (sorted by start), or
  `{:error, :bad_request | :agent_unavailable}`.
  """
  def list(user_id, from, to) do
    with {:ok, f} <- parse_ts(from),
         {:ok, t} <- parse_ts(to),
         true <- DateTime.compare(f, t) == :lt and DateTime.diff(t, f) <= @max_range_s,
         cursor = cursor(user_id),
         events = range(user_id, f, t),
         {:ok, views} <- views(events, user_id) do
      {:ok, %{"events" => views, "cursor" => cursor}}
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  # The caller's events (not removed) overlapping [from, to), sorted by start.
  defp range(user_id, from, to) do
    Repo.all(
      from e in Event,
        join: p in Participant,
        on: p.event_id == e.event_id,
        where:
          p.user_id == ^user_id and p.removed == false and e.start_at < ^to and
            e.end_at > ^from,
        order_by: [asc: e.start_at, asc: e.event_id],
        select: e
    )
  end

  @doc "`GET /risi/calendar/events/{id}`: `{:ok, view}` or `{:error, :not_found | :agent_unavailable}`."
  def get(user_id, event_id) do
    with {:ok, id} <- Ecto.UUID.cast(event_id),
         {%Event{} = e, parts} <- load(id),
         %Participant{removed: false} <- Enum.find(parts, &(&1.user_id == user_id)) do
      view(e, parts, user_id)
    else
      _ -> {:error, :not_found}
    end
  end

  @doc """
  The caller's own events (accepted and proposed; declined, removed and cancelled excluded)
  overlapping [from, to): `{:ok, [%{event, status, title}]}` (titles opened) or `:error` (the
  rows can't be opened). Used by `risi_calendar_check` and the digest.
  """
  def own_events(user_id, from, to) do
    rows =
      Repo.all(
        from e in Event,
          join: p in Participant,
          on: p.event_id == e.event_id,
          where:
            p.user_id == ^user_id and p.removed == false and
              p.status in ["accepted", "proposed"] and e.state == "active" and
              e.start_at < ^to and e.end_at > ^from,
          order_by: [asc: e.start_at, asc: e.event_id],
          select: {e, p.status}
      )

    with {:ok, _key} <- Seal.data() do
      Seal.open_each(rows, fn {e, status} ->
        case open_title(e) do
          {:ok, title} -> {:ok, %{event: e, status: status, title: title}}
          _ -> :error
        end
      end)
    end
  end

  @doc """
  `GET /risi/calendar/changes?since&limit`: `{:ok, %{"changes", "cursor", "has_more"}}`, or
  `{:error, :cursor_expired | :bad_request | :agent_unavailable}`. Coalesced per event (the
  latest view only), in cursor order.
  """
  def changes(user_id, since, limit) do
    with {:ok, seq} <- decode_cursor(since),
         {:ok, limit} <- limit(limit) do
      rows =
        Repo.all(
          from l in "risi_calendar_log",
            where: l.user_id == type(^user_id, :binary_id) and l.seq > ^seq,
            group_by: l.event_id,
            order_by: [asc: max(l.seq)],
            limit: ^(limit + 1),
            select: {type(l.event_id, :binary_id), max(l.seq)}
        )

      {page, more} = Enum.split(rows, limit)
      last = if page == [], do: seq, else: page |> List.last() |> elem(1)

      changes =
        Seal.open_each(page, fn {id, _seq} ->
          case load(id) do
            {%Event{} = e, parts} ->
              case Enum.find(parts, &(&1.user_id == user_id)) do
                %Participant{removed: false} ->
                  with {:ok, v} <- view(e, parts, user_id), do: {:ok, %{"event" => v}}

                _ ->
                  {:ok, %{"event_id" => id, "removed" => true}}
              end

            nil ->
              {:ok, %{"event_id" => id, "removed" => true}}
          end
        end)

      case changes do
        {:ok, changes} ->
          {:ok,
           %{
             "changes" => changes,
             "cursor" => encode_cursor(last),
             "has_more" => more != []
           }}

        :error ->
          {:error, :agent_unavailable}
      end
    end
  end

  defp limit(nil), do: {:ok, 100}
  defp limit(n) when is_integer(n) and n in 1..500, do: {:ok, n}

  defp limit(s) when is_binary(s) do
    case Integer.parse(s) do
      {n, ""} -> limit(n)
      _ -> {:error, :bad_request}
    end
  end

  defp limit(_), do: {:error, :bad_request}

  ## Cursors (opaque: the per-user log position and when it was handed out)

  @doc "The caller's current cursor (the end of their change log, now)."
  def cursor(user_id) do
    seq =
      Repo.one(
        from l in "risi_calendar_log",
          where: l.user_id == type(^user_id, :binary_id),
          select: max(l.seq)
      ) || 0

    encode_cursor(seq)
  end

  defp encode_cursor(seq),
    do:
      "rc1." <>
        Base.url_encode64("#{seq}.#{System.system_time(:millisecond)}", padding: false)

  defp decode_cursor("rc1." <> b64) do
    with {:ok, raw} <- Base.url_decode64(b64, padding: false),
         [s, ms] <- String.split(raw, "."),
         {seq, ""} when seq >= 0 <- Integer.parse(s),
         {ms, ""} <- Integer.parse(ms) do
      # Older than the 30-day change log: the phone lists again (§29.3).
      if System.system_time(:millisecond) - ms > @log_keep_s * 1000,
        do: {:error, :cursor_expired},
        else: {:ok, seq}
    else
      _ -> {:error, :bad_request}
    end
  end

  defp decode_cursor(_), do: {:error, :bad_request}

  ## Creating

  @doc """
  `POST /risi/calendar/events` (`created_by: "user"`): `{:ok, :created | :existing, view}` or
  `{:error, :bad_request | :not_invitable | :agent_unavailable}`. A repeated `client_event_id`
  (24 h) returns the same event.
  """
  def create_rest(user_id, %{"client_event_id" => cid} = body) do
    allowed = ~w(client_event_id title notes start end all_day tz with reminder_min source)

    with {:ok, cid} <- Ecto.UUID.cast(cid),
         true <- Map.keys(body) -- allowed == [] || {:error, :bad_request} do
      case existing_client_id(user_id, cid) do
        nil ->
          with {:ok, attrs} <- parse_new(user_id, body),
               {:ok, view} <- create(user_id, Map.put(attrs, :client_event_id, cid)) do
            {:ok, :created, view}
          end

        event_id ->
          with {:ok, v} <- get(user_id, event_id), do: {:ok, :existing, v}
      end
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  def create_rest(_user_id, _body), do: {:error, :bad_request}

  defp existing_client_id(user_id, cid) do
    since = DateTime.add(DateTime.utc_now(), -@client_id_keep_s, :second)

    Repo.one(
      from c in "risi_calendar_client_ids",
        where:
          c.user_id == type(^user_id, :binary_id) and c.client_event_id == type(^cid, :binary_id) and
            c.inserted_at > ^since,
        select: type(c.event_id, :binary_id)
    )
  end

  @doc """
  Parses a create body (REST, or a confirmed `risi_calendar_add` card's args):
  `{:ok, attrs}` or `{:error, :bad_request | :not_invitable}`.
  """
  def parse_new(user_id, body) do
    with {:ok, title} <- text(body["title"], 1, 200),
         {:ok, notes} <- opt_text(body["notes"], 2000),
         {:ok, tz} <- zone(body["tz"]),
         {:ok, start} <- parse_ts(body["start"]),
         {:ok, stop} <- parse_ts(body["end"]),
         all_day when is_boolean(all_day) <- Map.get(body, "all_day", false),
         :ok <- span_ok(start, stop, all_day, tz),
         {:ok, with} <- uuids(Map.get(body, "with", []), @max_participants),
         {:ok, rem} <- reminder(Map.get(body, "reminder_min", :default)),
         {:ok, source} <- source(user_id, body["source"]) do
      {:ok,
       %{
         title: title,
         notes: notes,
         tz: tz,
         start: start,
         stop: stop,
         all_day: all_day,
         with: with -- [user_id],
         reminder_min: rem,
         source: source,
         created_by: "user"
       }}
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  defp text(s, min, max) when is_binary(s) do
    t = String.trim(s)
    if String.length(t) in min..max, do: {:ok, t}, else: {:error, :bad_request}
  end

  defp text(_, _, _), do: {:error, :bad_request}

  defp opt_text(nil, _max), do: {:ok, nil}

  defp opt_text(s, max) when is_binary(s),
    do: if(String.trim(s) == "", do: {:ok, nil}, else: text(s, 1, max))

  defp opt_text(_, _), do: {:error, :bad_request}

  defp zone(tz) when is_binary(tz),
    do: if(RisiMe.Accounts.valid_tz?(tz), do: {:ok, tz}, else: {:error, :bad_request})

  defp zone(_), do: {:error, :bad_request}

  defp reminder(:default), do: {:ok, :default}
  defp reminder(nil), do: {:ok, nil}
  defp reminder(n) when is_integer(n) and n in 0..10_080, do: {:ok, n}
  defp reminder(_), do: {:error, :bad_request}

  defp uuids(list, max) when is_list(list) and length(list) <= max do
    Seal.open_each(list, fn
      s when is_binary(s) -> Ecto.UUID.cast(s)
      _ -> :error
    end)
    |> case do
      {:ok, ids} -> {:ok, Enum.uniq(ids)}
      :error -> {:error, :bad_request}
    end
  end

  defp uuids(_, _), do: {:error, :bad_request}

  @doc false
  def parse_ts(s) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, dt, _} -> {:ok, Clock.usec(DateTime.shift_zone!(dt, "Etc/UTC"))}
      _ -> {:error, :bad_request}
    end
  end

  def parse_ts(_), do: {:error, :bad_request}

  # start < end, ≤ 14 days; an all-day event runs from 00:00 to 00:00 in its zone.
  defp span_ok(start, stop, all_day, tz) do
    cond do
      DateTime.compare(start, stop) != :lt -> {:error, :bad_request}
      DateTime.diff(stop, start) > @max_span_s -> {:error, :bad_request}
      all_day and not (midnight?(start, tz) and midnight?(stop, tz)) -> {:error, :bad_request}
      true -> :ok
    end
  end

  defp midnight?(t, tz), do: NaiveDateTime.to_time(Clock.local(t, tz)) == ~T[00:00:00]

  # §29.3: an Official conversation or the caller's Risi chat where the caller is an active
  # member (a Private or `dm:` id is always refused).
  defp source(_user_id, nil), do: {:ok, %{conversation_id: nil, message_ids: [], item_id: nil}}

  defp source(user_id, %{"conversation_id" => conv} = s) do
    allowed = ~w(conversation_id message_ids item_id)

    with true <- Map.keys(s) -- allowed == [],
         true <- is_binary(conv) and String.starts_with?(conv, "grp:"),
         true <- RisiMe.Agent.official?(conv),
         true <- RisiMe.Agent.Secretary.active_human?(conv, user_id),
         {:ok, mids} <- uuids(Map.get(s, "message_ids") || [], 20),
         {:ok, item} <- opt_uuid(s["item_id"]) do
      {:ok, %{conversation_id: conv, message_ids: mids, item_id: item}}
    else
      _ -> {:error, :bad_request}
    end
  end

  defp source(_user_id, _), do: {:error, :bad_request}

  defp opt_uuid(nil), do: {:ok, nil}
  defp opt_uuid(s), do: Ecto.UUID.cast(s)

  @doc """
  Creates an event owned by `owner`. `attrs`: `title`, `notes`, `tz`, `start`, `stop`,
  `all_day`, `with` (user ids), `reminder_min` (`:default` = the owner's setting), `source`
  (`%{conversation_id, message_ids, item_id}`, optional `note_id`), `created_by` (`"user"`:
  the owner `accepted`, the others `proposed` and invited; `"risi"`: everyone `proposed`, the
  caller sends the invites), optional `client_event_id` and `invites: false`.

  Participants that are not invitable: `{:error, :not_invitable}` (REST); with `drop: true`
  they are left out (cards: "Couldn't invite <name>", see `invitable/2`).
  `{:ok, view}` (the owner's view) or `{:error, reason}`.
  """
  def create(owner, attrs) do
    with {:ok, key} <- key(),
         {:ok, others} <- invitees(owner, attrs.with, Map.get(attrs, :drop, false)),
         :ok <- under_limit([owner | others]) do
      id = Ecto.UUID.generate()
      now = DateTime.utc_now()
      risi? = attrs.created_by == "risi"
      source = attrs.source || %{}

      event = %Event{
        event_id: id,
        owner: owner,
        title_sealed: Seal.seal(key, Seal.aad("risi_events", id, "title"), attrs.title),
        notes_sealed:
          attrs[:notes] && Seal.seal(key, Seal.aad("risi_events", id, "notes"), attrs.notes),
        start_at: attrs.start,
        end_at: attrs.stop,
        all_day: attrs.all_day,
        tz: attrs.tz,
        created_by: attrs.created_by,
        state: "active",
        version: 1,
        source_conversation_id: source[:conversation_id],
        source_message_ids: source[:message_ids] || [],
        source_item_id: source[:item_id],
        source_note_id: source[:note_id],
        created_at: now,
        updated_at: now
      }

      users = [owner | others]
      defaults = default_reminders(users)

      parts =
        for u <- users do
          rem =
            if u == owner and attrs.reminder_min != :default,
              do: attrs.reminder_min,
              else: defaults[u]

          status = if u == owner and not risi?, do: "accepted", else: "proposed"

          %{
            event_id: id,
            user_id: u,
            status: status,
            responded_at: if(status == "accepted", do: now),
            reminder_min: rem,
            removed: false,
            schedule_v: 0,
            inserted_at: now
          }
        end

      Repo.transaction(fn ->
        Repo.insert!(event)
        Repo.insert_all(Participant, parts)

        if cid = attrs[:client_event_id],
          do:
            Repo.insert_all(
              "risi_calendar_client_ids",
              [
                %{
                  user_id: Ecto.UUID.dump!(owner),
                  client_event_id: Ecto.UUID.dump!(cid),
                  event_id: Ecto.UUID.dump!(id),
                  inserted_at: now
                }
              ],
              on_conflict: :nothing
            )

        log!(users, id)
      end)

      after_change(id, users)
      {e, ps} = load(id)
      CalendarReminders.schedule_all(e, ps)

      if not risi? and Map.get(attrs, :invites, true),
        do: for(u <- others, do: CalendarCards.invite(e, ps, u, "new", owner))

      view(e, ps, owner)
    end
  end

  defp default_reminders(users) do
    stored =
      Repo.all(
        from s in Settings,
          where: s.user_id in ^users,
          select: {s.user_id, s.default_reminder_min}
      )
      |> Map.new()

    Map.new(users, fn u -> {u, Map.get(stored, u, 30)} end)
  end

  defp under_limit(users) do
    counts =
      Repo.all(
        from p in Participant,
          join: e in Event,
          on: e.event_id == p.event_id,
          where: p.user_id in ^users and p.removed == false and e.state == "active",
          group_by: p.user_id,
          select: {p.user_id, count(p.event_id)}
      )

    if Enum.any?(counts, fn {_u, n} -> n >= @max_events end),
      do: {:error, :bad_request},
      else: :ok
  end

  # The invitable participants (§29.2): `{:ok, ids}`; a non-invitable one is
  # `{:error, :not_invitable}`, or dropped with `drop?`.
  defp invitees(owner, with, drop?) do
    others = Enum.uniq(with) -- [owner]
    {ok, bad} = Enum.split_with(others, &invitable?(owner, &1))

    cond do
      length(others) + 1 > @max_participants -> {:error, :bad_request}
      bad == [] or drop? -> {:ok, ok}
      true -> {:error, :not_invitable}
    end
  end

  @doc """
  §29.2: `user` shares at least one active chat with `owner` (a DM friend, or any active group
  where both are active human members), and is a human user.
  """
  def invitable?(owner, user) when owner == user, do: true

  def invitable?(owner, user) do
    human? =
      Repo.exists?(
        from u in RisiMe.Accounts.User, where: u.id == ^user and u.kind == "user", select: 1
      )

    human? and
      (RisiMe.Social.friends?(owner, user) or
         Repo.exists?(
           from a in RisiMe.Groups.Member,
             join: b in RisiMe.Groups.Member,
             on: b.group_id == a.group_id,
             join: g in RisiMe.Groups.Group,
             on: g.id == a.group_id,
             where:
               a.user_id == ^owner and b.user_id == ^user and a.state == "active" and
                 b.state == "active" and a.kind == "user" and b.kind == "user" and
                 g.state == "active",
             select: 1
         ))
  end

  @doc "`{ok_ids, dropped_ids}` of `ids` for `owner` (cards drop the non-invitable ones)."
  def invitable(owner, ids) do
    ids = Enum.uniq(ids) -- [owner]
    Enum.split_with(ids, &invitable?(owner, &1))
  end

  ## Changing (PATCH)

  @owner_keys ~w(title notes start end all_day tz add remove)

  @doc """
  `PATCH /risi/calendar/events/{id}`: `{:ok, view}`, `{:error, {:version_conflict, view}}`,
  `{:error, :not_owner | :not_found | :bad_request | :not_invitable | :agent_unavailable}`.
  """
  def patch(user_id, event_id, %{"version" => v} = body) when is_integer(v) do
    allowed = ["version", "reminder_min" | @owner_keys]

    with true <- Map.keys(body) -- allowed == [] || {:error, :bad_request},
         {:ok, e, parts, me} <- mine(user_id, event_id),
         :ok <- version_ok(e, parts, user_id, v),
         owner_change = Map.take(body, @owner_keys),
         true <- (owner_change == %{} or e.owner == user_id) || {:error, :not_owner},
         true <- e.state == "active" || {:error, :bad_request},
         {:ok, rem} <- patch_reminder(body) do
      if owner_change == %{} do
        own_reminder(e, me, rem)
      else
        owner_patch(e, parts, owner_change, rem)
      end
    else
      {:error, _} = err -> err
      _ -> {:error, :bad_request}
    end
  end

  def patch(_user_id, _event_id, _body), do: {:error, :bad_request}

  defp patch_reminder(%{"reminder_min" => r}), do: reminder(r)
  defp patch_reminder(_), do: {:ok, :keep}

  defp mine(user_id, event_id) do
    with {:ok, id} <- Ecto.UUID.cast(event_id),
         {%Event{} = e, parts} <- load(id),
         %Participant{removed: false} = me <- Enum.find(parts, &(&1.user_id == user_id)) do
      {:ok, e, parts, me}
    else
      _ -> {:error, :not_found}
    end
  end

  defp version_ok(%Event{version: v}, _parts, _user, v), do: :ok

  defp version_ok(e, parts, user, _v) do
    case view(e, parts, user) do
      {:ok, view} -> {:error, {:version_conflict, view}}
      error -> error
    end
  end

  defp own_reminder(e, me, :keep), do: view_of(e.event_id, me.user_id)

  defp own_reminder(e, me, rem) do
    Repo.update_all(
      from(p in Participant, where: p.event_id == ^e.event_id and p.user_id == ^me.user_id),
      set: [reminder_min: rem]
    )

    log!([me.user_id], e.event_id)
    after_change(e.event_id, [me.user_id])
    {e, ps} = load(e.event_id)
    CalendarReminders.schedule(e, Enum.find(ps, &(&1.user_id == me.user_id)))
    view(e, ps, me.user_id)
  end

  defp owner_patch(e, parts, ch, rem) do
    with {:ok, key} <- key(),
         {:ok, cur} <- open_event(e),
         {:ok, title} <-
           if(Map.has_key?(ch, "title"), do: text(ch["title"], 1, 200), else: {:ok, cur.title}),
         {:ok, notes} <-
           if(Map.has_key?(ch, "notes"), do: opt_text(ch["notes"], 2000), else: {:ok, cur.notes}),
         {:ok, tz} <- if(Map.has_key?(ch, "tz"), do: zone(ch["tz"]), else: {:ok, e.tz}),
         {:ok, start} <-
           if(Map.has_key?(ch, "start"), do: parse_ts(ch["start"]), else: {:ok, e.start_at}),
         {:ok, stop} <-
           if(Map.has_key?(ch, "end"), do: parse_ts(ch["end"]), else: {:ok, e.end_at}),
         all_day when is_boolean(all_day) <- Map.get(ch, "all_day", e.all_day),
         :ok <- span_ok(start, stop, all_day, tz),
         {:ok, add} <- uuids(Map.get(ch, "add", []), @max_participants),
         {:ok, remove} <- uuids(Map.get(ch, "remove", []), @max_participants),
         rows = Enum.map(parts, & &1.user_id),
         active = for(p <- parts, not p.removed, do: p.user_id),
         remove = Enum.filter(remove -- [e.owner], &(&1 in rows)),
         add = (add -- active) -- remove,
         {:ok, add} <- invitees(e.owner, add, false),
         true <-
           length(Enum.uniq((rows -- remove) ++ add)) <= @max_participants ||
             {:error, :bad_request} do
      time? =
        DateTime.compare(start, e.start_at) != :eq or DateTime.compare(stop, e.end_at) != :eq or
          all_day != e.all_day

      changed? =
        time? or title != cur.title or notes != cur.notes or tz != e.tz or add != [] or
          remove != []

      now = DateTime.utc_now()

      if changed? do
        defaults = default_reminders(add)

        Repo.transaction(fn ->
          Repo.update_all(from(x in Event, where: x.event_id == ^e.event_id),
            set: [
              title_sealed: Seal.seal(key, Seal.aad("risi_events", e.event_id, "title"), title),
              notes_sealed:
                notes && Seal.seal(key, Seal.aad("risi_events", e.event_id, "notes"), notes),
              start_at: start,
              end_at: stop,
              all_day: all_day,
              tz: tz,
              updated_at: now
            ],
            inc: [version: 1]
          )

          if time?,
            do:
              Repo.update_all(
                from(p in Participant,
                  where:
                    p.event_id == ^e.event_id and p.user_id != ^e.owner and p.removed == false
                ),
                set: [status: "proposed", responded_at: nil]
              )

          if remove != [] do
            Repo.delete_all(
              from p in Participant, where: p.event_id == ^e.event_id and p.user_id in ^remove
            )

            Repo.delete_all(
              from i in "risi_calendar_invites_pending",
                where:
                  i.event_id == type(^e.event_id, :binary_id) and
                    i.user_id in type(^remove, {:array, :binary_id})
            )
          end

          if add != [],
            do:
              Repo.insert_all(
                Participant,
                for u <- add do
                  %{
                    event_id: e.event_id,
                    user_id: u,
                    status: "proposed",
                    reminder_min: defaults[u],
                    removed: false,
                    schedule_v: 0,
                    inserted_at: now
                  }
                end,
                on_conflict: {:replace, [:status, :removed, :responded_at]},
                conflict_target: [:event_id, :user_id]
              )

          if rem != :keep,
            do:
              Repo.update_all(
                from(p in Participant,
                  where: p.event_id == ^e.event_id and p.user_id == ^e.owner
                ),
                set: [reminder_min: rem]
              )

          log!(Enum.uniq(rows ++ add), e.event_id)
        end)

        affected = Enum.uniq(rows ++ add)
        after_change(e.event_id, affected)
        {e2, ps} = load(e.event_id)
        CalendarReminders.schedule_all(e2, ps)
        for u <- remove, do: CalendarReminders.cancel(e.event_id, u)

        others = for p <- ps, p.user_id != e.owner, not p.removed, do: p.user_id

        cond do
          time? ->
            for u <- others -- add, do: CalendarCards.invite(e2, ps, u, "time_changed", e.owner)
            CalendarCards.update(e2, ps, "time", e.owner)

          add != [] or remove != [] ->
            CalendarCards.update(e2, ps, "participants", e.owner)

          true ->
            CalendarCards.update(e2, ps, "title", e.owner)
        end

        for u <- add, do: CalendarCards.invite(e2, ps, u, "added", e.owner)
        view(e2, ps, e.owner)
      else
        me = Enum.find(parts, &(&1.user_id == e.owner))
        own_reminder(e, me, rem)
      end
    else
      {:error, _} = err -> err
      _ -> {:error, :bad_request}
    end
  end

  ## Deleting

  @doc """
  `DELETE /risi/calendar/events/{id}` (§29.4): the owner cancels it for everyone; a participant
  declines and removes it from their own calendar. `:ok` or `{:error, :not_found}`.
  """
  def delete(user_id, event_id) do
    with {:ok, e, parts, _me} <- mine(user_id, event_id) do
      if e.owner == user_id, do: cancel(e, parts, user_id), else: leave(e, parts, user_id)
      :ok
    end
  end

  @doc "Cancels an event for everyone (`by`: the owner, or nil for Risi)."
  def cancel(%Event{state: "cancelled"}, _parts, _by), do: :ok

  def cancel(e, parts, by) do
    now = DateTime.utc_now()
    users = for p <- parts, do: p.user_id

    Repo.transaction(fn ->
      Repo.update_all(from(x in Event, where: x.event_id == ^e.event_id),
        set: [state: "cancelled", cancelled_at: now, updated_at: now],
        inc: [version: 1]
      )

      Repo.delete_all(
        from i in "risi_calendar_invites_pending",
          where: i.event_id == type(^e.event_id, :binary_id)
      )

      Repo.update_all(
        from(s in Suggestion, where: s.event_id == ^e.event_id and s.state == "open"),
        set: [state: "closed"]
      )

      log!(users, e.event_id)
    end)

    after_change(e.event_id, users)
    for u <- users, do: CalendarReminders.cancel(e.event_id, u)
    {e2, ps} = load(e.event_id)
    CalendarCards.update(e2, ps, "cancelled", by, except: by)
    :ok
  end

  # A participant's delete: declined and removed from their own calendar.
  defp leave(e, parts, user_id) do
    now = DateTime.utc_now()
    users = for p <- parts, not p.removed or p.user_id == user_id, do: p.user_id

    Repo.transaction(fn ->
      Repo.update_all(
        from(p in Participant, where: p.event_id == ^e.event_id and p.user_id == ^user_id),
        set: [status: "declined", removed: true, responded_at: now]
      )

      Repo.delete_all(
        from i in "risi_calendar_invites_pending",
          where:
            i.event_id == type(^e.event_id, :binary_id) and
              i.user_id == type(^user_id, :binary_id)
      )

      log!(users, e.event_id)
    end)

    after_change(e.event_id, users)
    CalendarReminders.cancel(e.event_id, user_id)
    {e2, ps} = load(e.event_id)
    CalendarCards.update(e2, ps, "status", user_id, except: user_id)
    :ok
  end

  @doc """
  `DELETE /risi/calendar` (§29.3): the caller leaves every event, cancels the ones they own, and
  their settings go. `:ok`.
  """
  def delete_all(user_id) do
    ids =
      Repo.all(
        from p in Participant,
          where: p.user_id == ^user_id and p.removed == false,
          select: p.event_id
      )

    for id <- ids, {%Event{} = e, parts} <- [load(id)] do
      if e.owner == user_id, do: cancel(e, parts, user_id), else: leave(e, parts, user_id)
    end

    Repo.delete_all(from s in Settings, where: s.user_id == ^user_id)

    Repo.delete_all(
      from i in "risi_calendar_invites_pending", where: i.user_id == type(^user_id, :binary_id)
    )

    :ok
  end

  ## Responding

  @doc """
  `POST /risi/calendar/events/{id}/respond` (§29.3): `{:ok, view}`,
  `{:error, {:version_conflict, view}}`, `{:error, :not_found | :bad_request}`.
  """
  def respond(user_id, event_id, %{"response" => r, "version" => v} = body)
      when r in ~w(accept decline suggest) and is_integer(v) do
    with true <-
           Map.keys(body) -- ~w(response version suggest reminder_min) == [] ||
             {:error, :bad_request},
         {:ok, e, parts, me} <- mine(user_id, event_id),
         :ok <- version_ok(e, parts, user_id, v),
         true <- e.state == "active" || {:error, :bad_request},
         {:ok, rem} <- patch_reminder(body) do
      case r do
        "accept" -> answer(e, me, "accepted", rem)
        "decline" -> answer(e, me, "declined", rem)
        "suggest" -> suggest(e, parts, me, body["suggest"])
      end
    else
      {:error, _} = err -> err
      _ -> {:error, :bad_request}
    end
  end

  def respond(_user_id, _event_id, _body), do: {:error, :bad_request}

  @doc """
  A participant's accept/decline (REST or a card's `risi_action`): idempotent, may be changed
  later. `rem`: `:keep` or the participant's reminder for this event. `{:ok, view}`.
  """
  def answer(e, me, status, rem) do
    now = DateTime.utc_now()
    changed? = me.status != status

    sets =
      [status: status] ++
        if(changed?, do: [responded_at: now], else: []) ++
        if(rem != :keep, do: [reminder_min: rem], else: [])

    if changed? or rem != :keep do
      Repo.transaction(fn ->
        Repo.update_all(
          from(p in Participant, where: p.event_id == ^e.event_id and p.user_id == ^me.user_id),
          set: sets
        )

        Repo.delete_all(
          from i in "risi_calendar_invites_pending",
            where:
              i.event_id == type(^e.event_id, :binary_id) and
                i.user_id == type(^me.user_id, :binary_id)
        )

        users = if changed?, do: participant_ids(e.event_id), else: [me.user_id]
        log!(users, e.event_id)
      end)

      users = if changed?, do: participant_ids(e.event_id), else: [me.user_id]
      after_change(e.event_id, users)
    end

    {e2, ps} = load(e.event_id)
    CalendarReminders.schedule(e2, Enum.find(ps, &(&1.user_id == me.user_id)))
    if changed?, do: CalendarCards.update(e2, ps, "status", me.user_id)
    view(e2, ps, me.user_id)
  end

  defp participant_ids(event_id),
    do:
      Repo.all(
        from p in Participant,
          where: p.event_id == ^event_id and p.removed == false,
          select: p.user_id
      )

  @doc "A participant (not the owner) suggests another time: `{:ok, view}` or `{:error, :bad_request}`."
  def suggest(e, _parts, me, %{"start" => s, "end" => t, "all_day" => ad} = sug)
      when is_boolean(ad) and map_size(sug) == 3 do
    open =
      Repo.aggregate(
        from(x in Suggestion,
          where: x.event_id == ^e.event_id and x.user_id == ^me.user_id and x.state == "open"
        ),
        :count
      )

    with true <- me.user_id != e.owner,
         true <- open < @max_open_suggestions,
         {:ok, start} <- parse_ts(s),
         {:ok, stop} <- parse_ts(t),
         :ok <- span_ok(start, stop, ad, e.tz) do
      sid = Ecto.UUID.generate()

      Repo.insert!(%Suggestion{
        suggestion_id: sid,
        event_id: e.event_id,
        user_id: me.user_id,
        start_at: start,
        end_at: stop,
        all_day: ad,
        state: "open",
        created_at: DateTime.utc_now()
      })

      # The suggester stays (or goes back to) `proposed`.
      if me.status != "proposed" do
        Repo.update_all(
          from(p in Participant, where: p.event_id == ^e.event_id and p.user_id == ^me.user_id),
          set: [status: "proposed", responded_at: DateTime.utc_now()]
        )

        log!(participant_ids(e.event_id), e.event_id)
        after_change(e.event_id, participant_ids(e.event_id))
      end

      {e2, ps} = load(e.event_id)
      CalendarReminders.schedule(e2, Enum.find(ps, &(&1.user_id == me.user_id)))
      CalendarCards.suggestion(e2, ps, Repo.get!(Suggestion, sid))
      view(e2, ps, me.user_id)
    else
      _ -> {:error, :bad_request}
    end
  end

  def suggest(_e, _parts, _me, _sug), do: {:error, :bad_request}

  @doc """
  `POST /risi/calendar/suggestions/{id}/resolve` (owner only): `use` moves the event to the
  suggested time (others back to `proposed` and re-invited, the suggester `accepted`); `keep`
  tells the suggester the original time stays. `{:ok, view}` or `{:error, reason}`.
  """
  def resolve(user_id, suggestion_id, %{"action" => action} = body)
      when action in ~w(use keep) and map_size(body) == 1 do
    with {:ok, sid} <- Ecto.UUID.cast(suggestion_id),
         %Suggestion{} = s <- Repo.get(Suggestion, sid),
         {%Event{} = e, parts} <- load(s.event_id),
         %Participant{removed: false} <- Enum.find(parts, &(&1.user_id == user_id)) do
      cond do
        e.owner != user_id ->
          {:error, :not_owner}

        s.state != "open" or e.state != "active" ->
          view(e, parts, user_id)

        action == "keep" ->
          Repo.update_all(from(x in Suggestion, where: x.suggestion_id == ^sid),
            set: [state: "kept"]
          )

          CalendarCards.kept(e, parts, s)
          view(e, parts, user_id)

        true ->
          use_suggestion(e, parts, s)
      end
    else
      {:error, _} = err -> err
      _ -> {:error, :not_found}
    end
  end

  def resolve(_user_id, _sid, _body), do: {:error, :bad_request}

  defp use_suggestion(e, parts, s) do
    body = %{
      "start" => Clock.ts(s.start_at),
      "end" => Clock.ts(s.end_at),
      "all_day" => s.all_day
    }

    case owner_patch(e, parts, body, :keep) do
      {:ok, _} ->
        Repo.update_all(from(x in Suggestion, where: x.suggestion_id == ^s.suggestion_id),
          set: [state: "used"]
        )

        {e2, ps} = load(e.event_id)

        case Enum.find(ps, &(&1.user_id == s.user_id and not &1.removed)) do
          nil -> :ok
          me -> answer(e2, me, "accepted", :keep)
        end

        {e3, ps3} = load(e.event_id)
        view(e3, ps3, e.owner)

      error ->
        error
    end
  end

  ## Risi-made events and Official off

  @doc """
  §29.4: Official turned off for `conv`: the Risi-made events of that chat that **no one
  accepted** are cancelled; accepted ones stay (they are the users' own).
  """
  def forget_conversation(conv) do
    if is_binary(conv) do
      ids =
        Repo.all(
          from e in Event,
            where:
              e.source_conversation_id == ^conv and e.created_by == "risi" and
                e.state == "active",
            select: e.event_id
        )

      for id <- ids,
          {%Event{} = e, parts} <- [load(id)],
          not Enum.any?(parts, &(&1.status == "accepted")),
          do: cancel(e, parts, nil)
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi calendar forget failed: #{inspect(e.__struct__)}")
      :ok
  end

  ## Loading and views

  @doc "An event and its participants (or nil)."
  def load(id) do
    case Repo.get(Event, id) do
      nil ->
        nil

      e ->
        parts =
          Repo.all(from p in Participant, where: p.event_id == ^id)
          |> Enum.sort_by(&{&1.user_id != e.owner, &1.inserted_at, &1.user_id})

        {e, parts}
    end
  end

  @doc "The opened title and notes of an event: `{:ok, %{title, notes}}` or `:error`."
  def open_event(%Event{} = e) do
    with {:ok, title} <- open_title(e),
         {:ok, notes} <- Seal.open_text("risi_events", e.event_id, "notes", e.notes_sealed) do
      {:ok, %{title: title, notes: notes}}
    else
      _ -> :error
    end
  end

  @doc "The opened title: `{:ok, title}` or `:error`."
  def open_title(%Event{} = e),
    do: Seal.open_text("risi_events", e.event_id, "title", e.title_sealed)

  defp views(events, user_id) do
    if events == [] do
      {:ok, []}
    else
      parts =
        Repo.all(from p in Participant, where: p.event_id in ^Enum.map(events, & &1.event_id))
        |> Enum.group_by(& &1.event_id)

      Seal.open_each(events, fn e ->
        ps =
          Enum.sort_by(
            parts[e.event_id] || [],
            &{&1.user_id != e.owner, &1.inserted_at, &1.user_id}
          )

        view(e, ps, user_id)
      end)
      |> case do
        {:ok, vs} -> {:ok, vs}
        :error -> {:error, :agent_unavailable}
      end
    end
  end

  defp view_of(event_id, user_id) do
    {e, ps} = load(event_id)
    view(e, ps, user_id)
  end

  @doc "The §29.2 Event as `user_id` sees it: `{:ok, map}` or `{:error, :agent_unavailable}`."
  def view(%Event{} = e, parts, user_id) do
    case open_event(e) do
      {:ok, %{title: title, notes: notes}} ->
        me = Enum.find(parts, &(&1.user_id == user_id))

        {:ok,
         %{
           "event_id" => e.event_id,
           "version" => e.version,
           "owner" => e.owner,
           "title" => title,
           "notes" => notes,
           "start" => Clock.ts(e.start_at),
           "end" => Clock.ts(e.end_at),
           "all_day" => e.all_day,
           "tz" => e.tz,
           "participants" =>
             for p <- parts do
               %{
                 "user_id" => p.user_id,
                 "status" => p.status,
                 "responded_at" => Clock.ts(p.responded_at)
               }
             end,
           "my_status" => me && me.status,
           "my_reminder_min" => me && me.reminder_min,
           "source" => %{
             "conversation_id" => e.source_conversation_id,
             "message_ids" => e.source_message_ids || [],
             "item_id" => e.source_item_id,
             "note_id" => e.source_note_id
           },
           "created_by" => e.created_by,
           "state" => e.state,
           "created_at" => Clock.ts(e.created_at),
           "updated_at" => Clock.ts(e.updated_at)
         }}

      :error ->
        {:error, :agent_unavailable}
    end
  end

  defp key do
    case Seal.data() do
      {:ok, k} -> {:ok, k}
      :error -> {:error, :agent_unavailable}
    end
  end

  ## The change log and `risi_calendar_changed`

  # One log row per user (ids only), per-user ordered: an advisory lock per user is held until
  # the transaction commits, so a reader never sees a later seq before an earlier one.
  defp log!(users, event_id) do
    users = users |> Enum.uniq() |> Enum.sort()
    now = DateTime.utc_now()

    for u <- users do
      Repo.query!("SELECT pg_advisory_xact_lock(hashtext('risi_calendar:' || $1::text))", [u])

      Repo.query!(
        "INSERT INTO risi_calendar_log (user_id, seq, event_id, at) " <>
          "VALUES ($1::uuid, nextval('risi_calendar_log_seq'), $2::uuid, $3)",
        [Ecto.UUID.dump!(u), Ecto.UUID.dump!(event_id), now]
      )
    end

    :ok
  end

  defp after_change(_event_id, users), do: notify_changed(users)

  @doc """
  The content-free `risi_calendar_changed` to each calendar user among `users`: now, or (within
  5 s of the last one) once, 5 s later, with the latest cursor (coalesced).
  """
  def notify_changed(users) do
    for u <- calendar_users(users) do
      case RisiMe.RateLimiter.hit_if_allowed(:risi_calendar_changed, u, 1, @changed_every_ms) do
        :ok ->
          publish_changed(u)

        {:error, :rate_limited} ->
          %{"kind" => "calendar_changed", "user_id" => u}
          |> RisiMe.Workers.Risi.new(
            queue: :risi_timers,
            schedule_in: div(@changed_every_ms, 1000),
            unique: [
              period: 60,
              keys: [:kind, :user_id],
              states: [:available, :scheduled, :retryable]
            ]
          )
          |> Oban.insert()
      end
    end

    :ok
  end

  @doc "Stores and broadcasts `risi_calendar_changed` with the user's current cursor (no push)."
  def publish_changed(user_id) do
    if calendar_user?(user_id) do
      RisiMe.Messaging.publish_quiet(user_id, "risi_calendar_changed", %{
        "cursor" => cursor(user_id),
        "server_ts" => Clock.ts(DateTime.utc_now())
      })
    end

    :ok
  end

  ## The digest (§29.12, amends §28.8)

  @doc """
  The personal digest's `events` for `user` at `now` (their zone `tz`): `%{"today",
  "tomorrow", "pending"}` of EventRefs, or nil (not a calendar user, `digest_events` off, or the
  calendar can't be opened). `pending` = invites awaiting the user's answer in the next 14 days.
  """
  def digest_events(user, now, tz) do
    with true <- calendar_user?(user),
         true <- settings(user)["digest_events"] == true,
         today = NaiveDateTime.to_date(Clock.local(now, tz)),
         t0 = Clock.to_utc(NaiveDateTime.new!(today, ~T[00:00:00]), tz),
         t1 = Clock.to_utc(NaiveDateTime.new!(Date.add(today, 1), ~T[00:00:00]), tz),
         t2 = Clock.to_utc(NaiveDateTime.new!(Date.add(today, 2), ~T[00:00:00]), tz),
         {:ok, day} <- own_events(user, t0, t1),
         {:ok, next} <- own_events(user, t1, t2),
         {:ok, soon} <- own_events(user, now, DateTime.add(now, 14 * 86_400, :second)) do
      %{
        "today" => Enum.map(day, &ref/1),
        "tomorrow" => Enum.map(next, &ref/1),
        "pending" => for(x <- soon, x.status == "proposed", do: ref(x))
      }
    else
      _ -> nil
    end
  end

  defp ref(%{event: e, status: status, title: title}),
    do: %{
      "event_id" => e.event_id,
      "title" => title,
      "start" => Clock.ts(e.start_at),
      "end" => Clock.ts(e.end_at),
      "all_day" => e.all_day,
      "my_status" => status
    }

  @doc "The calendar users with an event (not declined) in the next 14 days (digest sweep)."
  def digest_users(now) do
    if on?() do
      users =
        Repo.all(
          from p in Participant,
            join: e in Event,
            on: e.event_id == p.event_id,
            where:
              p.removed == false and p.status != "declined" and e.state == "active" and
                e.end_at > ^DateTime.add(now, -86_400, :second) and
                e.start_at < ^DateTime.add(now, 14 * 86_400, :second),
            distinct: true,
            select: p.user_id
        )

      calendar_users(users)
    else
      []
    end
  end

  ## Housekeeping

  @doc """
  Hourly: cancelled events older than 7 days are purged (titles gone), the change log keeps 30
  days, idempotency ids 24 h, held invites until the event starts, closed suggestions go.
  """
  def prune(now \\ DateTime.utc_now()) do
    Repo.delete_all(
      from e in Event,
        where:
          e.state == "cancelled" and e.cancelled_at < ^DateTime.add(now, -@purge_after_s, :second)
    )

    Repo.delete_all(
      from l in "risi_calendar_log", where: l.at < ^DateTime.add(now, -@log_keep_s, :second)
    )

    Repo.delete_all(
      from c in "risi_calendar_client_ids",
        where: c.inserted_at < ^DateTime.add(now, -@client_id_keep_s, :second)
    )

    Repo.delete_all(
      from i in "risi_calendar_invites_pending",
        join: e in Event,
        on: e.event_id == i.event_id,
        where: e.start_at < ^now
    )

    :ok
  end
end
