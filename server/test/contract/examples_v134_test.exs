defmodule RisiMe.Contract.ExamplesV134Test do
  @moduledoc """
  Contract v1.34 §33 (basic messaging): one test for each of the 17 new example files.
  Client-only envelopes (inside MLS) are checked against the §33 rules; server-produced
  examples are compared exactly against what the server produces (the Risi ones in
  test/risime/agent/export_pdf_test.exs). `msg:delete` `blob_ids` already take any `media` blob
  of the conversation, so a `file` blob needs no new rule (test/risime_web/channels/deletes_v112_test.exs).
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, e2ee_group!: 2]

  alias RisiMe.GroupHelpers

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(backup_entry_message_v134.json device_put_files.json
           envelope_risi_answer_next_action_pdf.json envelope_risi_confirm_export_pdf.json
           envelope_text_forwarded.json envelope_text_forwarded_bad.json
           envelope_text_forwarded_many.json envelope_text_reply.json
           envelope_text_view_once_reserved.json event_risi_tool_call_export_pdf.json
           file_payload.json file_payload_bad_name.json file_payload_parts.json
           image_payload_forwarded.json mls_group_files_ready.json
           risi_tool_result_export_pdf.json risi_tool_result_export_pdf_error.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

  # ---- the §10.3 / §33 envelope rules, as the server-side reference for the client rules -----
  defp forwarded_ok?(%{"forwarded" => %{"hops" => h}}) when is_integer(h), do: h in 1..255
  defp forwarded_ok?(_), do: false

  defp name_ok?(n) when is_binary(n) do
    byte_size(n) in 1..255 and String.valid?(n) and :unicode.characters_to_nfc_binary(n) == n and
      not Regex.match?(~r/[\x00-\x1f\x7f-\x9f\/\\\x{202A}-\x{202E}\x{2066}-\x{2069}]/u, n)
  end

  defp name_ok?(_), do: false

  test "forwarded envelopes: hops 1, 7 and 2 are valid; hops 0 is ignored, not dropped" do
    assert forwarded_ok?(example("envelope_text_forwarded.json"))
    assert example("envelope_text_forwarded.json")["forwarded"] == %{"hops" => 1}
    assert example("envelope_text_forwarded_many.json")["forwarded"] == %{"hops" => 7}
    assert forwarded_ok?(example("envelope_text_forwarded_many.json"))
    assert example("image_payload_forwarded.json")["forwarded"] == %{"hops" => 2}
    assert example("image_payload_forwarded.json")["type"] == "image"

    bad = example("envelope_text_forwarded_bad.json")
    refute forwarded_ok?(bad)
    assert bad["type"] == "text" and is_binary(bad["body"])
  end

  test "reply_to carries ids only; view_once is a reserved key" do
    r = example("envelope_text_reply.json")
    assert %{"message_id" => mid, "from" => from} = r["reply_to"]
    assert Map.keys(r["reply_to"]) |> Enum.sort() == ["from", "message_id"]
    assert mid =~ ~r/^[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$/
    assert from =~ @uuid

    assert Map.has_key?(example("envelope_text_view_once_reserved.json"), "view_once")
  end

  test "file envelopes: valid file, bad name dropped, parts placeholder" do
    f = example("file_payload.json")
    assert f["type"] == "file" and name_ok?(f["name"])
    # §14.3 cipher_size of 196 608 plain bytes (3 segments of 64 KiB).
    assert f["blob"]["size"] == 196_656 and f["enc"]["plain_size"] == 196_608
    assert f["enc"]["plain_size"] <= 16_515_072
    assert f["pages"] in 1..10_000
    assert f["mime"] =~ ~r/^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$/

    bad = example("file_payload_bad_name.json")
    assert bad["type"] == "file"
    refute name_ok?(bad["name"])

    p = example("file_payload_parts.json")
    assert p["type"] == "file" and is_list(p["parts"]) and not Map.has_key?(p, "blob")
    assert name_ok?(p["name"])
  end

  test "backup_entry_message_v134.json: a message line with starred_at over a file payload" do
    e = example("backup_entry_message_v134.json")
    assert e["type"] == "message" and e["payload"]["type"] == "file"
    assert e["payload"]["forwarded"] == %{"hops" => 1}
    assert e["starred_at"] =~ ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
  end

  describe "capabilities and readiness" do
    setup :with_attestation_key

    test "device_put_files.json keeps files and pdf_export (with risi_tools); mls_group_files_ready.json" do
      a = logged_in_user(display_name: "A")
      b = logged_in_user(display_name: "B")
      ex = example("device_put_files.json")
      a_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        GroupHelpers.api(:put, "/api/v1/me/devices/#{a_dev}", a.token, ex)

      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")
      dev = RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)
      assert "files" in dev.capabilities and "pdf_export" in dev.capabilities

      # pdf_export without risi_tools is silently dropped.
      b_dev = Ecto.UUID.generate()
      no_tools = put_in(ex, ["mls", "capabilities"], ~w(groups images files pdf_export))

      {200, _} = GroupHelpers.api(:put, "/api/v1/me/devices/#{b_dev}", b.token, no_tools)
      bd = RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: b_dev)
      assert bd.capabilities == ~w(groups images files)
      :ok = RisiMe.MLS.record_instance(b.user.id, b_dev, nil, "0.3.0")

      GroupHelpers.clear_legacy!()
      conv = e2ee_group!(a, b)
      ref = example("mls_group_files_ready.json")
      {200, view} = GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", a.token)
      assert view["files_ready"] == true and view["missing_files"] == []

      # b's device lacks `files`: not ready (the example's shape).
      no_files = put_in(ex, ["mls", "capabilities"], ~w(groups images))
      {200, _} = GroupHelpers.api(:put, "/api/v1/me/devices/#{b_dev}", b.token, no_files)
      {200, view} = GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", a.token)

      assert view["files_ready"] == false
      assert view["missing_files"] == [%{"user_id" => b.user.id, "device_id" => b_dev}]
      assert Map.keys(ref) -- Map.keys(view) == []
      assert ref["files_ready"] == false
    end
  end

  describe "Risi export_pdf examples (behaviour: test/risime/agent/export_pdf_test.exs)" do
    test "the result, tool-call, confirm and answer examples carry the §33.15 fields" do
      assert example("risi_tool_result_export_pdf.json") ==
               %{"status" => "ok", "result" => %{"state" => "sent", "pages" => 3}}

      assert example("risi_tool_result_export_pdf_error.json") ==
               %{"status" => "error", "result" => %{"code" => "files_not_ready"}}

      call = example("event_risi_tool_call_export_pdf.json")
      assert call["kind"] == "risi_tool_call" and call["data"]["tool"] == "export_pdf"

      confirm = example("envelope_risi_confirm_export_pdf.json")["risi"]
      assert confirm["tool"] == "export_pdf" and confirm["buttons"] == ["send", "cancel"]
      assert confirm["when"] == nil and is_map(confirm["export"])

      ans = example("envelope_risi_answer_next_action_pdf.json")["risi"]

      assert [%{"label" => "PDF", "action" => "pdf", "source" => %{"type" => "note"}}] =
               ans["next_actions"]
    end
  end
end
