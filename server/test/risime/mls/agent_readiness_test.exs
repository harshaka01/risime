defmodule RisiMe.MLS.AgentReadinessTest do
  @moduledoc """
  `images_ready` / `files_ready` / `voice_ready` (§14.1, §33.1, §37.1) in Official chats: the
  Risi agent member has no app instance and is never counted or listed as missing; human
  members still are.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers

  @moduletag capture_log: true

  @media ~w(images files voice)
  @caps ["groups", "member_devices", "tabs", "images", "deletes", "files", "voice"]

  setup :with_attestation_key

  setup do
    %{user_id: risi} = risi_on!(0)
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    a_dev = tabs_device!(a, caps: @caps)
    b_dev = tabs_device!(b, caps: @caps)
    c_dev = tabs_device!(c, caps: @caps)
    clear_legacy!()
    %{risi: risi, a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev}
  end

  defp official!(users, risi, opts \\ []) do
    members =
      users
      |> Enum.with_index()
      |> Enum.map(fn {u, i} -> {u.user.id, if(i == 0, do: "admin", else: "member")} end)

    official_group!("grp:" <> Ecto.UUID.generate(), members, [agents: [risi]] ++ opts)
  end

  defp view(conv, user, dev) do
    {200, view} = api(:get, "/api/v1/mls/groups/#{conv}", user.token, nil, dev)
    view
  end

  defp assert_ready(view) do
    for k <- @media do
      assert view["#{k}_ready"] == true, "#{k}_ready"
      assert view["missing_#{k}"] == [], "missing_#{k}"
    end
  end

  test "an Official group with Risi is ready when every human device is", ctx do
    conv = official!([ctx.a, ctx.b, ctx.c], ctx.risi)
    assert_ready(view(conv, ctx.a, ctx.a_dev))

    # The library call agrees, and Risi is never listed.
    v = RisiMe.MLS.Images.put_readiness(%{e2ee: true}, conv, ctx.a.user.id)
    assert v.voice_ready and v.files_ready and v.images_ready
  end

  test "a 1:1 Official (chat_kind dm) with Risi is ready when both humans are", ctx do
    conv = official!([ctx.a, ctx.b], ctx.risi, chat_kind: "dm")
    assert_ready(view(conv, ctx.b, ctx.b_dev))
  end

  test "still not ready when a human is missing the capability; Risi is not listed", ctx do
    conv = official!([ctx.a, ctx.b, ctx.c], ctx.risi)
    {:ok, _} = RisiMe.Devices.register(ctx.c.user.id, ctx.c_dev, device_params(["groups"]))

    view = view(conv, ctx.a, ctx.a_dev)

    for k <- @media do
      assert view["#{k}_ready"] == false, "#{k}_ready"
      assert view["missing_#{k}"] == [%{"user_id" => ctx.c.user.id, "device_id" => ctx.c_dev}]
    end
  end

  test "a 1:1 Official is not ready when the peer never connected", ctx do
    d = logged_in_user(display_name: "Dilan")
    conv = official!([ctx.a, d], ctx.risi, chat_kind: "dm")
    view = view(conv, ctx.a, ctx.a_dev)

    for k <- @media do
      assert view["#{k}_ready"] == false
      assert view["missing_#{k}"] == [%{"user_id" => d.user.id, "device_id" => nil}]
    end
  end

  defp device_params(caps),
    do: %{
      "platform" => "android",
      "mls" => %{
        "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
        "capabilities" => caps
      }
    }
end
