defmodule RisiMe.Contract.ExamplesV144Test do
  @moduledoc """
  Contract v1.44 §39 (link previews): every new example file parses and has the documented shape.
  The server has no logic for `link_preview`: it lives inside the MLS ciphertext, which the server
  never decrypts. These pins keep the examples honest for both roles.
  """
  use ExUnit.Case, async: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(envelope_text_link_preview.json envelope_text_link_preview_no_thumb.json
           envelope_text_link_preview_bad.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp valid?(%{"body" => body, "link_preview" => %{"url" => url, "title" => title} = p}) do
    thumb_ok =
      case p["thumb"] do
        nil ->
          true

        %{"mime" => m, "w" => w, "h" => h, "data" => d} ->
          m in ~w(image/jpeg image/webp) and w in 1..128 and h in 1..128 and
            byte_size(Base.decode64!(d)) <= 4096
      end

    String.starts_with?(url, "https://") and String.length(url) <= 2048 and
      String.contains?(body, url) and String.trim(title) != "" and String.length(title) <= 200 and
      String.length(p["description"] || "") <= 300 and String.length(p["site_name"] || "") <= 100 and
      thumb_ok
  end

  test "the files exist and are text envelopes" do
    for f <- files(), do: assert(%{"v" => 1, "type" => "text", "body" => _} = example(f))
  end

  test "a full preview: url in the body, title, description, site_name, a §14.4 thumb" do
    e = example("envelope_text_link_preview.json")
    assert valid?(e)

    assert %{"description" => _, "site_name" => _, "thumb" => %{"mime" => "image/jpeg"}} =
             e["link_preview"]

    assert byte_size(Jason.encode!(e)) <= 22_528
  end

  test "no thumb, next to reply_to" do
    e = example("envelope_text_link_preview_no_thumb.json")
    assert valid?(e) and e["link_preview"]["thumb"] == nil
    assert %{"message_id" => _, "from" => _} = e["reply_to"]
  end

  test "the bad one breaks the rules (receivers ignore it; the message stays)" do
    e = example("envelope_text_link_preview_bad.json")
    refute valid?(e)
    assert e["body"] != ""
  end
end
