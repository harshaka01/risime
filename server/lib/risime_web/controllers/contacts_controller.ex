defmodule RisiMeWeb.ContactsController do
  use RisiMeWeb, :controller

  alias RisiMe.Accounts
  alias RisiMeWeb.ApiJSON

  def index(conn, _params) do
    contacts = Accounts.list_contacts(conn.assigns.current_user)
    json(conn, %{contacts: Enum.map(contacts, &ApiJSON.contact/1)})
  end
end
