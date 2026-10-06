defmodule RisiMeWeb.DeviceTest do
  @moduledoc "Contract v1.5 §8.1: the device registry."
  use RisiMeWeb.ConnCase, async: true

  import RisiMe.Fixtures
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Devices
  alias RisiMe.Devices.Device
  alias RisiMe.Repo

  setup do
    %{user: user, token: token} = logged_in_user()
    %{user: user, token: token}
  end

  defp authed(conn, token), do: put_req_header(conn, "authorization", "Bearer " <> token)

  defp put_device(conn, token, id, body),
    do: conn |> authed(token) |> put("/api/v1/me/devices/#{id}", body)

  defp body(token, extra \\ %{}),
    do:
      Map.merge(
        %{"platform" => "android", "push_token" => token, "app_version" => "0.2.0"},
        extra
      )

  defp devices(user_id), do: Repo.all(from d in Device, where: d.user_id == ^user_id)

  test "PUT registers and is idempotent; a new token replaces the old", %{
    conn: conn,
    user: u,
    token: t
  } do
    id = Ecto.UUID.generate()
    assert put_device(conn, t, id, body("tok-1")) |> response(204)
    assert put_device(conn, t, id, body("tok-2")) |> response(204)
    assert [%Device{push_token: "tok-2", app_version: "0.2.0"}] = devices(u.id)
  end

  test "422 invalid_device for a bad id, platform or token", %{conn: conn, token: t} do
    id = Ecto.UUID.generate()

    for {path_id, payload} <- [
          {"not-a-uuid", body("tok")},
          {id, body("tok", %{"platform" => "ios"})},
          {id, body("  ")},
          {id, Map.delete(body("tok"), "push_token")},
          {id, body("tok", %{"app_version" => String.duplicate("v", 65)})}
        ] do
      assert put_device(conn, t, path_id, payload) |> json_response(422) == %{
               "error" => %{
                 "code" => "invalid_device",
                 "message" => "Device registration is invalid"
               }
             }
    end

    assert conn |> authed(t) |> delete("/api/v1/me/devices/nope") |> json_response(422)
  end

  test "DELETE removes and is idempotent; other users' devices are untouched",
       %{conn: conn, user: u, token: t} do
    %{user: other, token: ot} = logged_in_user()
    id = Ecto.UUID.generate()
    put_device(conn, t, id, body("mine"))
    put_device(conn, ot, id, body("theirs"))

    assert conn |> authed(t) |> delete("/api/v1/me/devices/#{id}") |> response(204)
    assert conn |> authed(t) |> delete("/api/v1/me/devices/#{id}") |> response(204)
    assert devices(u.id) == []
    assert [%Device{push_token: "theirs"}] = devices(other.id)
  end

  test "at most 10 devices per user; the least recently seen is evicted", %{
    conn: conn,
    user: u,
    token: t
  } do
    ids = for _ <- 1..11, do: Ecto.UUID.generate()

    for {id, i} <- Enum.with_index(ids) do
      put_device(conn, t, id, body("tok-#{i}")) |> response(204)
    end

    left = devices(u.id)
    assert length(left) == 10
    refute Enum.any?(left, &(&1.push_token == "tok-0"))
  end

  test "an FCM token moves with the install: registering it elsewhere removes the old row",
       %{conn: conn, user: u, token: t} do
    %{user: other, token: ot} = logged_in_user()
    put_device(conn, t, Ecto.UUID.generate(), body("shared"))
    put_device(conn, ot, Ecto.UUID.generate(), body("shared"))
    assert devices(u.id) == []
    assert [_] = devices(other.id)
  end

  test "dev-token logout removes that token's devices", %{conn: conn, user: u, token: t} do
    put_device(conn, t, Ecto.UUID.generate(), body("tok"))
    assert conn |> authed(t) |> post("/api/v1/auth/logout") |> response(204)
    assert devices(u.id) == []
  end

  test "prune removes devices unseen for 60 days", %{user: u} do
    :ok = Devices.register(u.id, Ecto.UUID.generate(), body("old"))
    :ok = Devices.register(u.id, Ecto.UUID.generate(), body("new"))

    Repo.update_all(from(d in Device, where: d.push_token == "old"),
      set: [last_seen_at: DateTime.add(DateTime.utc_now(), -61, :day)]
    )

    assert Devices.prune() == 1
    assert [%Device{push_token: "new"}] = devices(u.id)
  end

  test "inspect never shows the push token", %{user: u} do
    :ok = Devices.register(u.id, Ecto.UUID.generate(), body("secret-fcm-token"))
    refute inspect(devices(u.id)) =~ "secret-fcm-token"
  end
end
