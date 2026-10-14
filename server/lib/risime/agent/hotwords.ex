defmodule RisiMe.Agent.Hotwords do
  @moduledoc """
  Speech hints (proposal `2026-10-14-send-for-me` §41.6): words the speech service should spell
  right, passed **per request** as the service's `hotwords` field (`RisiMe.Agent.Speech`).

  * **Always:** `Risi`, `රිසි`, `ரிசி`.
  * **The sender's own contacts, only in the sender's own Risi chat:** the display names the
    server already holds for that user's friends and chat co-members (full names and first
    names, at most 50). Never for another user's note, never stored, never logged (the speech
    service logs only a count), never in the learning log. The phone's own recogniser (the
    Voice agent's L1) is the preferred place for contact names; this is the server fallback.
  * `fix_risi/1`: a Risi-chat request that starts with a common mis-hearing of the name
    ("Rishi,", "Risky,", "Reesi,", "රිෂි,") is read as "Risi," (only at the very start).
  """

  @always ["Risi", "රිසි", "ரிசி"]
  @max_names 50

  @doc "The words always hinted."
  def always, do: @always

  @doc """
  The hotwords for a note of `sender` in `conv`: Risi's name, plus (in the sender's own Risi
  chat) the sender's contacts' names.
  """
  def for_note(conv, sender) do
    if risi_chat_of?(conv, sender), do: @always ++ contact_names(sender), else: @always
  rescue
    _ -> @always
  end

  defp risi_chat_of?(conv, sender),
    do:
      is_binary(conv) and RisiMe.Groups.Tabs.risi_chat?(conv) and
        RisiMe.RisiChat.active_id(sender) == conv

  @doc "The sender's own contacts' display names (full and first), at most 50."
  def contact_names(user) do
    user
    |> RisiMe.Agent.CalendarTools.contacts()
    |> Enum.flat_map(fn {_id, n} ->
      n = String.trim(n || "")
      first = n |> String.split() |> List.first()
      if first && first != n, do: [n, first], else: [n]
    end)
    |> Enum.reject(&(&1 == ""))
    |> Enum.uniq()
    |> Enum.take(@max_names)
  end

  @mishears ~r/\A\s*(?:rishi|risky|riskey|reesi|reesie|reecy|ricy|rissy|rizzy|risee|risi|රිෂි|රිසී|රීසි|ரிஷி)\s*([,،:!.]|\s)/iu

  @doc "A Risi-chat request's opening mis-hearing of the name, read as \"Risi\"."
  def fix_risi(text) when is_binary(text) do
    case Regex.run(@mishears, text, return: :index) do
      [{0, len}, {sep_at, sep_len}] ->
        said = binary_part(text, 0, sep_at)
        sep = binary_part(text, sep_at, sep_len)
        sep = if String.trim(sep) == "", do: " ", else: sep
        name(said) <> sep <> binary_part(text, len, byte_size(text) - len)

      _ ->
        text
    end
  end

  def fix_risi(other), do: other

  # The name in the script it was heard in.
  defp name(said) do
    cond do
      said =~ ~r/\p{Sinhala}/u -> "රිසි"
      said =~ ~r/\p{Tamil}/u -> "ரிசி"
      true -> "Risi"
    end
  end
end
