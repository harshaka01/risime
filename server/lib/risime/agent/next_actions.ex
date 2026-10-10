defmodule RisiMe.Agent.NextActions do
  @moduledoc """
  `answer.next_actions` (contract v1.32 §25.4): the chips of a v1.32 app, at most 3, built from
  the model's `next_steps`.

    * an instruction about the phone's settings ("Connect your calendar in Settings", "Turn on
      the Calendar skill", "Allow calendar access") becomes an `open` action with a deep-link
      target, never `ask`;
    * a question or a request Risi can run becomes an `ask` (`text` is sent as the user's own
      Risi request);
    * anything else is dropped.

  `next_steps` stays for old apps.
  """

  @max 3

  @doc "`[next_action]` for the raw `next_steps` the model gave."
  def build(steps) do
    (steps || [])
    |> Enum.filter(&is_binary/1)
    |> Enum.map(&String.trim/1)
    |> Enum.reject(&(&1 == ""))
    |> Enum.map(&classify/1)
    |> Enum.reject(&is_nil/1)
    |> Enum.uniq()
    |> Enum.take(@max)
  end

  @doc "One step as an action map, or nil."
  def classify(step) do
    s = String.slice(step, 0, 120)

    cond do
      Regex.match?(
        ~r/\b(?:allow|grant|enable)\b[^.?]{0,30}\bcalendar (?:access|permission)|calendar permission/i,
        s
      ) ->
        open(s, "settings.calendar_permission")

      Regex.match?(~r/\bnotifications?\b/i, s) and instruction?(s) ->
        open(s, "settings.notifications")

      Regex.match?(~r/\b(?:turn on|enable|switch on)\b[^.?]{0,30}\bskills?\b|risi skills/i, s) ->
        open(s, "settings.risi_skills")

      Regex.match?(
        ~r/\b(?:connect|link|set up|sign in)\b[^.?]{0,30}\bcalendar\b|calendar settings/i,
        s
      ) ->
        open(s, "settings.calendar")

      Regex.match?(~r/\bsettings\b/i, s) ->
        nil

      ask?(s) ->
        %{"label" => s, "action" => "ask", "text" => s}

      true ->
        nil
    end
  end

  defp open(label, target), do: %{"label" => label, "action" => "open", "target" => target}

  defp instruction?(s), do: Regex.match?(~r/^(?:turn|allow|enable|switch|open|check|go)\b/i, s)

  # A question, or a request starting with a verb Risi can act on.
  @verbs ~w(show list what when who where how which is am are do does can remind add check
            summarise summarize find search draft tell give set schedule)
  defp ask?(s) do
    String.ends_with?(s, "?") or
      String.downcase(s) |> String.split(~r/\s+/, parts: 2) |> hd() |> then(&(&1 in @verbs))
  end
end
