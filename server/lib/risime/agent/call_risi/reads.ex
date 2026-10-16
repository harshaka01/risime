defmodule RisiMe.Agent.CallRisi.Reads do
  @moduledoc """
  The call brain's **read tools** (architect 15:35 B, 16:05 item 6): what Risi can tell a caller
  from what it already holds, without the §25 turn (no model call, fast):

    * `read_messages(person?, since?)`: the caller's **Official** messages from others, from Risi's
      sealed memory of Official chats (`RisiMe.Agent.Memory`, §35; the same store
      `VoiceTranslate.source_text/2` reads). "Did Nimali reply?" = Nimali's messages after the
      caller's own last message where she writes; "any new messages?" = the last 12 hours, by
      sender. **Private is never read** (Risi isn't a member there): asked for it, Risi says so.
    * `promises_by(person)`: the open Commitment Ledger items that person owns (promises to the
      caller), and the caller's own promises to them.
    * `my_promises`: the caller's own open promises (the day-named case stays the calendar read
      of Risi's items, `BrainV3`).

  Each returns the spoken line only (one or two sentences; a message is quoted up to 25 words),
  naming what was looked at ("in your Official chats"). Nothing here logs content. The data
  comes through `data/0` (tests: `config :risime, :risi_call_reads, Module` with
  `messages/2` and `commitments/1`).
  """

  alias RisiMe.Agent.{Clock, FindContact}

  @callback messages(user :: binary, since :: DateTime.t()) :: [map]
  @callback commitments(user :: binary) :: [map]

  @reply_window_s 3 * 86_400
  @new_window_s 12 * 3600

  @doc false
  def data, do: Application.get_env(:risime, :risi_call_reads, __MODULE__)

  ## The default data (the real stores)

  @doc false
  # The Official messages of the caller's chats since `since`, opened, oldest first:
  # `[%{sender_id, conversation_id, at, text, from_risi}]`.
  def messages(user, since) do
    convs = RisiMe.Agent.Memory.scope(user)
    RisiMe.Agent.Memory.messages(convs, since, nil, 300)
  rescue
    _ -> []
  end

  @doc false
  # The open ledger items where the caller is owner or counterpart:
  # `[%{owner_id, counterpart_ids, text, due}]`.
  def commitments(user) do
    case RisiMe.Agent.Rest.open_commitments(user) do
      {:ok, cs} -> cs
      cs when is_list(cs) -> cs
      _ -> []
    end
  rescue
    _ -> []
  end

  ## The tools

  @doc """
  The spoken answer to a messages question. `person`: `%{user_id, name}` or nil (anyone).
  """
  def read_messages(user, person, opts \\ []) do
    now = Keyword.get(opts, :now, Clock.now())
    tz = Keyword.get(opts, :tz, "UTC")

    if Keyword.get(opts, :private?) == true do
      "I can't see your Private chats, only your Official ones. " <>
        if(person, do: official_line(user, person, now, tz), else: new_line(user, now, tz))
    else
      if person, do: official_line(user, person, now, tz), else: new_line(user, now, tz)
    end
  end

  defp official_line(user, %{user_id: pid, name: name}, now, tz) do
    msgs = data().messages(user, DateTime.add(now, -@reply_window_s, :second))
    theirs = Enum.filter(msgs, &(&1.sender_id == pid and Map.get(&1, :from_risi) != true))
    convs = MapSet.new(theirs, & &1.conversation_id)

    mine =
      Enum.filter(
        msgs,
        &(&1.sender_id == user and MapSet.member?(convs, &1.conversation_id))
      )

    first = first_name(name)

    case {List.last(theirs), List.last(mine)} do
      {nil, _} ->
        "I don't see any Official messages from #{first} in the last 3 days."

      {last, nil} ->
        "#{first}'s last Official message, #{when_said(last.at, now, tz)}: #{quoted(last.text)}"

      {last, my} ->
        if DateTime.compare(last.at, my.at) == :gt do
          n = Enum.count(theirs, &(DateTime.compare(&1.at, my.at) == :gt))
          more = if n > 1, do: " That's the latest of #{n} since your message.", else: ""

          "Yes, #{first} replied #{when_said(last.at, now, tz)}: #{quoted(last.text)}" <> more
        else
          "Not yet. Your last Official message to #{first} was #{when_said(my.at, now, tz)}, " <>
            "and #{first} hasn't written since."
        end
    end
  end

  defp new_line(user, now, tz) do
    names = Map.new(FindContact.people(user))

    msgs =
      data().messages(user, DateTime.add(now, -@new_window_s, :second))
      |> Enum.filter(&(&1.sender_id != user and Map.get(&1, :from_risi) != true))

    case msgs do
      [] ->
        "No new Official messages in the last 12 hours."

      _ ->
        counts =
          msgs
          |> Enum.group_by(& &1.sender_id)
          |> Enum.map(fn {id, ms} -> {first_name(names[id] || "someone"), length(ms)} end)
          |> Enum.sort_by(&(-elem(&1, 1)))
          |> Enum.take(3)
          |> Enum.map(fn {n, c} -> "#{c} from #{n}" end)

        last = List.last(msgs)
        who = first_name(names[last.sender_id] || "someone")

        "In your Official chats in the last 12 hours: #{and_list(counts)}. The latest, from " <>
          "#{who} #{when_said(last.at, now, tz)}: #{quoted(last.text)}"
    end
  end

  @doc "The spoken answer to \"any promises by Kamal?\" (`person`: `%{user_id, name}`)."
  def promises_by(user, %{user_id: pid, name: name}, opts \\ []) do
    tz = Keyword.get(opts, :tz, "UTC")
    cs = data().commitments(user)
    first = first_name(name)
    theirs = Enum.filter(cs, &(&1.owner_id == pid))

    mine =
      Enum.filter(cs, &(&1.owner_id == user and pid in (Map.get(&1, :counterpart_ids) || [])))

    case {theirs, mine} do
      {[], []} ->
        "I don't see any open promises from #{first} in your Risi ledger, and none from you to #{first}."

      {[], m} ->
        "#{first} has no open promises to you. Yours to #{first}: #{items(m, tz)}."

      {t, []} ->
        "#{first}'s open promises to you: #{items(t, tz)}."

      {t, m} ->
        "#{first}'s open promises to you: #{items(t, tz)}. Yours to #{first}: #{items(m, tz)}."
    end
  end

  @doc "The spoken answer to \"what did I promise?\" (no day: everything open)."
  def my_promises(user, opts \\ []) do
    tz = Keyword.get(opts, :tz, "UTC")
    mine = Enum.filter(data().commitments(user), &(&1.owner_id == user))

    case mine do
      [] -> "You have no open promises in your Risi ledger."
      m -> "Your open promises in your Risi ledger: #{items(m, tz)}."
    end
  end

  ## Wording

  defp items(cs, tz) do
    shown = Enum.take(cs, 3)

    line =
      shown
      |> Enum.map(fn c ->
        t = String.trim(to_string(Map.get(c, :text) || ""), " .")

        case Map.get(c, :due) do
          %DateTime{} = due -> "#{t}, due #{day_said(due, tz)}"
          _ -> t
        end
      end)
      |> and_list()

    if length(cs) > 3, do: line <> ", and #{length(cs) - 3} more", else: line
  end

  defp and_list([one]), do: one
  defp and_list(l), do: Enum.join(Enum.drop(l, -1), ", ") <> " and " <> List.last(l)

  defp first_name(name), do: name |> to_string() |> String.split() |> List.first() || "them"

  defp quoted(text) do
    w = text |> to_string() |> String.split()
    t = if length(w) > 25, do: Enum.join(Enum.take(w, 25), " ") <> " …", else: Enum.join(w, " ")
    RisiMe.Agent.CallRisi.Dialogue.quoted(t)
  end

  defp when_said(at, now, tz) do
    l = local(at, tz)
    today = now |> local(tz) |> NaiveDateTime.to_date()
    clock = RisiMe.Agent.CallRisi.Spoken.clock(l)

    case Date.diff(today, NaiveDateTime.to_date(l)) do
      0 -> "at #{clock}"
      1 -> "yesterday at #{clock}"
      _ -> "on #{Calendar.strftime(l, "%A")} at #{clock}"
    end
  end

  defp day_said(due, tz), do: Calendar.strftime(local(due, tz), "%A %-d %B")

  defp local(%DateTime{} = dt, tz) do
    Clock.local(dt, tz)
  catch
    _, _ -> DateTime.to_naive(dt)
  end
end
