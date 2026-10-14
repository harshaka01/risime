defmodule RisiMeWeb.TranslateController do
  @moduledoc """
  `POST /api/v1/translate` (decision 092, proposal 2026-10-14-private-transcribe §3): the Private
  translate request. The phone sends **only** the text the user chose to translate and the
  target language, after the user's explicit yes; no conversation, no message id, no
  conversation access is needed or checked.

      {"text": "…", "target": "en" | "si" | "ta", "source"?: "en" | "si" | "ta"}
      → 200 {"text": "…", "target": "si", "source": "en" | null, "engine": "risime-server"}

  Errors: `404 not_found` while `RISI_PRIVATE_TRANSCRIBE` doesn't apply to the caller, `422
  bad_request` (empty, over 4000 characters / 12 000 bytes, or a bad language), `429
  rate_limited` (30 per user per minute), `503 agent_unavailable` (the model failed).

  **Nothing is kept:** the route is `log: false` (no "Parameters" line), the handling runs in
  `RisiMe.Agent.Translate.sensitive/1`, the text is never logged, stored or enqueued, and the
  learning log gets metadata only (`RisiMe.Agent.Translate`).
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agent.Translate
  alias RisiMe.Agents.Flags
  alias RisiMe.RateLimiter
  alias RisiMeWeb.ApiError

  @limit 30
  @window :timer.minutes(1)
  @flag "RISI_PRIVATE_TRANSCRIBE"

  @doc "True while the Private transcribe/translate feature applies to `user`."
  def enabled_for?(user), do: Flags.feature_for(@flag, user)

  def create(conn, params) do
    Translate.sensitive(fn -> handle(conn, params) end)
  end

  defp handle(conn, params) do
    me = conn.assigns.current_user.id

    cond do
      not enabled_for?(me) ->
        ApiError.send_error(conn, 404, :not_found)

      RateLimiter.hit_if_allowed(:private_translate, me, @limit, @window) != :ok ->
        ApiError.send_error(conn, 429, :rate_limited,
          retry_after: RateLimiter.retry_after_s(@window)
        )

      true ->
        case Translate.text(
               field(params, "text"),
               field(params, "target"),
               field(params, "source")
             ) do
          {:ok, r} ->
            json(conn, %{
              text: r.text,
              target: r.target,
              source: r.source,
              engine: "risime-server"
            })

          {:error, :bad_request} ->
            ApiError.send_error(conn, 422, :bad_request)

          {:error, _} ->
            ApiError.send_error(conn, 503, :agent_unavailable)
        end
    end
  end

  defp field(%{} = params, k), do: Map.get(params, k)
  defp field(_, _), do: nil
end
