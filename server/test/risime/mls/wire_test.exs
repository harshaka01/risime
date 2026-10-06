defmodule RisiMe.MLS.WireTest do
  @moduledoc "v1.12 §15.3: the PrivateMessage header parser and the delete AAD binding."
  use ExUnit.Case, async: true

  alias RisiMe.MLS.Wire

  # Real OpenMLS 0.9 output (MlsGroup::set_aad + create_message, MLS_128_DHKEMX25519_AES128GCM_
  # SHA256_Ed25519, epoch 0): with the canonical AAD of delete_payload.json's targets, and with
  # an empty AAD.
  @with_aad "000100021073f7cf86154ddc9e40ad757bde865169000000000000000001220144c1a2b3e1a0b111f080000242ac120002c1a2b3f0a0b111f080000242ac1200021c75228ee7eafbfee134778e03764861b8be92dcbd49400a8fe4d8e08a406a5a04b957f3dd04c5f11d5dc8fe7b3df68e239e4d551a091d86acfb81fd19551737262d3bf571d0947b704b5257e7f59752e11f906ad105a0658f8da27bfcb21de2e53d31f64093a754462b56b5b944fc56004d3a3a15474e0e7351ed88172654bc7fc33477391703380d"
  @without_aad "000100021063bc2d7275a22f31bd6ffe084b8a9919000000000000000001001cd390b59df47ccb5eae9c173b45d44c7f370b10abf3ee5945d3387bbd406abe0f3d227c7934554050ff5d414683fc06d1c66140f5e7fe921c5e89d23f61c6d7872121d9776605a4d4ddd0e4e5c6ccafbd818b3259b8d9067af4d75954dec150a579e42b6a78b4019abb489a00a51832d72b2632995df988d1b9130b4cb5e0069bb978bd21779a87d8"
  @targets ["c1a2b3e1-a0b1-11f0-8000-0242ac120002", "c1a2b3f0-a0b1-11f0-8000-0242ac120002"]

  defp b64(hex), do: hex |> Base.decode16!(case: :lower) |> Base.encode64()

  test "parses OpenMLS application messages and checks the binding as a set" do
    bin = Base.decode16!(@with_aad, case: :lower)

    assert {:ok, %{epoch: 0, content_type: 1, authenticated_data: aad}} =
             Wire.private_message(bin)

    assert aad == Wire.delete_aad(@targets)
    assert Wire.delete_bound?(b64(@with_aad), Enum.reverse(@targets))
    refute Wire.delete_bound?(b64(@with_aad), tl(@targets))
    refute Wire.delete_bound?(b64(@without_aad), @targets)
    assert Wire.nonempty_aad?(b64(@with_aad))
    refute Wire.nonempty_aad?(b64(@without_aad))
  end

  test "truncated, trailing or non-private messages don't parse" do
    bin = Base.decode16!(@with_aad, case: :lower)
    assert Wire.private_message(binary_part(bin, 0, byte_size(bin) - 1)) == :error
    assert Wire.private_message(bin <> <<0>>) == :error
    <<v::16, _wf::16, rest::binary>> = bin
    assert Wire.private_message(<<v::16, 1::16, rest::binary>>) == :error
    refute Wire.delete_bound?("not base64", @targets)
    refute Wire.nonempty_aad?(Base.encode64(:crypto.strong_rand_bytes(32)))
  end

  test "the canonical encoding: 0x01 'D', distinct 16-byte UUIDs sorted by bytes" do
    [x, y] = @targets
    assert Wire.delete_aad([y, x, y]) == <<1, ?D>> <> Ecto.UUID.dump!(x) <> Ecto.UUID.dump!(y)
    assert byte_size(Wire.delete_aad(for _ <- 1..100, do: RisiMe.TimeUUID.generate())) == 1602
  end
end
