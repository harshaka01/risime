defmodule RisiMe.Agent.CallRisi.VoiceConfirm do
  @moduledoc """
  Contract §41.9 (Harsha 2026-10-15, after "risi failed to add appointment in the calendar"): on a
  Call Risi call, once Risi has read the drafted event back ("Discuss racing, tomorrow, 9 to 10
  am. Shall I add it?"), a clear yes from the caller ("yes", "add it", "that's correct") confirms
  that pending card **server-side exactly like tapping Add**: the same `RisiMe.Agent.Writes.act/4`
  `confirm_write` (same write path, same `write_id` as the result id, the same `ActionGuard` check).
  Risi then says "Added: …" only from that result (`RisiMe.Agent.ClaimGuard`).

  Only the caller's **own** Risi Calendar and reminder writes (`risi_calendar_add`, `set_reminder`:
  server-side; the phone's own `calendar_add`/`set_alarm` need the tap on the phone, §25.3), a
  personal card in the caller's own Risi chat, still pending;
  never money, deletion or sending to other people. Flag `RISI_CALL_VOICE_CONFIRM`
  (`off | canary | on`, default `canary`).
  """

  import Ecto.Query

  alias RisiMe.Agent.Writes

  @flag "RISI_CALL_VOICE_CONFIRM"
  # Server-side writes only: the phone runs `calendar_add` / `set_alarm` only after its user's own
  # tap on the card (§25.3 rule a) or a typed request (rule b), so a voice yes can't run them.
  @tools ~w(risi_calendar_add set_reminder)
  @wait_ms 8_000

  @doc "The flag's name."
  def flag, do: @flag

  @doc "The tools a voice yes may confirm."
  def tools, do: @tools

  @doc "True when a voice yes confirms for `user_id`."
  def enabled?(user_id) do
    RisiMe.Agents.Flags.feature_for(@flag, user_id)
  rescue
    _ -> false
  end

  @doc """
  Confirms the call's pending card of `request_id` for the caller (`ctx`: `user_id`, `device_id`,
  `conv` = the caller's Risi chat). `{:done, write_id}`, `{:failed, state}` (the write ran and
  didn't go through, or is still running after the wait) or `{:not_allowed, why}` (nothing ran).
  """
  def confirm(request_id, ctx) do
    case Application.get_env(:risime, :risi_call_voice_confirm) do
      f when is_function(f, 2) -> f.(request_id, ctx)
      _ -> do_confirm(request_id, ctx)
    end
  end

  defp do_confirm(request_id, ctx) do
    with {:ok, rid} <- Ecto.UUID.cast(request_id),
         %Writes.Write{} = w <- pending(rid),
         :ok <- allowed(w, ctx) do
      # Unlinked: a barge-in that kills the call's turn never leaves the write half-run.
      Task.start(fn ->
        Writes.act(w.card_conversation_id, ctx.user_id, ctx.device_id, %{
          "action" => "confirm_write",
          "target" => w.write_id
        })
      end)

      wait(w.write_id, System.monotonic_time(:millisecond) + @wait_ms)
    else
      {:not_allowed, _} = no -> no
      _ -> {:not_allowed, :no_pending_card}
    end
  rescue
    _ -> {:not_allowed, :error}
  end

  defp pending(rid) do
    RisiMe.Repo.one(
      from w in Writes.Write,
        where: w.request_id == ^rid and w.state == "pending",
        order_by: [desc: w.inserted_at],
        limit: 1
    )
  end

  @doc false
  # Only the caller's own, personal calendar/reminder card in the conversation of the call.
  def allowed(w, ctx) do
    cond do
      w.user_id != ctx.user_id -> {:not_allowed, :not_the_asker}
      w.tool not in @tools -> {:not_allowed, :tool}
      w.personal != true -> {:not_allowed, :not_personal}
      w.card_conversation_id != ctx.conv -> {:not_allowed, :other_chat}
      true -> :ok
    end
  end

  defp wait(wid, deadline) do
    case Writes.get(wid) do
      %{state: "done"} ->
        {:done, wid}

      # "pending" for a moment (the confirm runs in its own task), then "running".
      %{state: s} when s in ~w(pending running) ->
        if System.monotonic_time(:millisecond) < deadline do
          Process.sleep(250)
          wait(wid, deadline)
        else
          {:failed, s}
        end

      %{state: s} ->
        {:failed, s}

      nil ->
        {:failed, "gone"}
    end
  end
end
