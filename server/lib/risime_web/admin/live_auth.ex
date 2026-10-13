defmodule RisiMeWeb.Admin.LiveAuth do
  @moduledoc """
  `on_mount` of the admin LiveViews (decision 089): the session must belong to an active admin
  at mount, and again before every event (a removed admin's open page stops working at once).
  Assigns `:admin_id` and `:admin_name`.
  """
  import Phoenix.LiveView
  import Phoenix.Component, only: [assign: 3]

  alias RisiMeWeb.Admin.Session

  def on_mount(:default, _params, session, socket) do
    case Session.admin_id(session) do
      nil ->
        {:halt, redirect(socket, to: "/admin/sign-in")}

      id ->
        name =
          case RisiMe.Admin.get_user(id) do
            %{display_name: n} when is_binary(n) -> n
            _ -> "admin"
          end

        socket =
          socket
          |> assign(:admin_id, id)
          |> assign(:admin_name, name)
          |> attach_hook(:admin_recheck, :handle_event, fn _event, _params, socket ->
            if RisiMe.Admin.admin?(socket.assigns.admin_id),
              do: {:cont, socket},
              else: {:halt, redirect(socket, to: "/admin/sign-in")}
          end)

        {:cont, socket}
    end
  end
end
