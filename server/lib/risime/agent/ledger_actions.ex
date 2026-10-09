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

  @actions ~w(item_confirm item_edit item_decline done)

  @doc "The actions handled here (from a Risi chat)."
  def actions, do: @actions

  @doc "Handles one item action from `user` in the Risi chat `conv`. Oban result."
  def act(conv, user, %{"target" => target, "action" => action} = env) when action in @actions do
    with {:ok, _} <- Ecto.UUID.cast(target),
         true <- RisiChat.owner(conv) == user,
         %Commitment{} = c <- Repo.get(Commitment, target),
         true <- Commitment.ledger?(c),
         %Discussion{} = d <- Repo.get(Discussion, c.summary_id),
         true <- user in d.recipients,
         {:ok, c} <- Commitment.open(c) do
      do_act(action, c, d, user, env["edit"])
    else
      _ -> :ok
    end
  end

  def act(_conv, _user, _env), do: :ok

  defp expired?(%Discussion{created_at: at}),
    do: DateTime.compare(Clock.now(), DateTime.add(at, Ledger.expire_s())) != :lt

  defp do_act("item_confirm", %Commitment{item_state: "proposed"} = c, d, user, _edit) do
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

      tracked(c)
      LedgerOut.item_update(c, d, "confirmed", user)
    else
      :ok
    end
  end

  defp do_act("item_edit", %Commitment{item_state: state} = c, d, user, %{} = edit)
       when state in ~w(proposed confirmed edited) do
    with true <- c.owner_id == user,
         false <- state == "proposed" and expired?(d),
         {:ok, text, due, all_day} <- edit_values(c, edit),
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
      LedgerOut.item_update(c, d, "edited", user)
    else
      _ -> :ok
    end
  end

  defp do_act("item_decline", %Commitment{item_state: state} = c, d, user, _edit)
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

  defp do_act("done", %Commitment{item_state: state} = c, d, user, _edit)
       when state in ~w(confirmed edited) do
    if user == c.owner_id or user in c.counterpart_ids do
      c =
        c
        |> Ecto.Changeset.change(
          state: "done",
          item_state: "done",
          by: user,
          schedule_v: c.schedule_v + 1
        )
        |> Repo.update!()

      Commitments.cancel_timers(c.id)
      LedgerOut.item_update(c, d, "done", user)
    else
      :ok
    end
  end

  defp do_act(_action, _c, _d, _user, _edit), do: :ok

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
  defp tracked(c) do
    Commitments.cancel_timers(c.id)
    Commitments.learn(c)
    RisiMe.Agent.LedgerReminders.schedule(c)
    # Items 8/10: tracked now (counterparts may be offered too), or a new due after an edit.
    RisiMe.Agent.Offers.consider(c)
    :ok
  end
end
