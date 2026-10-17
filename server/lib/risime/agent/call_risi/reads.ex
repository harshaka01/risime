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
  # Architect 04:20 item 4 / Q-014 d: one person's Official texts, filtered by sender in the query
  # (never cut by a row limit across all chats), newest first: `[%{conversation_id, at, text, dm?}]`.
  @callback messages_from(
              user :: binary,
              sender :: binary,
              since :: DateTime.t(),
              limit :: pos_integer
            ) ::
              [map]
  # The caller's own last text in the Official 1:1 with `person` (Q-014 c), or nil.
  @callback my_last_in_dm(user :: binary, person :: binary) :: map | nil
  # 04:25 / item 5: when `person` last wrote to the caller in a Private chat (metadata only:
  # sender, conversation and time; Risi never reads it), or nil.
  @callback private_last(user :: binary, person :: binary) :: DateTime.t() | nil

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

  @doc false
  def messages_from(user, sender, since, limit) do
    import Ecto.Query
    alias RisiMe.Agent.Memory
    convs = Memory.scope(user)
    dms = dm_convs(convs)

    ids =
      RisiMe.Repo.all(
        from m in Memory.Message,
          where:
            m.conversation_id in ^convs and m.sender_id == ^sender and m.from_risi == false and
              m.at >= ^since,
          order_by: [desc: m.at],
          limit: ^limit,
          select: m.id
      )

    ids
    |> Memory.by_ids()
    |> Enum.map(&Map.put(&1, :dm?, MapSet.member?(dms, &1.conversation_id)))
    |> Enum.sort_by(& &1.at, {:desc, DateTime})
  rescue
    _ -> []
  end

  @doc false
  def my_last_in_dm(user, person) do
    import Ecto.Query
    alias RisiMe.Agent.Memory
    convs = Memory.scope(user)
    dms = dm_convs(convs) |> MapSet.to_list()

    shared =
      RisiMe.Repo.all(
        from m in RisiMe.Groups.Member,
          where: m.group_id in ^dms and m.user_id == ^person and m.state == "active",
          select: m.group_id
      )

    RisiMe.Repo.one(
      from m in Memory.Message,
        where: m.conversation_id in ^shared and m.sender_id == ^user,
        order_by: [desc: m.at],
        limit: 1,
        select: %{at: m.at, conversation_id: m.conversation_id}
    )
  rescue
    _ -> nil
  end

  @doc false
  def private_last(user, person) do
    since = RisiMe.TimeUUID.at(DateTime.add(Clock.now(), -7 * 86_400, :second))
    official = MapSet.new(RisiMe.Agent.Memory.scope(user))

    RisiMe.Messaging.Store.impl().list_events(user, since, 5000, false)
    |> Enum.flat_map(fn
      %{kind: "message", data: %{"from" => ^person, "conversation_id" => conv} = d} ->
        if MapSet.member?(official, conv) or not private_conv?(conv),
          do: [],
          else: [elem(DateTime.from_iso8601(to_string(d["server_ts"])), 1)]

      _ ->
        []
    end)
    |> Enum.filter(&match?(%DateTime{}, &1))
    |> Enum.max(DateTime, fn -> nil end)
  rescue
    _ -> nil
  catch
    _, _ -> nil
  end

  defp private_conv?("dm:" <> _), do: true

  defp private_conv?("grp:" <> _ = conv) do
    case RisiMe.Repo.get(RisiMe.Groups.Group, conv) do
      %{tab: "official"} -> false
      %{} -> true
      _ -> false
    end
  rescue
    _ -> false
  end

  defp private_conv?(_), do: false

  defp dm_convs(convs) do
    import Ecto.Query

    RisiMe.Repo.all(
      from g in RisiMe.Groups.Group, where: g.id in ^convs and g.chat_kind == "dm", select: g.id
    )
    |> MapSet.new()
  rescue
    _ -> MapSet.new()
  end

  ## The tools

  @doc """
  "Read me Shakya's last message", "what did Shenika say?" (architect 04:20 item 4): the person's
  latest Official texts whatever the order, up to 3 with their times; nothing in 3 days → the
  real latest of 30 days (item 5); their newer Private messages are named, never read.
  """
  def last_from(user, %{user_id: pid, name: name}, opts \\ []) do
    now = Keyword.get(opts, :now, Clock.now())
    tz = Keyword.get(opts, :tz, "UTC")
    who = display(name)
    recent = data().messages_from(user, pid, DateTime.add(now, -3 * 86_400, :second), 3)

    older =
      if recent == [],
        do: data().messages_from(user, pid, DateTime.add(now, -30 * 86_400, :second), 1),
        else: []

    last = List.first(recent) || List.first(older)
    private = private_note(user, pid, who, last, now, tz)

    cond do
      recent != [] ->
        private <> "#{who}'s last Official messages: " <> quotes(recent, now, tz)

      last != nil ->
        private <>
          "Nothing from #{who} in your Official chats in the last 3 days. #{who}'s last Official " <>
          "message was #{when_said(last.at, now, tz)}#{where(last)}: #{quoted(last.text)}"

      true ->
        private <>
          "I don't see any Official messages from #{who} in the last 30 days, in your Official " <>
          "chats with them (1:1 and groups) on your work account."
    end
  end

  @doc """
  "Did Shenika reply?" (architect 04:20 item 4): their Official texts after the caller's own last
  one in their **1:1** Official chat (Q-014 c: never a group post of the caller's).
  """
  def replied_since(user, %{user_id: pid, name: name} = person, opts \\ []) do
    now = Keyword.get(opts, :now, Clock.now())
    tz = Keyword.get(opts, :tz, "UTC")
    who = display(name)

    case data().my_last_in_dm(user, pid) do
      %{at: mine} ->
        theirs =
          data().messages_from(user, pid, mine, 5)
          |> Enum.filter(&(DateTime.compare(&1.at, mine) == :gt))

        private = private_note(user, pid, who, List.first(theirs), now, tz)

        case theirs do
          [last | _] = all ->
            more =
              if length(all) > 1,
                do: " That's the latest of #{length(all)} since your message.",
                else: ""

            private <>
              "Yes, #{who} replied #{when_said(last.at, now, tz)}#{where(last)}: #{quoted(last.text)}" <>
              more

          [] ->
            private <>
              "Not yet in Official. Your last Official message to #{who} was #{when_said(mine, now, tz)}, " <>
              "and #{who} hasn't written in Official since."
        end

      nil ->
        last_from(user, person, opts)
    end
  end

  @doc """
  The spoken answer to a messages question. `person`: `%{user_id, name}` or nil (anyone).
  """
  def read_messages(user, person, opts \\ []) do
    now = Keyword.get(opts, :now, Clock.now())
    tz = Keyword.get(opts, :tz, "UTC")

    if Keyword.get(opts, :private?) == true do
      "I can't see your Private chats, only your Official ones. " <>
        if(person, do: last_from(user, person, opts), else: new_line(user, now, tz))
    else
      if person, do: last_from(user, person, opts), else: new_line(user, now, tz)
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

  defp first_name(name) do
    # Architect 04:20 item 10: never a one-letter name ("M N M Shirazi" is said in full).
    case name |> to_string() |> String.split() do
      [f | _] = all ->
        if String.length(String.trim(f, ".")) <= 1, do: Enum.join(all, " "), else: f

      [] ->
        "them"
    end
  end

  defp display(name), do: first_name(name)

  defp quotes(msgs, now, tz) do
    msgs
    |> Enum.map(fn m -> "#{when_said(m.at, now, tz)}#{where(m)}: #{quoted(m.text)}" end)
    |> Enum.join(" Before that, ")
  end

  defp where(%{dm?: false}), do: " in an Official group chat"
  defp where(_), do: ""

  # Item 5: their newer Private messages are named (never read).
  defp private_note(user, pid, who, last_official, now, tz) do
    case data().private_last(user, pid) do
      %DateTime{} = p ->
        if last_official == nil or DateTime.compare(p, last_official.at) == :gt,
          do:
            "#{who} wrote to you in your Private chat #{when_said(p, now, tz)}, which I can't read. ",
          else: ""

      _ ->
        ""
    end
  end

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
