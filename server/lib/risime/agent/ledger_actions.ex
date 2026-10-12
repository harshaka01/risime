defmodule RisiMe.Agent.LedgerActions do
  @moduledoc """
  Confirming items and the item life cycle (contract v1.27 §27.5). The `risi_action` envelope
  is sent **in the actor's own Risi chat** (where the summary copy is), `target` = `item_id`:

  | `action` | who (others are ignored) | when | new state |
  |---|---|---|---|
  | `item_confirm` | the owner | `proposed`, before `expires_at` | `confirmed` |
  | `item_edit` | the owner | `proposed` before `expires_at`, or `confirmed`/`edited` | `edited` |
  | `item_decline` | the owner | `proposed` → `declined`; `confirmed`/`edited` → `cancelled` | as stated |
  | `done` | the owner or a counterpart | `confirmed`/`edited` | `done` |
  | `item_reopen` (v1.29 §30.5) | the owner or a counterpart | `done`, within 7 days | the state before `done` |

  Risi checks that the Risi chat is the actor's own (the worker already checked the attested
  sender) and that the item belongs to a summary the actor received. Repeats are idempotent
  (the state no longer matches). Declined and cancelled items are deleted; confirmed and edited
  ones are the owner's promises (facts are written, the §27.6 reminders scheduled). After every
  accepted action an `item_update` goes to every recipient's Risi chat
  (`RisiMe.Agent.LedgerOut.item_update/4`).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, Commitment, Commitments, Discussion, Fact, Ledger, LedgerOut}
  alias RisiMe.{Repo, RisiChat}

  # v1.42 §38.4: `item_calendar`, `item_remind`, `item_not_mine` (owner only, the action items'
  # confirm card; `RisiMe.Agent.ActionItems`).
  # v1.43 §38.9: `item_claim` (any member, an unowned action item; `ActionItems.claim/3`).
  @actions ~w(item_confirm item_edit item_decline done item_reopen item_calendar item_remind
              item_not_mine item_claim)
  @reopen_s 7 * 86_400

  @doc "The actions handled here (from a Risi chat)."
  def actions, do: @actions

  @doc "Handles one item action from `user` in the Risi chat `conv`. Oban result."
  def act(conv, user, %{"target" => target, "action" => "item_claim"}) when is_binary(target) do
    if RisiChat.owner(conv) == user,
      do: RisiMe.Agent.ActionItems.claim(conv, user, target),
      else: :ok
  end

  def act(conv, user, %{"target" => target, "action" => action} = env) when action in @actions do
    with {:ok, _} <- Ecto.UUID.cast(target),
         true <- RisiChat.owner(conv) == user,
         %Commitment{} = c <- Repo.get(Commitment, target),
         true <- Commitment.ledger?(c),
         %Discussion{} = d <- Repo.get(Discussion, c.summary_id),
         true <- user in d.recipients,
         {:ok, c} <- Commitment.open(c) do
      do_act(action, c, d, user, env["edit"], conv)
    else
      _ -> :ok
    end
  end

  def act(_conv, _user, _env), do: :ok

  defp expired?(%Discussion{created_at: at}),
    do: DateTime.compare(Clock.now(), DateTime.add(at, Ledger.expire_s())) != :lt

  defp do_act(action, c, d, user, edit, conv)

  defp do_act("item_confirm", %Commitment{item_state: "proposed"} = c, d, user, _edit, _conv) do
    case confirm(c, d, user) do
      :ok ->
        RisiMe.Agent.ActionItems.feedback(c, user, "confirm")

      _ ->
        :ok
    end
  end

  # v1.42 §38.4: the owner's other buttons (the tap is the consent, §26.3).
  defp do_act(action, %Commitment{item_state: state} = c, d, user, edit, conv)
       when action in ~w(item_calendar item_remind) and state in ~w(proposed confirmed edited) do
    cond do
      c.owner_id != user ->
        :ok

      state == "proposed" and expired?(d) ->
        :ok

      action == "item_calendar" ->
        RisiMe.Agent.ActionItems.calendar(conv, c, d, user, edit)

      true ->
        RisiMe.Agent.ActionItems.remind(c, d, user, edit)
    end
  end

  defp do_act("item_not_mine", %Commitment{item_state: "proposed"} = c, d, user, _edit, _conv) do
    if c.owner_id == user and not expired?(d) do
      # Declined; offered to nobody else; the owner's feedback.
      Commitments.cancel_timers(c.id)
      Repo.delete!(c)
      RisiMe.Agent.ActionItems.feedback(c, user, "not_mine")
      LedgerOut.item_update(c, d, "declined", user)
    else
      :ok
    end
  end

  defp do_act("item_edit", %Commitment{item_state: state} = c, d, user, %{} = edit, _conv)
       when state in ~w(proposed confirmed edited) do
    with true <- c.owner_id == user,
         false <- state == "proposed" and expired?(d),
         {:ok, text, due, all_day} <- edit_values(c, edit),
         old_text = c.text,
         old_due = c.due,
         due_text = if(due == c.due, do: c.due_text, else: nil),
         {:ok, sealed} <- Commitment.sealed_changes(c.id, text, due_text) do
      c =
        c
        |> Ecto.Changeset.change(
          [
            state: "edited",
            item_state: "edited",
            due: due,
            due_kind: if(due, do: if(all_day, do: "date", else: "datetime")),
            all_day: all_day,
            by: user,
            confirmed_at: c.confirmed_at || Clock.usec(Clock.now()),
            schedule_v: c.schedule_v + 1,
            needs_clarification: RisiMe.Agent.Offers.vague?(due, due_text)
          ] ++ sealed
        )
        |> Repo.update!()

      Repo.delete_all(from f in Fact, where: f.commitment_id == ^c.id)
      tracked(c)

      RisiMe.Agent.ActionItems.feedback(
        c,
        user,
        "edit",
        for({k, true} <- [{"text", text != old_text}, {"due", due != old_due}], do: k)
      )

      LedgerOut.item_update(c, d, "edited", user)
    else
      _ -> :ok
    end
  end

  defp do_act("item_decline", %Commitment{item_state: state} = c, d, user, _edit, _conv)
       when state in ~w(proposed confirmed edited) do
    if c.owner_id == user do
      # ✗ on a proposal: declined; on a tracked one: cancelled. Either way nothing is kept.
      Commitments.cancel_timers(c.id)
      Repo.delete!(c)

      LedgerOut.item_update(
        c,
        d,
        if(state == "proposed", do: "declined", else: "cancelled"),
        user
      )
    else
      :ok
    end
  end

  defp do_act("done", %Commitment{item_state: state} = c, d, user, _edit, _conv)
       when state in ~w(confirmed edited) do
    if user == c.owner_id or user in c.counterpart_ids do
      c =
        c
        |> Ecto.Changeset.change(
          state: "done",
          item_state: "done",
          by: user,
          schedule_v: c.schedule_v + 1,
          # v1.29 §30.5: what `item_reopen` goes back to, within 7 days.
          done_at: Clock.usec(Clock.now()),
          reopen_state: state
        )
        |> Repo.update!()

      Commitments.cancel_timers(c.id)
      LedgerOut.item_update(c, d, "done", user)
    else
      :ok
    end
  end

  # v1.29 §30.5: un-ticking a done item (a note, My promises or an `item_due` card).
  defp do_act("item_reopen", %Commitment{item_state: "done"} = c, d, user, _edit, _conv) do
    back = if c.reopen_state in ~w(confirmed edited), do: c.reopen_state, else: "confirmed"

    recent? =
      c.done_at != nil and DateTime.diff(Clock.now(), c.done_at) <= @reopen_s

    if recent? and (user == c.owner_id or user in c.counterpart_ids) do
      c =
        c
        |> Ecto.Changeset.change(
          state: back,
          item_state: back,
          by: user,
          schedule_v: c.schedule_v + 1,
          done_at: nil,
          reopen_state: nil
        )
        |> Repo.update!()

      Commitments.cancel_timers(c.id)
      RisiMe.Agent.LedgerReminders.schedule(c)
      LedgerOut.item_update(c, d, "reopened", user)
    else
      :ok
    end
  end

  defp do_act(_action, _c, _d, _user, _edit, _conv), do: :ok

  @doc """
  `item_confirm` of a proposed item by its owner before it expires: `confirmed`, tracked, the
  `item_update`s. `:ok`, or `:error` when it can't be confirmed (v1.42 §38.4 reuses it).
  """
  def confirm(c, d, user, opts \\ [])

  def confirm(%Commitment{item_state: "proposed"} = c, d, user, opts) do
    if c.owner_id == user and not expired?(d) do
      c =
        c
        |> Ecto.Changeset.change(
          state: "confirmed",
          item_state: "confirmed",
          by: user,
          confirmed_at: Clock.usec(Clock.now()),
          schedule_v: c.schedule_v + 1
        )
        |> Repo.update!()

      tracked(c, opts)
      LedgerOut.item_update(c, d, "confirmed", user)
      :ok
    else
      :error
    end
  end

  def confirm(_c, _d, _user, _opts), do: :error

  # §27.5: `edit.text` 1–200 characters (absent: unchanged); `due` a future ts or null (absent:
  # unchanged); `all_day` a boolean (absent: unchanged).
  defp edit_values(c, edit) do
    text =
      case edit["text"] do
        t when is_binary(t) -> String.trim(t)
        nil -> c.text
        _ -> ""
      end

    due =
      case Map.fetch(edit, "due") do
        :error ->
          {:ok, c.due}

        {:ok, nil} ->
          {:ok, nil}

        {:ok, s} when is_binary(s) ->
          case DateTime.from_iso8601(s) do
            {:ok, dt, _} ->
              if DateTime.compare(dt, Clock.now()) == :gt,
                do: {:ok, Clock.usec(dt)},
                else: :error

            _ ->
              :error
          end

        _ ->
          :error
      end

    all_day =
      case edit["all_day"] do
        b when is_boolean(b) -> b
        _ -> c.all_day
      end

    case due do
      {:ok, due} when text != "" ->
        if String.length(text) <= 200, do: {:ok, text, due, all_day == true}, else: :error

      _ ->
        :error
    end
  end

  # A tracked item (confirmed or edited): its facts and its reminders (§27.6).
  defp tracked(c, opts \\ []) do
    Commitments.cancel_timers(c.id)
    Commitments.learn(c)
    RisiMe.Agent.LedgerReminders.schedule(c)
    # Items 8/10: tracked now (counterparts may be offered too), or a new due after an edit.
    # v1.42 §38.4: not for an action item: its confirm card's own buttons are the offers, and
    # nothing proactive runs without the owner's tap.
    if c.kind == nil, do: RisiMe.Agent.Offers.consider(c)

    # v1.43 §38.9 point 2: an action item tracked without a usable date: Risi asks when (once).
    if c.kind != nil and c.needs_clarification and Keyword.get(opts, :ask_date, true),
      do: RisiMe.Agent.Offers.ask_date(c)

    :ok
  end
end
