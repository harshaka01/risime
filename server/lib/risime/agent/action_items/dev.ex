defmodule RisiMe.Agent.ActionItems.Dev do
  @moduledoc """
  Developer preview of the §38 extraction (decision 082): runs the action-items extraction on a
  transcript still in the sealed 24-h buffer and returns the `action_items` card it would post,
  **without posting or storing anything** (no ledger rows, no extras, no confirm cards; the
  buffer is left as it is). Only for a conversation `member_id` is an active human member of,
  only Official. The output is the card (items, owners, dues, line refs), never the transcript.

  The model call writes its usual `extract_actions` learning-log row (counts only).

      # on spark2, against the running release (decision 023):
      scripts/risi-rpc 'RisiMe.Agent.ActionItems.Dev.print_call("grp:…", "<session_id>", "<member user id>")'
      # in a dev checkout:
      mix risime.action_items.preview --conversation grp:… --session <id> --member <user id>
      mix risime.action_items.preview --conversation grp:… --note <message id> --member <user id>
  """
  alias RisiMe.Agent.{ActionItems, Secretary}
  alias RisiMe.Agent.CallTranscribe.{Session, Summary}
  alias RisiMe.Repo

  @doc """
  The card a stored call session would get: `{:ok, %{body, risi}}` or `{:error, reason}`
  (`:not_member`, `:not_official`, `:no_session`, `:no_transcript`, or the model's error).
  """
  def preview_call(conv, session_id, member_id) do
    with :ok <- allowed(conv, member_id),
         %Session{conversation_id: ^conv} = s <-
           Repo.get(Session, session_id) || {:error, :no_session},
         [_ | _] = rows <- Summary.segments(conv, s) do
      src = ActionItems.call_source(conv, s, rows)

      case ActionItems.gate(rows) do
        nil -> preview(src, ActionItems.call_lines(src, rows))
        reason -> {:ok, ActionItems.unclear_card(src, reason)}
      end
    else
      [] -> {:error, :no_transcript}
      %Session{} -> {:error, :no_session}
      {:error, _} = e -> e
      nil -> {:error, :no_session}
    end
  end

  @doc "The card a voice note's buffered transcript would get (as `preview_call/3`)."
  def preview_note(conv, note_id, member_id) do
    with :ok <- allowed(conv, member_id),
         %{"type" => "voice_transcript"} = env <-
           Secretary.envelope(conv, note_id) || {:error, :no_transcript} do
      src = ActionItems.voice_source(conv, note_id, env)

      case env["status"] do
        "ok" -> preview(src, ActionItems.voice_lines(src, env))
        _ -> {:ok, ActionItems.unclear_card(src, env["reason"] || "no_speech")}
      end
    else
      {:error, _} = e -> e
      _ -> {:error, :no_transcript}
    end
  end

  @doc "Prints `preview_call/3`'s card as JSON (the body line, then the `risi` object)."
  def print_call(conv, session_id, member_id),
    do: print(preview_call(conv, session_id, member_id))

  @doc "Prints `preview_note/3`'s card."
  def print_note(conv, note_id, member_id), do: print(preview_note(conv, note_id, member_id))

  defp print({:ok, %{body: body, risi: risi}}) do
    IO.puts(body)
    IO.puts(Jason.encode!(risi, pretty: true))
    :ok
  end

  defp print({:error, reason}) do
    IO.puts("no card: #{inspect(reason)}")
    {:error, reason}
  end

  defp allowed(conv, member_id) do
    cond do
      not RisiMe.Agent.official?(conv) -> {:error, :not_official}
      RisiMe.Groups.Tabs.risi_chat?(conv) -> {:error, :not_official}
      not Secretary.active_human?(conv, member_id) -> {:error, :not_member}
      true -> :ok
    end
  end

  defp preview(src, lines) do
    case ActionItems.extract(src, lines) do
      {:ok, found} -> {:ok, ActionItems.preview_card(src, found)}
      {:error, _} = e -> e
    end
  end
end
