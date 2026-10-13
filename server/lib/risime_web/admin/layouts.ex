defmodule RisiMeWeb.Admin.Layouts do
  @moduledoc """
  The admin console's layouts (decision 089): the root HTML document and the shell (header and
  the page nav). Pages that are not built yet are listed and open a "coming soon" page.
  """
  use RisiMeWeb, :html

  @pages [
    {"speech", "Speech models", true},
    {"intelligence", "Intelligence", false},
    {"agents", "Agents", false},
    {"servers", "Servers", false},
    {"users", "Users and admins", false}
  ]

  @doc "The nav pages: `{slug, title, built?}`."
  def pages, do: @pages

  @doc "The title of a page slug, or nil."
  def page_title(slug), do: Enum.find_value(@pages, fn {s, t, _} -> s == slug && t end)

  def root(assigns) do
    ~H"""
    <!DOCTYPE html>
    <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="csrf-token" content={get_csrf_token()} />
        <meta name="robots" content="noindex, nofollow" />
        <title>{assigns[:page_title] || "RisiMe admin"}</title>
        <style>
          <%= Phoenix.HTML.raw(css()) %>
        </style>
        <script defer src="/admin/assets/app.js">
        </script>
      </head>
      <body>
        {@inner_content}
      </body>
    </html>
    """
  end

  attr :current, :string, required: true
  attr :admin_name, :string, required: true
  slot :inner_block, required: true

  def shell(assigns) do
    assigns = assign(assigns, :pages, @pages)

    ~H"""
    <header class="top">
      <span class="brand">RisiMe admin</span>
      <span class="who">
        {@admin_name}
        <form action="/admin/sign-out" method="post" class="inline">
          <input type="hidden" name="_csrf_token" value={get_csrf_token()} />
          <button type="submit" class="link">Sign out</button>
        </form>
      </span>
    </header>
    <div class="wrap">
      <nav class="side">
        <a
          :for={{slug, title, built} <- @pages}
          href={"/admin/" <> slug}
          class={["nav", slug == @current && "on", !built && "soon"]}
        >
          {title}<span :if={!built} class="tag">soon</span>
        </a>
      </nav>
      <main class="main">
        {render_slot(@inner_block)}
      </main>
    </div>
    """
  end

  @doc false
  def css do
    """
    :root{--bg:#f7f7f8;--fg:#1b1b1f;--muted:#5d5d66;--line:#dcdce1;--card:#fff;--accent:#2457c5;--warn:#9a5b00;--bad:#b3261e;--ok:#1e7a3c}
    @media (prefers-color-scheme: dark){:root{--bg:#141417;--fg:#e9e9ee;--muted:#a0a0aa;--line:#33333a;--card:#1d1d22;--accent:#8fb1ff;--warn:#e2b25c;--bad:#ff8a80;--ok:#7bd88f}}
    *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
    .top{display:flex;justify-content:space-between;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line);background:var(--card)}
    .brand{font-weight:600}.who{color:var(--muted);display:flex;gap:12px;align-items:center}
    .inline{display:inline}.link{background:none;border:0;color:var(--accent);cursor:pointer;font:inherit;padding:0}
    .wrap{display:flex;flex-wrap:wrap;min-height:calc(100vh - 46px)}
    .side{flex:0 0 200px;padding:12px;border-right:1px solid var(--line)}
    .nav{display:block;padding:7px 10px;border-radius:6px;color:var(--fg);text-decoration:none}
    .nav.on{background:var(--accent);color:var(--card)}.nav.soon{color:var(--muted)}
    .tag{font-size:11px;margin-left:6px;border:1px solid var(--line);border-radius:8px;padding:0 5px}
    .main{flex:1 1 400px;padding:16px;max-width:1100px;min-width:0}
    h1{font-size:20px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 8px}
    .muted{color:var(--muted)}.warn{color:var(--warn)}.bad{color:var(--bad)}.ok{color:var(--ok)}
    .card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px;margin:10px 0;overflow-x:auto}
    table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
    th{font-weight:600;color:var(--muted);font-size:13px}
    select,button.btn{font:inherit;padding:5px 8px;border-radius:6px;border:1px solid var(--line);background:var(--card);color:var(--fg)}
    button.btn{cursor:pointer}button.btn.primary{background:var(--accent);color:var(--card);border-color:var(--accent)}
    button.btn:disabled{opacity:.5;cursor:default}
    .row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
    .text{white-space:pre-wrap;word-break:break-word}
    .flash{padding:8px 12px;border-radius:6px;border:1px solid var(--line);margin:8px 0;background:var(--card)}
    .center{max-width:420px;margin:12vh auto;padding:0 16px;text-align:center}
    a.btn{display:inline-block;padding:8px 14px;border-radius:6px;background:var(--accent);color:var(--card);text-decoration:none}
    @media (max-width:640px){.side{flex:1 1 100%;border-right:0;border-bottom:1px solid var(--line);display:flex;flex-wrap:wrap;gap:4px}.nav{display:inline-block}}
    """
  end
end
