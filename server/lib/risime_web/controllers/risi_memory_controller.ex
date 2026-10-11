defmodule RisiMeWeb.RisiMemoryController do
  @moduledoc """
  Risi memory controls (contract v1.36 §35.3, decision 077), always in the caller's own scope:

    * `DELETE /api/v1/risi/memory?conversation_id=grp:…` → `204`: the caller's messages, Risi's
      posts to the caller and the items derived from them in that chat (other members' rows
      stay);
    * `DELETE /api/v1/risi/memory` → `204` over every chat, only with `X-Confirm: delete-all`
      (else `400 bad_request`);
    * `PUT /api/v1/risi/learning` `{"conversation_id": "grp:…" | null, "enabled": bool}` → `204`:
      "Stop Risi learning from my messages" in one chat or everywhere;
    * `GET /api/v1/risi/learning` → `{"global": bool, "chats": [{"conversation_id", "enabled"}]}`.

  A malformed `conversation_id` or `enabled` is `422 bad_request`. 30 calls per user per minute.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agent.Memory
  alias RisiMe.RateLimiter
  alias RisiMeWeb.ApiError

  @limit 30
  @window :timer.minutes(1)

  defp me(conn), do: conn.assigns.current_user.id

  defp limited(conn, fun) do
    if RateLimiter.hit_if_allowed(:risi_memory_rest, me(conn), @limit, @window) == :ok,
      do: fun.(),
      else:
        ApiError.send_error(conn, 429, :rate_limited,
          retry_after: RateLimiter.retry_after_s(@window)
        )
  end

  def delete(conn, params) do
    limited(conn, fn ->
      case params["conversation_id"] do
        nil ->
          if get_req_header(conn, "x-confirm") == ["delete-all"] do
            :ok = Memory.delete_user(me(conn), :all)
            send_resp(conn, 204, "")
          else
            ApiError.send_error(conn, 400, :bad_request,
              message: "Deleting all of Risi's memory needs X-Confirm: delete-all"
            )
          end

        c ->
          if conv?(c) do
            :ok = Memory.delete_user(me(conn), c)
            send_resp(conn, 204, "")
          else
            ApiError.send_error(conn, 422, :bad_request)
          end
      end
    end)
  end

  def put_learning(conn, params) do
    limited(conn, fn ->
      conv = params["conversation_id"]

      if is_boolean(params["enabled"]) and (conv == nil or conv?(conv)) do
        :ok = Memory.set_learning(me(conn), conv, params["enabled"])
        send_resp(conn, 204, "")
      else
        ApiError.send_error(conn, 422, :bad_request)
      end
    end)
  end

  def learning(conn, _params) do
    limited(conn, fn -> json(conn, Memory.learning(me(conn))) end)
  end

  defp conv?("grp:" <> id), do: match?({:ok, _}, Ecto.UUID.cast(id))
  defp conv?(_), do: false
end
