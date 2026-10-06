defmodule RisiMeWeb.BlobsHttpTest do
  @moduledoc """
  Contract v1.11 §14.2 over real HTTP (Bandit on 127.0.0.1, an ephemeral port): streamed
  uploads, `Connection: close` on pre-body refusals, a client gone mid-upload, ranges via
  `sendfile` and `HEAD`. The controller tests use the Plug test adapter; this checks the server.
  """
  use RisiMeWeb.ChannelCase, async: false

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, e2ee_group!: 2]

  alias RisiMe.Blobs.Slots
  alias RisiMe.Repo

  setup :with_attestation_key

  setup do
    dir = Path.join(System.tmp_dir!(), "risime-blobs-http-#{System.unique_integer([:positive])}")
    prev = Application.get_env(:risime, :blob_dir)
    Application.put_env(:risime, :blob_dir, dir)

    on_exit(fn ->
      Application.put_env(:risime, :blob_dir, prev)
      File.rm_rf(dir)
    end)

    {:ok, server} =
      Bandit.start_link(plug: RisiMeWeb.Endpoint, ip: {127, 0, 0, 1}, port: 0, startup_log: false)

    {:ok, {_ip, port}} = ThousandIsland.listener_info(server)

    a = logged_in_user()
    b = logged_in_user()
    befriend!(a, b)
    for u <- [a, b], do: RisiMe.MLSHelpers.mls_device!(u)
    conv = e2ee_group!(a, b)
    %{port: port, a: a, b: b, conv: conv, dir: dir}
  end

  defp url(ctx, path), do: "http://127.0.0.1:#{ctx.port}/api/v1" <> path

  defp params(conv),
    do:
      URI.encode_query(%{
        "purpose" => "media",
        "conversation_id" => conv,
        "client_blob_id" => Ecto.UUID.generate()
      })

  # A raw HTTP/1.1 request: headers plus `body` (which may be shorter than Content-Length).
  defp raw(ctx, token, path, declared, body, close? \\ false) do
    {:ok, sock} = :gen_tcp.connect(~c"127.0.0.1", ctx.port, [:binary, active: false])

    :ok =
      :gen_tcp.send(sock, [
        "POST /api/v1#{path} HTTP/1.1\r\nhost: localhost\r\n",
        "authorization: Bearer #{token}\r\ncontent-type: application/octet-stream\r\n",
        "content-length: #{declared}\r\n\r\n",
        body
      ])

    if close? do
      :gen_tcp.close(sock)
      nil
    else
      reply = read_all(sock, "")
      :gen_tcp.close(sock)
      reply
    end
  end

  # Reads until the server closes the connection (or 5 s pass).
  defp read_all(sock, acc) do
    case :gen_tcp.recv(sock, 0, 5000) do
      {:ok, data} -> read_all(sock, acc <> data)
      {:error, :closed} -> {:closed, acc}
      {:error, :timeout} -> {:open, acc}
    end
  end

  test "streamed upload, ranges, HEAD, no content-encoding", ctx do
    bytes = :crypto.strong_rand_bytes(3 * 1024 * 1024 + 5)

    resp =
      Req.post!(url(ctx, "/blobs?" <> params(ctx.conv)),
        body: bytes,
        headers: [
          {"authorization", "Bearer " <> ctx.a.token},
          {"content-type", "application/octet-stream"}
        ],
        retry: false
      )

    assert resp.status == 201
    assert resp.body["sha256"] == Base.encode64(:crypto.hash(:sha256, bytes))
    id = resp.body["blob_id"]

    get = fn headers ->
      Req.get!(url(ctx, "/blobs/#{id}"),
        headers: [{"authorization", "Bearer " <> ctx.b.token} | headers],
        retry: false,
        decode_body: false,
        compressed: false
      )
    end

    resp = get.([{"range", "bytes=-100"}, {"accept-encoding", "gzip, zstd"}])
    assert resp.status == 206
    assert resp.body == binary_part(bytes, byte_size(bytes) - 100, 100)
    assert Req.Response.get_header(resp, "content-length") == ["100"]
    assert Req.Response.get_header(resp, "content-encoding") == []

    resp = get.([{"range", "bytes=65552-"}])

    assert resp.status == 206 and
             resp.body == binary_part(bytes, 65_552, byte_size(bytes) - 65_552)

    resp = get.([])
    assert resp.status == 200 and resp.body == bytes

    resp =
      Req.head!(url(ctx, "/blobs/#{id}"),
        headers: [{"authorization", "Bearer " <> ctx.b.token}],
        retry: false
      )

    assert resp.status == 200 and resp.body == ""
    assert Req.Response.get_header(resp, "content-length") == [to_string(byte_size(bytes))]
  end

  test "a refusal before the body is answered at once and the connection closed", ctx do
    # 16 MiB declared, only a few bytes sent: the server must not wait to drain the body.
    {state, reply} =
      raw(ctx, ctx.a.token, "/blobs?" <> params(ctx.conv), 16 * 1024 * 1024 + 1, "abc")

    assert reply =~ "HTTP/1.1 413"
    assert reply =~ "too_large"
    assert state == :closed
  end

  test "a client gone mid-upload stores nothing and frees its slot", ctx do
    nil =
      raw(
        ctx,
        ctx.a.token,
        "/blobs?" <> params(ctx.conv),
        1_000_000,
        :binary.copy("x", 10_000),
        true
      )

    wait = fn wait, n ->
      if Slots.count(:up, ctx.a.user.id) == 0 or n == 0,
        do: :ok,
        else:
          (
            Process.sleep(50)
            wait.(wait, n - 1)
          )
    end

    wait.(wait, 100)
    assert Slots.count(:up, ctx.a.user.id) == 0
    assert Repo.all(from x in "blobs", select: x.id) == []
    assert File.ls(Path.join(ctx.dir, ".tmp")) in [{:ok, []}, {:error, :enoent}]
  end
end
