defmodule RisiRegistryWeb.Pages do
  @moduledoc """
  The page shell's HTML: server-rendered EEx through `Phoenix.HTML.Engine`, so every value is
  escaped. No JavaScript, no external assets (the CSP allows only inline styles).
  """
  require EEx

  @layout """
  <!doctype html>
  <html lang="en">
  <head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex">
  <title><%= @title %> · Risi Marketplace</title>
  <style>
  :root { --bg:#fafaf8; --fg:#1d1d1b; --muted:#5f5f5a; --line:#e2e1dc; --accent:#1f5f8b; --chip:#eef3f7; }
  @media (prefers-color-scheme: dark) { :root { --bg:#151514; --fg:#ecebe6; --muted:#a3a29b; --line:#2f2f2c; --accent:#7fb6dd; --chip:#1f2a33; } }
  * { box-sizing:border-box }
  body { margin:0; background:var(--bg); color:var(--fg); font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif }
  header { display:flex; justify-content:space-between; align-items:center; padding:14px 16px; border-bottom:1px solid var(--line) }
  header strong { font-size:17px }
  main { max-width:920px; margin:0 auto; padding:24px 16px }
  h1 { font-size:22px; margin:0 0 8px }
  p.muted, .muted { color:var(--muted) }
  a.button, button { display:inline-block; background:var(--accent); color:var(--bg); border:0; border-radius:6px; padding:9px 16px; font:inherit; text-decoration:none; cursor:pointer }
  button.link { background:none; color:var(--accent); padding:0 }
  table { width:100%; border-collapse:collapse; margin-top:16px }
  th, td { text-align:left; padding:10px 8px; border-bottom:1px solid var(--line); vertical-align:top }
  th { font-size:13px; color:var(--muted); font-weight:600 }
  .chip { display:inline-block; background:var(--chip); border-radius:4px; padding:1px 7px; font-size:13px }
  .wrap { overflow-x:auto }
  </style>
  </head>
  <body>
  <header><strong>Risi Marketplace</strong><%= @nav %></header>
  <main><%= @inner %></main>
  </body>
  </html>
  """

  EEx.function_from_string(:defp, :layout, @layout, [:assigns], engine: Phoenix.HTML.Engine)

  EEx.function_from_string(
    :defp,
    :logout_nav,
    """
    <form method="post" action="/marketplace/auth/logout">
    <input type="hidden" name="_csrf_token" value="<%= @csrf %>">
    <span class="muted"><%= @name %></span> · <button class="link" type="submit">Sign out</button>
    </form>
    """,
    [:assigns],
    engine: Phoenix.HTML.Engine
  )

  EEx.function_from_string(
    :defp,
    :signed_out_body,
    """
    <h1>Sign in</h1>
    <p class="muted">The marketplace is open to invited accounts only.</p>
    <p><a class="button" href="/marketplace/auth/login">Sign in with RisiCloud</a></p>
    """,
    [:_assigns],
    engine: Phoenix.HTML.Engine
  )

  EEx.function_from_string(
    :defp,
    :not_open_body,
    """
    <h1>Not open yet</h1>
    <p>Thank you for your interest. The Risi Marketplace is not open to your account yet.</p>
    <p class="muted">We are inviting people a few at a time. You will hear from us when it opens for you.</p>
    """,
    [:_assigns],
    engine: Phoenix.HTML.Engine
  )

  EEx.function_from_string(
    :defp,
    :unavailable_body,
    """
    <h1>Sign-in is not available</h1>
    <p class="muted"><%= @message %></p>
    <p><a href="/marketplace">Back</a></p>
    """,
    [:assigns],
    engine: Phoenix.HTML.Engine
  )

  EEx.function_from_string(
    :defp,
    :agents_body,
    """
    <h1>Agents</h1>
    <p class="muted">Every agent in the registry, read-only. Only active, verified agents can be asked by Risi.</p>
    <div class="wrap"><table>
    <thead><tr><th>Agent</th><th>Tier</th><th>Status</th><th>Card</th><th>What it can read</th></tr></thead>
    <tbody>
    <%= for a <- @agents do %>
    <tr>
    <td><strong><%= a.name %></strong><br><span class="muted"><%= a.agent_id %></span><br><%= a.description %></td>
    <td><span class="chip"><%= a.tier %></span></td>
    <td><span class="chip"><%= a.status %></span></td>
    <td><span class="chip"><%= a.verification %></span></td>
    <td><%= for s <- a.scopes do %><div><%= s["label"] %></div><% end %></td>
    </tr>
    <% end %>
    </tbody>
    </table></div>
    <%= if @agents == [] do %><p class="muted">No agents yet.</p><% end %>
    """,
    [:assigns],
    engine: Phoenix.HTML.Engine
  )

  def signed_out, do: page("Sign in", "", signed_out_body(%{}))
  def not_open, do: page("Not open yet", "", not_open_body(%{}))

  def unavailable(message),
    do: page("Sign-in unavailable", "", unavailable_body(%{message: message}))

  def agents(agents, user, csrf) do
    nav = logout_nav(%{csrf: csrf, name: user["name"] || "Signed in"})
    page("Agents", nav, agents_body(%{agents: agents}))
  end

  defp page(title, nav, inner) do
    %{title: title, nav: nav, inner: inner}
    |> layout()
    |> Phoenix.HTML.safe_to_string()
  end
end
