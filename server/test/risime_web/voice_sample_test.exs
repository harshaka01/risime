defmodule RisiMeWeb.VoiceSampleTest do
  @moduledoc """
  Harsha's call-voice pick (2026-10-15): the signed sample links (`RisiMe.Agent.TTS.VoicePick`,
  `GET /admin/voice-samples/:token`): only the signed-in admin the token was made for, unexpired,
  known files only, `no-store`, one audit line; a link opened before signing in comes back after it;
  the engine messages; and Call Risi's voice stays Kokoro unless the engine is made live.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import Ecto.Query, only: [from: 2]

  alias RisiMe.{Accounts, Admin, Repo}
  alias RisiMe.Admin.{AuditEntry, Settings}
  alias RisiMe.Agent.TTS.VoicePick
  alias RisiMeWeb.Admin.RequireAdmin

  setup do
    dir = Path.join(System.tmp_dir!(), "voice-pick-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    Application.put_env(:risime, :risi_voice_samples_dir, dir)
    Settings.refresh()

    on_exit(fn ->
      Application.delete_env(:risime, :risi_voice_samples_dir)
      File.rm_rf!(dir)
      Settings.refresh()
    end)

    %{dir: dir}
  end

  defp admin(email) do
    allowlist_entry(%{email: email})
    {:ok, u} = Accounts.map_identity(%{sub: Ecto.UUID.generate(), email: email})

    case Admin.seed(email) do
      {:ok, _} -> :ok
      :noop -> :ok = Admin.add_admin(u.id, hd(Admin.list_admins()).user_id)
    end

    u
  end

  defp admin_conn(conn, id),
    do:
      Plug.Test.init_test_session(conn, %{
        "admin_user_id" => id,
        "admin_at" => System.os_time(:second)
      })

  defp wav, do: "RIFF" <> <<36::little-32>> <> "WAVEfmt " <> :binary.copy(<<0>>, 32)

  test "the token's admin gets the audio, no-store, and one audit line", %{conn: conn, dir: dir} do
    u = admin("pick-a@example.com")
    File.write!(Path.join(dir, "dia2-greeting.wav"), wav())

    "http://localhost:4002/admin/voice-samples/" <> token =
      VoicePick.link("dia2-greeting.wav", u.id)

    conn = conn |> admin_conn(u.id) |> get("/admin/voice-samples/" <> token)
    assert conn.status == 200
    assert conn.resp_body == wav()
    assert get_resp_header(conn, "cache-control") == ["no-store"]
    assert [ct] = get_resp_header(conn, "content-type")
    assert ct =~ "audio/wav"

    assert [%{target: "dia2-greeting.wav"}] =
             Repo.all(from a in AuditEntry, where: a.action == "voice_sample_played")
  end

  test "another admin, a forged or unknown file, and a missing file are refused", %{
    conn: conn,
    dir: dir
  } do
    a = admin("pick-b@example.com")
    b = admin("pick-c@example.com")
    File.write!(Path.join(dir, "kokoro-check.wav"), wav())
    token = VoicePick.sign("kokoro-check.wav", a.id)

    assert build_conn()
           |> admin_conn(b.id)
           |> get("/admin/voice-samples/" <> token)
           |> Map.get(:status) ==
             403

    assert conn |> admin_conn(a.id) |> get("/admin/voice-samples/x" <> token) |> Map.get(:status) ==
             403

    bad = VoicePick.sign("../../etc/passwd", a.id)

    assert build_conn()
           |> admin_conn(a.id)
           |> get("/admin/voice-samples/" <> bad)
           |> Map.get(:status) ==
             403

    missing = VoicePick.sign("orpheus-check.wav", a.id)

    assert build_conn()
           |> admin_conn(a.id)
           |> get("/admin/voice-samples/" <> missing)
           |> Map.get(:status) == 404

    assert Repo.all(from x in AuditEntry, where: x.action == "voice_sample_played") == []
  end

  test "expired tokens are refused" do
    u = Ecto.UUID.generate()

    token =
      Phoenix.Token.sign(RisiMeWeb.Endpoint, "voice-sample", %{"f" => "dia2-check.wav", "u" => u},
        signed_at: System.system_time(:second) - 25 * 3600
      )

    assert VoicePick.verify(token, u) == {:error, :expired}
  end

  test "signed out: the sign-in comes back to the sample link only", %{conn: conn} do
    conn = get(conn, "/admin/voice-samples/abc.def-ghi")
    assert redirected_to(conn) == "/admin/sign-in"
    assert get_session(conn, "admin_return_to") == "/admin/voice-samples/abc.def-ghi"
    assert RequireAdmin.return_to?("/admin/voice-samples/abc")
    refute RequireAdmin.return_to?("/admin/voices")
    refute RequireAdmin.return_to?("//evil.example/admin/voice-samples/a")
    refute RequireAdmin.return_to?("/admin/voice-samples/a/../../x")

    conn = get(build_conn(), "/admin/voices")
    assert get_session(conn, "admin_return_to") == nil
  end

  test "engine messages: three links and stats when the samples are there, else waiting", %{
    dir: dir
  } do
    for k <- ~w(greeting calendar check),
        do: File.write!(Path.join(dir, "kokoro-#{k}.wav"), wav())

    u = Ecto.UUID.generate()
    stats = %{"kokoro" => %{"p50_ms" => 112, "p95_ms" => 129, "rtf" => 0.04}}

    {body, sources} = VoicePick.message({"1", "Kokoro", "kokoro"}, u, stats)
    assert body =~ "1 Kokoro (first audio 112 ms typical, 129 ms slowest"
    assert length(sources) == 3
    assert Enum.all?(sources, &(&1["type"] == "link" and String.starts_with?(&1["url"], "http")))

    for %{"url" => url} <- sources do
      [_, token] = String.split(url, "/admin/voice-samples/")
      assert {:ok, "kokoro-" <> _} = VoicePick.verify(token, u)
    end

    assert {"4 Orpheus: waiting for access", []} =
             VoicePick.message({"4", "Orpheus", "orpheus"}, u, stats)
  end

  test "Call Risi keeps Kokoro unless the picked voice's engine is live" do
    by = Ecto.UUID.generate()
    :ok = Settings.put("tts_voice_en", "chatterbox-turbo:default", by)
    Settings.refresh()
    assert RisiMe.Agent.CallRisi.voice("en") == "kokoro:af_heart"

    cfg = Application.get_env(:risime, :risi_call_risi_config, [])

    Application.put_env(
      :risime,
      :risi_call_risi_config,
      Keyword.put(cfg, :live_engines, ["chatterbox-turbo"])
    )

    on_exit(fn -> Application.put_env(:risime, :risi_call_risi_config, cfg) end)
    assert RisiMe.Agent.CallRisi.voice("en") == "chatterbox-turbo:default"
  end
end
