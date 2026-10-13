defmodule RisiMeWeb.ApiJSON do
  @moduledoc "Contract shapes for REST payloads (PROTOCOL.md §1)."

  @doc """
  `User`. `phone_verified` (v1.4) is the session's view: pass it explicitly; for a plain map
  (examples) it is taken from the map.
  """
  def user(user, phone_verified \\ nil) do
    json = %{
      id: user.id,
      phone: user.phone,
      display_name: user.display_name,
      company: user.company,
      phone_verified:
        if(is_nil(phone_verified), do: Map.get(user, :phone_verified, true), else: phone_verified),
      vouched_by: vouched_by(user),
      phone_confirmed: phone_confirmed(user)
    }

    # v1.39 §36.1: `risi_dev: true` only for the users in RISI_DEV_USERS (absent otherwise).
    json =
      if RisiMe.Agent.CallTranscribe.dev?(user.id), do: Map.put(json, :risi_dev, true), else: json

    # Decision 090 (proposal 2026-10-14-call-risi §41.1): `call_risi: true` while RISI_CALL_RISI
    # applies to the user (absent otherwise, so off is byte-identical).
    if RisiMe.Agent.CallRisi.enabled_for?(user.id),
      do: Map.put(json, :call_risi, true),
      else: json
  end

  # v1.20 §21.4: false while an open sign-up's phone is self-asserted.
  defp phone_confirmed(%RisiMe.Accounts.User{} = user),
    do: RisiMe.Accounts.phone_confirmed?(user)

  defp phone_confirmed(map), do: Map.get(map, :phone_confirmed, true)

  # v1.6 §9.1: the inviter while an invited user's phone isn't SMS-verified.
  defp vouched_by(%RisiMe.Accounts.User{} = user), do: RisiMe.Social.vouched_by(user)
  defp vouched_by(map), do: Map.get(map, :vouched_by)

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
