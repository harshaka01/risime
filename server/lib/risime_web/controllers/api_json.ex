defmodule RisiMeWeb.ApiJSON do
  @moduledoc "Contract shapes for REST payloads (PROTOCOL.md §1)."

  @doc """
  `User`. `phone_verified` (v1.4) is the session's view: pass it explicitly; for a plain map
  (examples) it is taken from the map.
  """
  def user(user, phone_verified \\ nil) do
    %{
      id: user.id,
      phone: user.phone,
      display_name: user.display_name,
      company: user.company,
      phone_verified:
        if(is_nil(phone_verified), do: Map.get(user, :phone_verified, true), else: phone_verified)
    }
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
