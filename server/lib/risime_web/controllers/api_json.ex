defmodule RisiMeWeb.ApiJSON do
  @moduledoc "Contract shapes for REST payloads (PROTOCOL.md §1)."

  def user(user) do
    %{id: user.id, phone: user.phone, display_name: user.display_name, company: user.company}
  end

  def contact(c) do
    %{
      phone: c.phone,
      display_name: c.display_name,
      company: c.company,
      user_id: c.user_id,
      registered: c.registered
    }
  end
end
