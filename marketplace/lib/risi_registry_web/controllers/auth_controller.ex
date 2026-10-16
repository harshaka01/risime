defmodule RisiRegistryWeb.AuthController do
  @moduledoc """
  OIDC sign-in (Keycloak realm `aoa`, client `risi-marketplace`, code + PKCE). Every sign-in
  attempt that reaches the callback writes one audit row: the subject and a result code, no
  address and no token.
  """
  use Phoenix.Controller, formats: [:html]
  import Plug.Conn
  require Logger
  alias RisiRegistry.{AllowList, Audit, OIDC}
  alias RisiRegistryWeb.Pages

  def login(conn, _params) do
    case OIDC.authorize() do
      {:ok, %{url: url} = req} ->
        conn
        |> put_session("oidc", Map.take(req, [:state, :nonce, :verifier]) |> stringify())
        |> redirect(external: url)

      {:error, :not_configured} ->
        conn |> put_status(503) |> html(Pages.unavailable("Sign-in is not set up yet."))

      {:error, reason} ->
        Logger.warning("oidc authorize failed: #{inspect(reason)}")

        conn
        |> put_status(503)
        |> html(
          Pages.unavailable("The sign-in service is not reachable. Please try again later.")
        )
    end
  end

  def callback(conn, %{"error" => _}) do
    audit("anonymous", "idp_error")

    conn
    |> clear_oidc()
    |> put_status(401)
    |> html(Pages.unavailable("Sign-in was cancelled or refused."))
  end

  def callback(conn, %{"code" => code, "state" => state})
      when is_binary(code) and is_binary(state) do
    pending = get_session(conn, "oidc")
    conn = clear_oidc(conn)

    with %{"state" => expected, "nonce" => nonce, "verifier" => verifier} <- pending,
         true <- Plug.Crypto.secure_compare(expected, state) || {:error, :bad_state},
         {:ok, claims} <- OIDC.exchange(code, verifier, nonce) do
      finish(conn, claims)
    else
      nil -> fail(conn, "anonymous", :no_pending_sign_in)
      {:error, reason} -> fail(conn, "anonymous", reason)
      _ -> fail(conn, "anonymous", :bad_state)
    end
  end

  def callback(conn, _params), do: fail(clear_oidc(conn), "anonymous", :bad_request)

  def logout(conn, _params) do
    conn |> configure_session(drop: true) |> redirect(to: "/marketplace")
  end

  defp finish(conn, %{"sub" => sub} = claims) do
    email = claims["email"]
    actor = "oidc:" <> sub

    if claims["email_verified"] == true and AllowList.allowed?(email) do
      audit(actor, "allowed")

      conn
      |> configure_session(renew: true)
      |> put_session("user", %{
        "sub" => sub,
        "email" => email,
        "name" => claims["name"] || claims["preferred_username"]
      })
      |> redirect(to: "/marketplace/agents")
    else
      audit(actor, "not_listed")
      conn |> configure_session(drop: true) |> put_status(403) |> html(Pages.not_open())
    end
  end

  defp fail(conn, actor, reason) do
    audit(actor, "failed:" <> to_string(reason_atom(reason)))

    conn
    |> configure_session(drop: true)
    |> put_status(401)
    |> html(Pages.unavailable("Sign-in did not complete. Please try again."))
  end

  defp reason_atom({tag, _}), do: tag
  defp reason_atom(reason) when is_atom(reason), do: reason
  defp reason_atom(_), do: :error

  defp audit(actor, code), do: Audit.record("sign_in", %{actor: actor, result_code: code})

  defp clear_oidc(conn), do: delete_session(conn, "oidc")
  defp stringify(map), do: Map.new(map, fn {k, v} -> {to_string(k), v} end)
end
