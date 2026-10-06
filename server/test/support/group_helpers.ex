defmodule RisiMe.GroupHelpers do
  @moduledoc "v1.9 group test helpers: groups-capable devices, REST calls, events."
  import ExUnit.Assertions

  alias RisiMe.{Messaging, Repo}

  @endpoint RisiMeWeb.Endpoint

  @doc "Registers a groups-capable MLS device (and records it in the census). Returns its id."
  def groups_device!(user, device_id \\ Ecto.UUID.generate()) do
    user_id = id_of(user)

    {:ok, att} =
      RisiMe.Devices.register(user_id, device_id, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => ["groups"]
        }
      })

    true = is_binary(att)
    :ok = RisiMe.MLS.record_instance(user_id, device_id, nil, "0.3.0-test")
    device_id
  end

  @doc "A user inserted directly (no OTP round trip), for large groups."
  def fast_user!(name \\ nil) do
    n = System.unique_integer([:positive])

    Repo.insert!(%RisiMe.Accounts.User{
      phone: RisiMe.Fixtures.unique_phone(),
      email: "fast#{n}@example.com",
      display_name: name || "User #{n}",
      company: "CodeGen"
    })
  end

  @doc "Drops the legacy (pre-v1.7) census instances the sockets of `logged_in_user` create."
  def clear_legacy! do
    import Ecto.Query
    Repo.delete_all(from i in "app_instances", where: like(i.instance_key, "legacy:%"))
  end

  @doc "A REST call with the bearer token and an optional X-Device-Id. Returns `{status, json}`."
  def api(method, path, token, body \\ nil, device \\ nil) do
    conn =
      Phoenix.ConnTest.build_conn()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)

    conn = if device, do: Plug.Conn.put_req_header(conn, "x-device-id", device), else: conn
    conn = Phoenix.ConnTest.dispatch(conn, @endpoint, method, path, body)
    {conn.status, if(conn.resp_body == "", do: nil, else: Jason.decode!(conn.resp_body))}
  end

  @doc "A raw-body POST (blobs)."
  def api_raw(path, token, bytes) do
    conn =
      Phoenix.ConnTest.build_conn()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
      |> Plug.Conn.put_req_header("content-type", "application/octet-stream")
      |> Plug.Conn.put_req_header("content-length", Integer.to_string(byte_size(bytes)))
      |> Phoenix.ConnTest.dispatch(@endpoint, :post, path, bytes)

    {conn.status, if(conn.resp_body == "", do: nil, else: Jason.decode!(conn.resp_body))}
  end

  @doc "Stored inbox events of a user, as wire JSON, optionally of one kind."
  def events(user_id, kind \\ nil) do
    {:ok, events, _} = Messaging.fetch_events(user_id, nil)

    events
    |> Enum.filter(&(kind == nil or &1.kind == kind))
    |> Enum.map(&(&1 |> Jason.encode!() |> Jason.decode!()))
  end

  def last_event(user_id, kind), do: user_id |> events(kind) |> List.last()

  @doc """
  The epoch-0 commit body that adds every other groups device of the given users (plus the
  creator's other devices).
  """
  def create_commit(member_ids, {me, dev}, extra \\ %{}) do
    added =
      for %{user_id: u, device_id: d} <- RisiMe.Groups.groups_devices(member_ids),
          {u, d} != {me, dev},
          do: %{"user_id" => u, "device_id" => d}

    Map.merge(
      %{
        "generation" => 1,
        "epoch" => 0,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => added,
        "removed" => [],
        "op_id" => nil,
        "meta_changed" => false
      },
      extra
    )
  end

  def b64(n \\ 32), do: Base.encode64(:crypto.strong_rand_bytes(n))

  def ref(u, d), do: %{"user_id" => u, "device_id" => d}

  def assert_status({status, body}, expected) do
    assert status == expected, "expected #{expected}, got #{status}: #{inspect(body)}"
    body
  end

  defp id_of(%{user: %{id: id}}), do: id
  defp id_of(%{id: id}), do: id
  defp id_of(id) when is_binary(id), do: id
end
