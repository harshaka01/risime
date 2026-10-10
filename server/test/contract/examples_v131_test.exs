defmodule RisiMe.Contract.ExamplesV131Test do
  @moduledoc """
  Contract v1.31 §31 (Google Calendar link): the 13 new example files in
  `contract/v1/examples/`.

  Stage 1 (this file's first version): every file parses, is a JSON object, and obeys the §31
  privacy rule (no token, email, calendar name/id, event title anywhere). The later chunks of
  v1.31 replace each check with an exact comparison against the server's real output, as in
  `examples_v130_test.exs`, as the code behind each file lands.
  """
  use ExUnit.Case, async: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @files ~w(auth_config_v131.json device_put_google_calendar.json
            envelope_risi_answer_calendar_google_no_answer.json
            envelope_risi_answer_calendar_sources_google.json envelope_risi_google_reconnect.json
            error_not_google_device.json event_google_calendar_link.json
            event_risi_tool_call_calendar_check_google.json risi_google_link_put.json
            risi_google_link_reply.json risi_skills_reply_v131.json
            risi_tool_result_calendar_check_google.json
            risi_tool_result_calendar_check_google_reauth.json)

  @doc false
  def files, do: @files

  @forbidden ~w(token access_token refresh_token email calendar_id)

  defp keys(m) when is_map(m), do: Enum.flat_map(m, fn {k, v} -> [k | keys(v)] end)
  defp keys(l) when is_list(l), do: Enum.flat_map(l, &keys/1)
  defp keys(_), do: []

  for f <- @files do
    test "#{f} parses and carries no Google secrets" do
      ex = Path.join(@dir, unquote(f)) |> File.read!() |> Jason.decode!()
      assert is_map(ex)
      assert keys(ex) -- (keys(ex) -- @forbidden) == []
    end
  end

  test "google_api sources carry only ref and events per calendar (§31.4)" do
    for f <- ~w(risi_tool_result_calendar_check_google.json) do
      ex = Path.join(@dir, f) |> File.read!() |> Jason.decode!()

      for s <- ex["result"]["sources"], c <- s["calendars"] do
        assert Enum.sort(Map.keys(c)) == ["events", "ref"]
        assert c["ref"] =~ ~r/^[a-z0-9]{1,16}$/
      end
    end
  end
end
