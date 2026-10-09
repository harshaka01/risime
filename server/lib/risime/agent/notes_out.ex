defmodule RisiMe.Agent.NotesOut do
  @moduledoc """
  What Risi Notes posts (contract v1.29 §30.4), through `RisiMe.Agent.Out` (MLS-encrypted):

  * **`note_card`** in each notes recipient's own Risi chat (`for`, `notify: [R]`), the full
    §30.3 Note, held 24 h for a recipient without an active Risi chat (the §27.3 pending copies,
    `RisiMe.Agent.LedgerOut.deliver_or_hold/4`). It replaces the §27.3 `discussion_summary` for
    that person (never both).
  * **`notes_saved`** in the Official conversation, silent, with no items, owners or dues. It
    replaces `discussion_card`; the Official chat never gets both.

  `body` is the English fallback for old apps; the phone renders the title per viewer.
  """
  require Logger

  alias RisiMe.Agent.{CalendarCards, ClientTools, Clock, LedgerOut, Notes, Out, Secretary}
  alias RisiMe.Agent.Notes.Note

  @doc """
  Posts (or holds) the `note_card` of the opened note `n` for each of `recipients`, with the
  extraction's discussion `d` (for the 48-h `expires_at`). Returns the recipients served.
  """
  def cards(d, %Note{} = n, recipients) do
    names = names(n)
    json = Notes.note_json(n, nil)

    for r <- recipients do
      {body, risi} = card(n, json, r, names, d.created_at)
      LedgerOut.deliver_or_hold(d, r, body, risi)
      r
    end
  end

  @doc "The `note_card` of note `n` for recipient `r`: `{body, risi}`."
  def card(%Note{} = n, json, r, names, created_at) do
    tz = Clock.user_tz(r)
    json = Map.put(json, "events", Notes.events(n.note_id, r))

    risi =
      Map.merge(json, %{
        "kind" => "note_card",
        "for" => r,
        "expires_at" => Clock.ts(DateTime.add(created_at, RisiMe.Agent.Ledger.expire_s())),
        "notify" => [r]
      })

    {body(n, json, r, names, tz), risi}
  end

  @doc "The note's title for `viewer`: \"Harsha × Shenika · interview planning · Fri 9 Oct\"."
  def title(%Note{} = n, viewer, names, tz) do
    people = [viewer | n.participants -- [viewer]] |> Enum.map(&(names[&1] || "Someone"))

    who =
      if length(people) > 3,
        do: "#{Enum.at(people, 0)} × #{Enum.at(people, 1)} +#{length(people) - 2}",
        else: Enum.join(people, " × ")

    date = n.ended_at |> Clock.local(tz) |> Calendar.strftime("%a %-d %b")
    "#{who} · #{n.topic} · #{date}"
  end

  defp body(n, json, r, names, tz) do
    items =
      for i <- json["items"] do
        owner = if i["owner"] == r, do: "You", else: names[i["owner"]] || "A member"
        "• #{owner}: #{i["text"]}#{LedgerOut.due_suffix(i, tz)}"
      end

    events =
      for e <- json["events"] do
        {:ok, s, _} = DateTime.from_iso8601(e["start"])
        {:ok, t, _} = DateTime.from_iso8601(e["end"])
        "• #{e["title"]} · #{ClientTools.span12(s, t, e["all_day"], tz)}"
      end

    (["Notes: " <> title(n, r, names, tz)] ++
       Enum.map(n.key_points, &("• " <> &1)) ++
       if(items == [], do: [], else: ["Agreed:" | items]) ++
       if(events == [], do: [], else: ["Meetings:" | events]) ++
       ["Open in RisiMe"])
    |> Enum.join("\n")
  end

  @doc "Display names of the note's people (members first, then any user row)."
  def names(%Note{} = n) do
    ids = n.participants ++ Notes.recipients(n.note_id)
    members = Map.new(Secretary.members(n.conversation_id), &{&1.user_id, &1.name})
    Map.merge(CalendarCards.names(ids), members)
  end

  @doc """
  `notes_saved` in the note's Official conversation (`notify` `[]`, or `[asker]` for a repeated
  `@Risi summarise`). `:ok` or `{:error, reason}`.
  """
  def saved(%Note{} = n, summary, notify \\ []) do
    risi = %{
      "kind" => "notes_saved",
      "note_id" => n.note_id,
      "source" => n.source,
      "with" => n.participants,
      "summary" => summary,
      "items_count" => length(Notes.items(n.note_id)),
      "events_count" => length(Notes.events(n.note_id, nil)),
      "made_by" => n.made_by,
      "notify" => notify
    }

    case Out.post(n.conversation_id, "Notes saved · open in your Risi chat", risi) do
      {:ok, _} ->
        :ok

      {:error, reason} = e ->
        Logger.warning("Risi notes_saved not sent: #{inspect(reason)}")
        e
    end
  end
end
