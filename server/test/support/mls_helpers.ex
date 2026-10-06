defmodule RisiMe.MLSHelpers do
  @moduledoc "E2EE test helpers: a temp attestation key and MLS fixtures (opaque fake blobs)."
  import ExUnit.Callbacks, only: [on_exit: 1]

  alias RisiMe.MLS.Attestation

  @doc "Turns E2EE on for the test with a freshly generated attestation key in a temp file."
  def with_attestation_key(_ctx \\ %{}) do
    path =
      Path.join(System.tmp_dir!(), "risime-test-attest-#{System.unique_integer([:positive])}.jwk")

    {:ok, kid} = Attestation.generate(path)
    Application.put_env(:risime, :attestation_key_file, path)
    true = Attestation.reload()

    on_exit(fn ->
      File.rm(path)
      Application.put_env(:risime, :attestation_key_file, nil)
      Attestation.reload()
    end)

    %{attestation_kid: kid, attestation_path: path}
  end

  @doc "Registers an MLS device (random Ed25519-sized key) and records it in the census."
  def mls_device!(user, device_id \\ Ecto.UUID.generate()) do
    user_id = id_of(user)

    {:ok, att} =
      RisiMe.Devices.register(user_id, device_id, %{
        "platform" => "android",
        "mls" => %{"signature_key" => Base.encode64(:crypto.strong_rand_bytes(32))}
      })

    true = is_binary(att)
    :ok = RisiMe.MLS.record_instance(user_id, device_id, nil, "0.3.0-test")
    device_id
  end

  @doc """
  Makes the DM between two users e2ee at epoch 1 with all their current MLS devices in the
  group (directly in the DB). Returns the conversation id.
  """
  def e2ee_group!(a, b) do
    {a, b} = {id_of(a), id_of(b)}
    conv = RisiMe.Messaging.conversation_id(a, b)
    now = DateTime.utc_now()

    RisiMe.Repo.insert_all("mls_groups", [
      %{conversation_id: conv, generation: 1, epoch: 1, e2ee_since: now, updated_at: now}
    ])

    rows =
      for d <- RisiMe.MLS.current_mls_devices([a, b]),
          do: %{
            conversation_id: conv,
            user_id: Ecto.UUID.dump!(d.user_id),
            device_id: Ecto.UUID.dump!(d.device_id)
          }

    RisiMe.Repo.insert_all("mls_group_devices", rows, on_conflict: :nothing)
    conv
  end

  def b64(n \\ 32), do: Base.encode64(:crypto.strong_rand_bytes(n))

  defp id_of(%{user: %{id: id}}), do: id
  defp id_of(%{id: id}), do: id
  defp id_of(id) when is_binary(id), do: id
end
