defmodule RisiMeWeb.ContactsController do
  use RisiMeWeb, :controller

  alias RisiMeWeb.ApiJSON

  def index(conn, _params) do
    # v1.6 §9.2: friends only.
    contacts = RisiMe.Social.contacts(conn.assigns.current_user)
    json(conn, %{contacts: Enum.map(contacts, &ApiJSON.contact/1)})
  end
end
