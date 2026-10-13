defmodule RisiMeWeb.Admin.Headers do
  @moduledoc """
  Security headers of the admin console (decision 089): Phoenix's secure browser headers plus a
  strict Content-Security-Policy (scripts only from /admin/assets, the LiveView socket on the
  same origin, no framing) and `Cache-Control: no-store`.
  """
  def init(opts), do: opts

  def call(conn, _opts) do
    # The public URL (Caddy terminates TLS, so the conn itself is plain HTTP on loopback).
    url = RisiMeWeb.Endpoint.struct_url()
    ws = if url.scheme == "https", do: "wss://", else: "ws://"

    csp =
      Enum.join(
        [
          "default-src 'self'",
          "script-src 'self'",
          "style-src 'self' 'unsafe-inline'",
          "img-src 'self' data:",
          # The Voices page's test players (audio returned in the page, never a URL).
          "media-src 'self' data:",
          "connect-src 'self' #{ws}#{url.host}#{port(url)}",
          "frame-ancestors 'none'",
          "base-uri 'none'",
          "form-action 'self'",
          "object-src 'none'"
        ],
        "; "
      )

    conn
    |> Phoenix.Controller.put_secure_browser_headers(%{
      "content-security-policy" => csp,
      "cache-control" => "no-store",
      "referrer-policy" => "no-referrer"
    })
  end

  defp port(%URI{scheme: "https", port: 443}), do: ""
  defp port(%URI{scheme: "http", port: 80}), do: ""
  defp port(%URI{port: p}) when is_integer(p), do: ":#{p}"
  defp port(_), do: ""
end
