defmodule RisiMe.Contract.ExamplesV137Test do
  @moduledoc """
  Contract v1.37 §8.1 (decision 079): `device_put_app_id.json` is accepted, its `app_id` is
  stored and picks the `risicloud` Firebase project.
  """
  use RisiMeWeb.ConnCase, async: true

  import RisiMe.Fixtures
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Devices.Device

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files, do: ~w(device_put_app_id.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  test "device_put_app_id.json is accepted and its app_id chooses the risicloud project", %{
    conn: conn
  } do
    %{user: u, token: t} = logged_in_user()
    ex = example("device_put_app_id.json")
    assert ex["app_id"] == "ai.risicloud.risime"

    assert conn
           |> put_req_header("authorization", "Bearer " <> t)
           |> put("/api/v1/me/devices/#{Ecto.UUID.generate()}", ex)
           |> response(204)

    [d] = RisiMe.Repo.all(from d in Device, where: d.user_id == ^u.id)
    assert d.app_id == "ai.risicloud.risime"
    assert d.push_token == ex["push_token"]
    assert RisiMe.Push.FCM.project_for(RisiMe.Devices.push_app_id(d.push_token)) == :risicloud
  end
end
