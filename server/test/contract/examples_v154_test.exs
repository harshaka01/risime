defmodule RisiMe.Contract.ExamplesV154Test do
  @moduledoc """
  Contract v1.54 (architect 2026-10-16 14:05 item 3; proposal 2026-10-16-translate-languages): §37.13.2
  Translate to more languages. `translate_languages` on `/me/risi/voice` only while Translate is
  available; `target` and `always_translate` take any listed code, another one is refused.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures

  alias RisiMe.Agent.VoiceTranslate

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(me_risi_voice_reply_v154.json)

  @doc "The v1.54 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  defp example(f), do: @dir |> Path.join(f) |> File.read!() |> Jason.decode!()

  setup do
    prev = Application.get_env(:risime, :risi_agent_flags)

    on_exit(fn ->
      if prev,
        do: Application.put_env(:risime, :risi_agent_flags, prev),
        else: Application.delete_env(:risime, :risi_agent_flags)
    end)

    :ok
  end

  test "translate_languages: listed only while available, en/si/ta first, every code has a name" do
    a = logged_in_user()
    conn = fn -> build_conn() |> put_req_header("authorization", "Bearer " <> a.token) end

    Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "on"})
    got = conn.() |> get("/api/v1/me/risi/voice") |> json_response(200)
    ex = example("me_risi_voice_reply_v154.json")
    assert Map.keys(got) |> Enum.sort() == Map.keys(ex) |> Enum.sort()
    assert ["en", "si", "ta" | more] = got["translate_languages"]
    assert Enum.all?(ex["translate_languages"], &(&1 in got["translate_languages"]))
    assert more != [] and Enum.all?(more, &(VoiceTranslate.target_name(&1) != "English"))

    # always_translate takes a listed code; an unlisted one is refused.
    assert conn.()
           |> put("/api/v1/me/risi/voice", %{
             "transcribe" => true,
             "action_items" => true,
             "always_translate" => "fr"
           })
           |> json_response(200)
           |> Map.get("always_translate") == "fr"

    assert VoiceTranslate.put_always_translate(a.user.id, "xx") == {:error, :bad_request}

    Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "off"})

    refute conn.()
           |> get("/api/v1/me/risi/voice")
           |> json_response(200)
           |> Map.has_key?("translate_languages")
  end
end
