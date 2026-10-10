defmodule RisiMe.Agent.GoogleCards do
  @moduledoc """
  The `google_reconnect` card (contract v1.31 §31.8): when the Google device reports
  `reauth_needed`, the user's Risi chat gets **one** card, at most one per 24 h
  (`risi_gcal_links.reconnect_card_at`). A server card (`made_by.model` null), through
  `RisiMe.Agent.Out` like the other cards. The body names the Google device's `device_name`.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{GoogleLink, Out}
  alias RisiMe.RisiChat
  alias RisiMe.Repo

  @every_s 86_400

  @doc "Posts the card for `link` (a `reauth_needed` link) unless one went out in the last 24 h."
  def reconnect(%GoogleLink{} = link) do
    now = DateTime.utc_now()

    with rc when is_binary(rc) <- RisiChat.active_id(link.user_id),
         true <- claim(link.user_id, now) do
      name = GoogleLink.device_name(link.device_id) || "your Google phone"

      body =
        "Google Calendar needs reconnecting. Until then I can't see your Google busy times " <>
          "or copy events there. Reconnect it on #{name}: Settings → Risi skills → Calendar."

      risi = %{
        "kind" => "google_reconnect",
        "reason" => "reauth_needed",
        "device_id" => link.device_id,
        "buttons" => ["reconnect"],
        "notify" => [link.user_id]
      }

      case Out.post(rc, body, risi) do
        {:ok, _} -> :ok
        {:error, reason} -> Logger.warning("google_reconnect card not sent: #{inspect(reason)}")
      end
    else
      _ -> :ok
    end
  end

  # One card per 24 h, claimed atomically (a second PUT in the window posts nothing).
  defp claim(user_id, now) do
    cutoff = DateTime.add(now, -@every_s, :second)

    {n, _} =
      Repo.update_all(
        from(l in GoogleLink,
          where:
            l.user_id == ^user_id and
              (is_nil(l.reconnect_card_at) or l.reconnect_card_at < ^cutoff)
        ),
        set: [reconnect_card_at: now]
      )

    n == 1
  end
end
