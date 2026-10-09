defmodule RisiMe.Agent.DailySummaries do
  @moduledoc """
  30-day summaries (Harsha 2026-10-09; proposal `2026-10-09-risi-30day-summaries.md`), while
  `RISI_LEDGER=on`.

  * **Daily** (`sweep/1`, every 15 min): each Official conversation (never a Risi chat) whose
    clock reads 23:30 or later and that has no summary for its local day yet gets one, made by
    one model call (task `daily_summarise`) over the buffered `text` messages of that day not yet
    covered (the buffer still holds raw text for 24 h only). No message: no summary.
  * **Weekly** rollup: on the chat's local Sunday, the week's day summaries are rolled into one
    (`scope: "week"`, task `summary_rollup`) from the derived summaries only.
  * **At rest:** the summary JSON (summary, decisions, action items, open questions) is sealed
    with `RISI_DATA_KEY`; rows are kept 35 days (`prune/0`) and deleted with the chat's Risi data.
  * **Answers** (`RisiMe.Agent.Requests`, `summarise` with `scope` `7d`, `30d` or
    `{from, to}` beyond the 24-h buffer): `for_range/3` gives the day summaries (and the week
    rollups that cover whole weeks of the range); the request adds confirmed commitments and the
    raw text of the last 24 h not yet covered.
  * **Facts:** `GET /api/v1/risi/facts` lists them under kind `"summary"` to every active member
    on a `risi_tools` device; `DELETE /api/v1/risi/facts/{id}` deletes one (for the chat).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, LLM, Prompts, Seal, Secretary}
  alias RisiMe.Repo

  @table "risi_daily_summaries"
  @keep_days 35

  defmodule Row do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:id, :binary_id, autogenerate: false}
    schema "risi_daily_summaries" do
      field :conversation_id, :string
      field :chat_id, :string
      field :scope, :string
      field :period_from, :date
      field :period_to, :date
      field :tz, :string
      field :covered_from, :utc_datetime_usec
      field :covered_to, :utc_datetime_usec
      field :message_count, :integer, default: 0
      field :summary, :map, virtual: true
      field :summary_sealed, :binary
      field :call_ref, :binary_id
      field :made_by, :map
      field :created_at, :utc_datetime_usec
    end
  end

  @doc "How long summaries are kept (days)."
  def keep_days, do: @keep_days

  ## The daily sweep

  @doc "Every 15 min (with the digest sweep): the day summaries due now. `now` for tests."
  def sweep(now \\ Clock.now()) do
    if RisiMe.Risi.ledger_on?() do
      for conv <- official_convs(), RisiMe.Agent.may_act?(conv), do: maybe_day(conv, now)
    end

    :ok
  end

  # Official dm/group conversations Risi is an active member of.
  defp official_convs do
    Repo.all(
      from g in RisiMe.Groups.Group,
        join: m in RisiMe.Groups.Member,
        on: m.group_id == g.id,
        where:
          g.tab == "official" and g.chat_kind in ["dm", "group"] and
            m.user_id == ^RisiMe.Risi.user_id() and m.state == "active",
        select: g.id
    )
  end

  @doc "Makes `conv`'s summary of its local day when its clock reads 23:30 or later."
  def maybe_day(conv, now \\ Clock.now()) do
    tz = Secretary.chat_tz(conv)
    local = Clock.local(now, tz)
    date = NaiveDateTime.to_date(local)

    if local.hour == 23 and local.minute >= 30 and not exists?(conv, "day", date),
      do: summarise_day(conv, date, tz, now),
      else: :skipped
  end

  defp exists?(conv, scope, date),
    do:
      Repo.exists?(
        from r in Row,
          where: r.conversation_id == ^conv and r.scope == ^scope and r.period_from == ^date
      )

  @doc "The day summary of `conv` for its local `date` (messages up to `now`)."
  def summarise_day(conv, date, tz, now) do
    start = Clock.to_utc(NaiveDateTime.new!(date, ~T[00:00:00]), tz)

    last_covered =
      Repo.one(
        from r in Row,
          where: r.conversation_id == ^conv and r.scope == "day",
          select: max(r.covered_to)
      )

    from = if last_covered, do: Enum.max([start, last_covered], DateTime), else: start

    msgs =
      conv
      |> Secretary.text_messages(from)
      |> Enum.filter(fn m ->
        t = RisiMe.TimeUUID.to_datetime(m.message_id)
        DateTime.compare(t, from) == :gt and DateTime.compare(t, now) != :gt
      end)

    case msgs do
      [] ->
        :skipped

      _ ->
        members = Secretary.members(conv)
        {text, _refs} = Prompts.render(members, msgs, tz, now: now)

        req = %{
          task: "daily_summarise",
          conversation_id: conv,
          chat_id: Secretary.chat_id(conv),
          system: Prompts.summary_system(),
          user: text,
          schema_name: "summary",
          schema: Prompts.summary_schema(),
          source_message_ids: Enum.map(msgs, & &1.message_id),
          max_tokens: 1_000
        }

        with {:ok, %{call_ref: ref, output: out}} <- LLM.complete(req) do
          ts = fn m -> RisiMe.TimeUUID.to_datetime(m.message_id) end

          store(conv, "day", date, date, tz, out, ref, %{
            covered_from: ts.(hd(msgs)),
            covered_to: ts.(List.last(msgs)),
            message_count: length(msgs)
          })

          if Date.day_of_week(date) == 7, do: rollup_week(conv, date, tz)
          :ok
        else
          {:error, :rate_limited} -> {:snooze, 60}
          {:error, reason} -> {:error, reason}
        end
    end
  end

  @doc "Rolls the day summaries of the week ending on `end_date` into one (`scope: \"week\"`)."
  def rollup_week(conv, end_date, tz) do
    from_date = Date.add(end_date, -6)
    days = for_dates(conv, "day", from_date, end_date)

    with [_ | _] <- days,
         {:ok, %{call_ref: ref, output: out}} <-
           LLM.complete(%{
             task: "summary_rollup",
             conversation_id: conv,
             chat_id: Secretary.chat_id(conv),
             system: Prompts.summary_system(),
             user: Prompts.summaries_block(Enum.map(days, &line/1)),
             schema_name: "summary",
             schema: Prompts.summary_schema(),
             source_message_ids: [],
             max_tokens: 1_000
           }) do
      store(conv, "week", from_date, end_date, tz, out, ref, %{
        covered_from: Enum.min_by(days, & &1.covered_from, DateTime).covered_from,
        covered_to: Enum.max_by(days, & &1.covered_to, DateTime).covered_to,
        message_count: Enum.sum(Enum.map(days, & &1.message_count))
      })
    else
      _ -> :ok
    end
  end

  defp store(conv, scope, from_date, to_date, tz, out, call_ref, extra) do
    id = Ecto.UUID.generate()

    summary =
      Map.take(out, ~w(summary decisions action_items open_questions))
      |> Map.new(fn
        {k, s} when is_binary(s) -> {k, RisiMe.Agent.Capabilities.strip_labels(s)}
        {k, l} when is_list(l) -> {k, Enum.map(l, &RisiMe.Agent.Capabilities.strip_labels/1)}
        kv -> kv
      end)

    with {:ok, key} <- Seal.data() do
      row = %Row{
        id: id,
        conversation_id: conv,
        chat_id: Secretary.chat_id(conv),
        scope: scope,
        period_from: from_date,
        period_to: to_date,
        tz: tz,
        covered_from: extra.covered_from && Clock.usec(extra.covered_from),
        covered_to: extra.covered_to && Clock.usec(extra.covered_to),
        message_count: extra.message_count,
        summary_sealed: Seal.seal(key, aad(id), summary),
        call_ref: call_ref,
        made_by: RisiMe.Agent.MadeBy.build(call_ref),
        created_at: Clock.usec(Clock.now())
      }

      Repo.insert!(row,
        on_conflict: :nothing,
        conflict_target: [:conversation_id, :scope, :period_from]
      )

      :ok
    else
      :error ->
        Logger.warning("Risi daily summary not kept in #{conv}: missing_key RISI_DATA_KEY")
        :ok
    end
  end

  defp aad(id), do: Seal.aad(@table, id, "summary")

  @doc "Opens a row's summary: `{:ok, row}` (with `summary`) or `:error`."
  def open(%Row{} = r) do
    with {:ok, key} <- Seal.data(),
         {:ok, %{} = s} <- Seal.open(key, aad(r.id), r.summary_sealed) do
      {:ok, %{r | summary: s}}
    else
      _ -> :error
    end
  end

  defp for_dates(conv, scope, from_date, to_date) do
    Repo.all(
      from r in Row,
        where:
          r.conversation_id == ^conv and r.scope == ^scope and r.period_from >= ^from_date and
            r.period_to <= ^to_date,
        order_by: [asc: r.period_from]
    )
    |> Enum.flat_map(fn r ->
      case open(r) do
        {:ok, r} -> [r]
        :error -> []
      end
    end)
  end

  @doc """
  The summaries that cover `from_date..to_date` of `conv` (oldest first): the week rollups that
  lie inside the range, and the day summaries of the days no such week covers.
  """
  def for_range(conv, from_date, to_date) do
    weeks = for_dates(conv, "week", from_date, to_date)
    covered = MapSet.new(Enum.flat_map(weeks, &Date.range(&1.period_from, &1.period_to)))

    days =
      conv
      |> for_dates("day", from_date, to_date)
      |> Enum.reject(&MapSet.member?(covered, &1.period_from))

    Enum.sort_by(weeks ++ days, & &1.period_from, Date)
  end

  @doc "One summary as a prompt line (derived data only)."
  def line(%Row{} = r) do
    %{
      "period" =>
        if(r.period_from == r.period_to,
          do: Date.to_iso8601(r.period_from),
          else: "#{r.period_from}..#{r.period_to}"
        ),
      "summary" => r.summary["summary"],
      "decisions" => r.summary["decisions"],
      "action_items" => r.summary["action_items"],
      "open_questions" => r.summary["open_questions"]
    }
  end

  ## Facts (GET/DELETE /api/v1/risi/facts)

  @doc "The summaries of the chats `user` is an active member of, newest first, as fact maps."
  def facts(user) do
    mine =
      from m in RisiMe.Groups.Member,
        where: m.user_id == ^user and m.state == "active",
        select: m.group_id

    Repo.all(
      from r in Row,
        where: r.conversation_id in subquery(mine),
        order_by: [desc: r.period_from, asc: r.scope, asc: r.id]
    )
    |> Enum.flat_map(fn r ->
      case open(r) do
        {:ok, r} ->
          [
            %{
              fact_id: r.id,
              kind: "summary",
              text: r.summary["summary"],
              chat_id: r.chat_id,
              created_at: RisiMe.Messaging.iso(r.created_at),
              scope: r.scope,
              period: %{from: Date.to_iso8601(r.period_from), to: Date.to_iso8601(r.period_to)}
            }
          ]

        :error ->
          []
      end
    end)
  end

  @doc "Deletes one summary of a chat `user` is an active member of: `:ok` or `:not_found`."
  def delete(user, id) do
    mine =
      from m in RisiMe.Groups.Member,
        where: m.user_id == ^user and m.state == "active",
        select: m.group_id

    case Repo.delete_all(
           from r in Row, where: r.id == ^id and r.conversation_id in subquery(mine)
         ) do
      {1, _} -> :ok
      _ -> :not_found
    end
  end

  ## Retention

  @doc "Hourly: summaries older than 35 days go."
  def prune do
    cutoff = DateTime.add(Clock.now(), -@keep_days * 86_400)
    Repo.delete_all(from r in Row, where: r.created_at < ^cutoff)
    :ok
  end

  @doc "Deletes a conversation's summaries (§24.4)."
  def forget(conv) do
    Repo.delete_all(from r in Row, where: r.conversation_id == ^conv)
    :ok
  end
end
