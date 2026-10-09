defmodule RisiMe.Agent.Clock do
  @moduledoc """
  Time zones for Risi (§24.11 "Timezone", "Timing"). The server has no Elixir time-zone database,
  so the conversions run in Postgres (`AT TIME ZONE`), which ships the IANA database. A user's
  zone is `users.tz` (set by `PATCH /me`), else `RISI_DEFAULT_TZ` (default `Asia/Colombo`); a
  zone Postgres doesn't know falls back to the default.
  """
  import Ecto.Query

  alias RisiMe.Repo

  @default "Asia/Colombo"

  @doc """
  The clock Risi reads ("now" for time phrases, reminders and request times). Defaults to
  `DateTime.utc_now/0`; tests fix it with `config :risime, :risi_now` (an ISO-8601 string, a
  `DateTime`, or a zero-arity function), so no Risi test depends on the wall clock.
  """
  def now do
    case Application.get_env(:risime, :risi_now) do
      nil -> DateTime.utc_now()
      f when is_function(f, 0) -> f.()
      %DateTime{} = dt -> dt
      s when is_binary(s) -> elem(DateTime.from_iso8601(s), 1)
    end
  end

  def default_tz, do: Application.get_env(:risime, :risi_default_tz, @default)

  @doc "The user's zone (or the default)."
  def user_tz(user_id) do
    tz = Repo.one(from u in RisiMe.Accounts.User, where: u.id == ^user_id, select: u.tz)
    usable(tz)
  end

  @doc "The zones of several users: `%{user_id => tz}`."
  def user_tzs([]), do: %{}

  def user_tzs(ids) do
    found =
      Repo.all(from u in RisiMe.Accounts.User, where: u.id in ^ids, select: {u.id, u.tz})
      |> Map.new()

    Map.new(ids, &{&1, usable(found[&1])})
  end

  defp usable(tz) when is_binary(tz) and tz != "" do
    if RisiMe.Accounts.valid_tz?(tz), do: tz, else: default_tz()
  end

  defp usable(_), do: default_tz()

  @doc "The wall-clock time in `tz` of a UTC instant."
  def local(%DateTime{} = dt, tz) do
    %{rows: [[naive]]} =
      Repo.query!("SELECT ($1::timestamptz AT TIME ZONE $2::text)::timestamp", [dt, tz])

    naive
  end

  @doc "The UTC instant of a wall-clock time in `tz`."
  def to_utc(%NaiveDateTime{} = naive, tz) do
    %{rows: [[dt]]} =
      Repo.query!("SELECT ($1::timestamp AT TIME ZONE $2::text)", [naive, tz])

    DateTime.shift_zone!(dt, "Etc/UTC")
  end

  @doc """
  A model's `due_local` (`YYYY-MM-DD` or `YYYY-MM-DDTHH:MM`, the owner's wall clock) as
  `{due_utc, kind}`: a date-only due is the end of that day (23:59 local), kind `"date"`.
  `{nil, nil}` when absent or unreadable.
  """
  def parse_due(nil, _tz), do: {nil, nil}

  def parse_due(s, tz) when is_binary(s) do
    cond do
      match = Regex.run(~r/^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})$/, s) ->
        [_, d, h, m] = match

        with {:ok, date} <- Date.from_iso8601(d),
             {:ok, time} <- Time.new(String.to_integer(h), String.to_integer(m), 0),
             {:ok, naive} <- NaiveDateTime.new(date, time) do
          {to_utc(naive, tz), "datetime"}
        else
          _ -> {nil, nil}
        end

      Regex.match?(~r/^\d{4}-\d{2}-\d{2}$/, s) ->
        case Date.from_iso8601(s) do
          {:ok, date} -> {to_utc(NaiveDateTime.new!(date, ~T[23:59:00]), tz), "date"}
          _ -> {nil, nil}
        end

      true ->
        {nil, nil}
    end
  end

  def parse_due(_, _tz), do: {nil, nil}

  @doc """
  When to remind (§24.11): `due − 1 h`, or 09:00 local on the due date when only a date is
  known. nil without a due.
  """
  def remind_at(nil, _kind, _tz), do: nil

  def remind_at(due, "date", tz) do
    date = due |> local(tz) |> NaiveDateTime.to_date()
    to_utc(NaiveDateTime.new!(date, ~T[09:00:00]), tz)
  end

  def remind_at(due, _kind, _tz), do: DateTime.add(due, -3600, :second)

  @doc "A short human form of a due in `tz`: `Fri 9 Oct, 17:00` (or `Fri 9 Oct` for a date)."
  def human(nil, _kind, _tz), do: nil

  def human(due, kind, tz) do
    local = local(due, tz)

    if kind == "date",
      do: Calendar.strftime(local, "%a %-d %b"),
      else: Calendar.strftime(local, "%a %-d %b, %H:%M")
  end

  @doc "The same instant with microsecond precision (for `:utc_datetime_usec` columns)."
  def usec(%DateTime{microsecond: {us, _}} = dt), do: %{dt | microsecond: {us, 6}}

  @doc "`2026-10-09T11:30:00.000Z` (milliseconds, as every contract timestamp)."
  def ts(nil), do: nil

  def ts(%DateTime{} = dt) do
    {us, _} = dt.microsecond
    ms = div(us, 1000)

    %{DateTime.shift_zone!(dt, "Etc/UTC") | microsecond: {ms * 1000, 3}}
    |> DateTime.to_iso8601()
  end
end
