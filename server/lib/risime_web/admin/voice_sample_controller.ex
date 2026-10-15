defmodule RisiMeWeb.Admin.VoiceSampleController do
  @moduledoc """
  `GET /admin/voice-samples/:token`: one call-voice sample (`RisiMe.Agent.TTS.VoicePick`), behind
  the admin sign-in (`RequireAdmin`). The token must be valid, unexpired (24 h) and made for the
  signed-in admin; otherwise 403 or 404, with nothing about the file. The audio is sent
  `Cache-Control: no-store`; the only record is the audit line `voice_sample_played`.
  """
  use Phoenix.Controller, formats: []

  import Plug.Conn

  alias RisiMe.Agent.TTS.VoicePick
  alias RisiMeWeb.Admin.Session

  def show(conn, %{"token" => token}) do
    admin = Session.admin_id(get_session(conn))

    with true <- is_binary(admin) || :forbidden,
         {:ok, file} <- VoicePick.verify(token, admin),
         {:ok, audio} <- VoicePick.read(file) do
      RisiMe.Admin.audit(admin, "voice_sample_played", file)

      conn
      |> put_resp_header("cache-control", "no-store")
      |> put_resp_header("x-content-type-options", "nosniff")
      |> put_resp_header("content-disposition", ~s(inline; filename="#{file}"))
      |> put_resp_content_type("audio/wav", nil)
      |> send_resp(200, audio)
    else
      {:error, :enoent} -> plain(conn, 404, "404 Not Found\n")
      {:error, :expired} -> plain(conn, 403, "This link has expired. Ask Risi for new samples.\n")
      _ -> plain(conn, 403, "403 Forbidden\n")
    end
  end

  defp plain(conn, status, text) do
    conn
    |> put_resp_header("cache-control", "no-store")
    |> put_resp_content_type("text/plain")
    |> send_resp(status, text)
  end
end
