defmodule RisiMeWeb.Admin.ComingSoonLive do
  @moduledoc "The admin console pages that are not built yet (decision 089): \"coming soon\"."
  use RisiMeWeb, :live_view

  alias RisiMeWeb.Admin.Layouts

  @impl true
  def mount(%{"page" => page}, _session, socket) do
    case Layouts.page_title(page) do
      nil ->
        {:ok, push_navigate(socket, to: "/admin/speech")}

      title ->
        {:ok, assign(socket, page: page, title: title, page_title: title <> " · RisiMe admin")}
    end
  end

  @impl true
  def render(assigns) do
    ~H"""
    <Layouts.shell current={@page} admin_name={@admin_name}>
      <h1>{@title}</h1>
      <p class="muted">Coming soon.</p>
    </Layouts.shell>
    """
  end
end
