defmodule RisiMe.Agent.Reminders do
  @moduledoc """
  `set_reminder` (contract v1.25 §25.4/§25.5, v1.26 §26.3/§26.4; server S15).

  * **The tool** (`tool/0`, skill `reminders`, a server write): `{when, text, audience: "me" |
    "conversation"}`. `when` is resolved from the request's `server_ts` in the asker's zone
    (`RisiMe.Agent.TimePhrase`); a date without a time or a bare hour asks instead of guessing
    (the step fails with the reason and the `final` asks); a time in the past or more than a
    year ahead is refused the same way.
  * **The card** (`RisiMe.Agent.Writes`): a reminder for the conversation (others are in it)
    goes to that conversation and offers [Me too]; a "remind me" one is personal (the asker's
    Risi chat, or the conversation itself for an asker without one). "Allowed" (§26.3) skips the
    card only for a "remind me".
  * **Confirmed** (`exec/3`): a `risi_reminders` row (text sealed with `RISI_DATA_KEY`), an Oban
    job at `when`, and `reminder_set` where the card was; for a gated asker an activity entry
    (`reminder_set`, undo `server` until it fires).
  * **Me too / Not me** (`act/3`, §25.4): `me_too` from an active human member other than the
    asker before it fires adds only the tapper; `not_me` removes only the tapper (from the asker
    it removes them; no participants left drops the reminder).
  * **Firing** (`fire/1`): the `reminder` message in that conversation, `notify` = the
    participants (each gets the normal message push), then the text is wiped.
  """
  use Ecto.Schema

  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{ClientTools, Clock, Out, Seal, Secretary, Skills, Writes}
  alias RisiMe.Repo

  @primary_key {:reminder_id, :binary_id, autogenerate: false}
  schema "risi_reminders" do
    field :owner_id, :binary_id
    field :conversation_id, :string
    field :request_conversation_id, :string
    field :request_id, :binary_id
    field :turn_id, :binary_id
    field :write_id, :binary_id
    field :due_at, :utc_datetime_usec
    field :text, :binary
    field :participants, {:array, :binary_id}, default: []
    field :me_too, :boolean, default: false
    field :state, :string, default: "pending"
    field :inserted_at, :utc_datetime_usec
    field :updated_at, :utc_datetime_usec
  end

  ## The tool

  def tool do
    %{
      name: "set_reminder",
      description:
        "propose a reminder (the asker confirms first): when (an ISO time or a local phrase " <>
          "like \"Tuesday 2pm\"; ask if the day or time is unclear), text, audience: \"me\" " <>
          "(only the asker) or \"conversation\" (this chat; others may tap Me too)",
      where: :server,
      personal: false,
      write: true,
      finds: false,
      skill: "reminders",
      args: %{
        "type" => "object",
        "properties" => %{
          "when" => %{"type" => "string", "maxLength" => 100},
          "text" => %{"type" => "string", "minLength" => 1, "maxLength" => 200},
          "audience" => %{"enum" => ["me", "conversation"]}
        },
        "required" => ["when", "text", "audience"],
        "additionalProperties" => false
      },
      # A confirm card needs an app that can answer it (§25.8: v1.24 apps get no tool output).
      authorize: fn ctx ->
        if RisiMe.Devices.risi_tools_device?(ctx.asker, ctx.device_id), do: :ok, else: :denied
      end,
      run: &run/2,
      exec: &exec/3
    }
  end

  defp run(%{"when" => phrase, "text" => text, "audience" => audience}, ctx) do
    text = String.trim(text)

    with true <- (text != "" and String.length(text) <= 200) || {:error, "failed", "bad_text"},
         {:ok, due, kind} <- ClientTools.time(phrase, ctx),
         true <-
           kind == :datetime ||
             {:error, "failed", "ambiguous_time: ask the person which time of day"},
         :ok <- ClientTools.future(due, ctx) do
      others? =
        not ctx.in_risi_chat? and
          Enum.any?(Secretary.members(ctx.conv), &(&1.user_id != ctx.asker))

      group? = audience == "conversation" and others?
      at = "#{Calendar.strftime(Clock.local(due, ctx.tz), "%a %-d %b at %H:%M")}"

      summary =
        if group?,
          do: "Remind this chat on #{at}: #{text} (others can tap Me too)",
          else: "Remind you on #{at}: #{text}"

      {:propose,
       %{
         args: %{
           "when" => Clock.ts(due),
           "text" => text,
           "audience" => if(group?, do: "conversation", else: "me"),
           "at_text" => at
         },
         summary: summary,
         when: %{"start" => Clock.ts(due), "end" => nil, "all_day" => false},
         text: text,
         personal: not group?,
         skill_id: "reminders"
       }}
    end
  end

  defp run(_args, _ctx), do: {:error, "failed", "bad_args"}

  ## Confirmed

  @doc "Stores the confirmed (or allowed) reminder, schedules it, posts `reminder_set`."
  def exec(%Writes.Write{} = w, args, _device) do
    {:ok, due, _} = DateTime.from_iso8601(args["when"])
    due = Clock.usec(due)

    cond do
      DateTime.compare(due, DateTime.utc_now()) != :gt ->
        Writes.post(w, "That time has passed, so I didn't set the reminder.", %{
          "kind" => "error",
          "request_id" => w.request_id,
          "code" => "out_of_window",
          "notify" => [w.user_id]
        })

        {:error, "failed"}

      true ->
        create(w, args, due)
    end
  end

  defp create(w, args, due) do
    {:ok, key} = Seal.data()
    id = Ecto.UUID.generate()
    now = DateTime.utc_now()
    group? = args["audience"] == "conversation"

    Repo.insert!(%__MODULE__{
      reminder_id: id,
      owner_id: w.user_id,
      conversation_id: w.card_conversation_id,
      request_conversation_id: w.conversation_id,
      request_id: w.request_id,
      turn_id: w.turn_id,
      write_id: w.write_id,
      due_at: due,
      text: Seal.seal(key, aad(id), args["text"]),
      participants: [w.user_id],
      me_too: group?,
      state: "pending",
      inserted_at: now,
      updated_at: now
    })

    %{"kind" => "reminder_fire", "conv" => w.card_conversation_id, "reminder_id" => id}
    |> RisiMe.Workers.Risi.new(queue: :risi_timers, scheduled_at: due)
    |> Oban.insert!()

    name = if group?, do: owner_name(w), else: "you"

    body =
      "I'll remind #{name} on #{args["at_text"]}: #{args["text"]}." <>
        if(group?, do: " Tap Me too to be reminded as well.", else: "")

    Writes.post(w, body, %{
      "kind" => "reminder_set",
      "request_id" => w.request_id,
      "reminder_id" => id,
      "when" => Clock.ts(due),
      "text" => args["text"],
      "participants" => [w.user_id],
      "me_too" => group?,
      "turn_ref" => w.turn_id,
      "notify" => []
    })

    if Skills.gated?(w.user_id) do
      Skills.log!(
        w.user_id,
        "reminders",
        "reminder_set",
        "Reminder for #{args["at_text"]}: #{args["text"]}",
        via: w.via || "confirm",
        conversation_id: w.conversation_id,
        undo_kind: "server",
        undo_until: due,
        data: %{"reminder_id" => id}
      )
    end

    :ok
  end

  defp owner_name(w) do
    Enum.find_value(Secretary.members(w.card_conversation_id), "the asker", fn m ->
      if m.user_id == w.user_id, do: m.name
    end)
  end

  defp aad(id), do: "risi_reminders:" <> id

  ## Me too / Not me (§25.4)

  @doc "A `me_too` / `not_me` from `user` (an active human member) in `conv`. Oban result."
  def act(conv, user, %{"action" => action, "target" => target})
      when action in ~w(me_too not_me) do
    with {:ok, id} <- Ecto.UUID.cast(target),
         %__MODULE__{state: "pending"} = r <- Repo.get(__MODULE__, id),
         true <- r.conversation_id == conv,
         true <- DateTime.compare(DateTime.utc_now(), r.due_at) == :lt do
      case action do
        "me_too" when user != r.owner_id and r.me_too ->
          set_participants(r, Enum.uniq(r.participants ++ [user]))

        "me_too" ->
          :ok

        "not_me" ->
          case List.delete(r.participants, user) do
            [] -> cancel(r)
            rest -> set_participants(r, rest)
          end
      end
    else
      _ -> :ok
    end

    :ok
  end

  def act(_conv, _user, _env), do: :ok

  defp set_participants(r, list) do
    r
    |> Ecto.Changeset.change(participants: list, updated_at: DateTime.utc_now())
    |> Repo.update!()

    :ok
  end

  defp cancel(r) do
    r
    |> Ecto.Changeset.change(state: "cancelled", text: nil, updated_at: DateTime.utc_now())
    |> Repo.update!()

    cancel_job(r.reminder_id)
    :ok
  end

  defp cancel_job(id) do
    Oban.cancel_all_jobs(
      from j in Oban.Job,
        where:
          j.worker == "RisiMe.Workers.Risi" and
            j.state in ["available", "scheduled", "retryable"] and
            fragment("?->>'reminder_id' = ?", j.args, ^id)
    )
  end

  ## Firing

  @doc "Posts the `reminder` (§25.4) to the participants, once. Oban result."
  def fire(id) do
    with %__MODULE__{state: "pending", participants: [_ | _]} = r <- Repo.get(__MODULE__, id),
         {:ok, key} <- Seal.data(),
         {:ok, text} <- Seal.open(key, aad(id), r.text) do
      risi = %{
        "kind" => "reminder",
        "commitment_id" => nil,
        "reminder_id" => id,
        "due" => Clock.ts(r.due_at),
        "text" => text,
        "notify" => r.participants
      }

      case Out.post(r.conversation_id, "Reminder: #{text}", risi) do
        {:ok, _} ->
          r
          |> Ecto.Changeset.change(state: "fired", text: nil, updated_at: DateTime.utc_now())
          |> Repo.update!()

          :ok

        {:error, :rate_limited} ->
          {:snooze, 10}

        {:error, reason} ->
          Logger.warning("Risi reminder not posted: #{inspect(reason)}")
          :ok
      end
    else
      _ -> :ok
    end
  end

  ## Undo, revoke, deletion (§26.4, §26.5, §25.2)

  @doc "§26.4 server undo of a reminder that hasn't fired: `:ok` or `{:error, reason}`."
  def undo(user_id, %{"reminder_id" => id}) when is_binary(id) do
    case Repo.get(__MODULE__, id) do
      %__MODULE__{owner_id: ^user_id, state: "pending"} = r ->
        if DateTime.compare(DateTime.utc_now(), r.due_at) == :lt,
          do: cancel(r),
          else: {:error, :undo_unavailable}

      _ ->
        {:error, :undo_unavailable}
    end
  end

  def undo(_user_id, _data), do: {:error, :undo_unavailable}

  @doc "§26.5 `cancel_pending`: cancels the user's pending reminders (an entry for each)."
  def cancel_pending(user_id) do
    for r <-
          Repo.all(from r in __MODULE__, where: r.owner_id == ^user_id and r.state == "pending") do
      cancel(r)

      Skills.log!(user_id, "reminders", "reminder_cancelled", "Cancelled a pending reminder",
        via: "settings",
        conversation_id: r.request_conversation_id
      )
    end

    :ok
  end

  @doc "Deletes the reminders of a conversation (Official off, removal, a Risi chat left)."
  def forget(conv) do
    for r <- Repo.all(from r in __MODULE__, where: r.conversation_id == ^conv),
        do: cancel_job(r.reminder_id)

    Repo.delete_all(from r in __MODULE__, where: r.conversation_id == ^conv)
    :ok
  end

  @doc "Deletes fired or cancelled reminders a day later."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -86_400, :second)

    Repo.delete_all(
      from r in __MODULE__, where: r.state in ["fired", "cancelled"] and r.updated_at < ^cutoff
    )

    :ok
  end

  @doc "A reminder by id (tests)."
  def get(id), do: Repo.get(__MODULE__, id)
end
