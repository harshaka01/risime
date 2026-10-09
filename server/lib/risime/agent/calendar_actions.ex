defmodule RisiMe.Agent.CalendarActions do
  @moduledoc """
  The Risi Calendar `risi_action`s (contract v1.29 §29.11), from a human leaf (the attested
  sender, an active human member), in the actor's own Risi chat or the event's Official
  conversation:

  | action | target | edit | who |
  |---|---|---|---|
  | `event_accept` | event_id | `{"reminder_min"?}` or null | a participant |
  | `event_decline` | event_id | null | a participant |
  | `event_suggest` | event_id | `{"start", "end", "all_day"}` | a participant other than the owner |
  | `suggestion_use` / `suggestion_keep` | suggestion_id | null | the owner |

  Anything else (another user, another conversation, a malformed edit, the switch off) is
  ignored; repeating an action is idempotent. A card's `version` is not needed: the action
  applies to the current event. Same effect as the REST calls.
  """
  alias RisiMe.Agent.Calendar
  alias RisiMe.Agent.Calendar.{Event, Participant}

  @actions ~w(event_accept event_decline event_suggest suggestion_use suggestion_keep)

  def actions, do: @actions

  @doc "Handles one action envelope. Oban result (always `:ok`)."
  def act(conv, user, %{"action" => action, "target" => target} = env) when action in @actions do
    if Calendar.on?(), do: run(conv, user, action, target, env["edit"])
    :ok
  rescue
    e ->
      require Logger
      Logger.warning("Risi calendar action failed: #{inspect(e.__struct__)}")
      :ok
  end

  def act(_conv, _user, _env), do: :ok

  defp run(conv, user, action, target, edit) when action in ~w(suggestion_use suggestion_keep) do
    with {:ok, sid} <- Ecto.UUID.cast(target),
         %Calendar.Suggestion{} = s <- RisiMe.Repo.get(Calendar.Suggestion, sid),
         {%Event{} = e, _parts} <- Calendar.load(s.event_id),
         true <- edit == nil and where_ok?(conv, user, e) do
      Calendar.resolve(user, sid, %{"action" => String.replace_prefix(action, "suggestion_", "")})
    end
  end

  defp run(conv, user, action, target, edit) do
    with {:ok, id} <- Ecto.UUID.cast(target),
         {%Event{state: "active"} = e, parts} <- Calendar.load(id),
         %Participant{removed: false} = me <- Enum.find(parts, &(&1.user_id == user)),
         true <- where_ok?(conv, user, e) do
      case {action, edit} do
        {"event_accept", nil} ->
          Calendar.answer(e, me, "accepted", :keep)

        {"event_accept", %{"reminder_min" => r} = ed}
        when map_size(ed) == 1 and
               (is_nil(r) or (is_integer(r) and r in 0..10_080)) ->
          Calendar.answer(e, me, "accepted", r)

        {"event_accept", %{} = ed} when map_size(ed) == 0 ->
          Calendar.answer(e, me, "accepted", :keep)

        {"event_decline", nil} ->
          Calendar.answer(e, me, "declined", :keep)

        {"event_suggest", %{} = sug} ->
          Calendar.suggest(e, parts, me, sug)

        _ ->
          :ignored
      end
    end
  end

  # The actor's own Risi chat, or the event's Official conversation.
  defp where_ok?(conv, user, %Event{source_conversation_id: src}) do
    conv == RisiMe.RisiChat.active_id(user) or (is_binary(src) and conv == src)
  end
end
