defmodule RisiMe.Agent.CallRisi.CallSendTurnTest do
  @moduledoc """
  Call Risi brain v3 (Harsha's call 75ec8683: Risi made up "Hi Shenika, how are you"): the call
  hands the §25 turn a message whose recipient and text are the caller's own words
  (`:call_send`). The turn drafts exactly that text, with no model call, through the same
  `send_message` step (card [Send] [Edit] Cancel); "cancel that" cancels that card for real
  (`Writes.cancel_request/2`). A JSON envelope can't carry the hook (an atom key).
  """
  use RisiMe.DataCase, async: false

  import RisiMe.RisiHelpers

  alias RisiMe.Agent.Writes

  @moduletag capture_log: true

  @send_caps ~w(groups member_devices tabs risi_tools risi_skills risi_send)

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    restore_on_exit([:risi_send_for_me])
    Application.put_env(:risime, :risi_send_for_me, :on)
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    nimali = RisiMe.Fixtures.logged_in_user(display_name: "Nimali Perera")
    RisiMe.Fixtures.befriend!(harsha, nimali)
    _og = risi_chat!([harsha.user, nimali.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = RisiMe.TabsHelpers.tabs_device!(harsha.user, caps: @send_caps)
    rc = own_risi_chat!(harsha.user)
    # Any model call fails the turn (none is expected).
    scripted_llm!([])
    %{harsha: harsha, dev: dev, rc: rc}
  end

  defp env(ctx, rid, send) do
    %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => rid,
      "action" => "ask",
      "text" => "Send a message to Nimali: " <> (send["text"] || ""),
      "__sender" => ctx.harsha.user.id,
      "__device" => ctx.dev,
      "__ts" => DateTime.utc_now() |> DateTime.to_iso8601()
    }
    |> Map.put(:call_send, send)
  end

  test "the card carries exactly the caller's text; no model writes it; cancel closes it", ctx do
    rid = Ecto.UUID.generate()
    text = "Good morning, Nimali. How are you doing?"

    assert :ok =
             RisiMe.Agent.Turn.run(
               ctx.rc,
               env(ctx, rid, %{"to" => "Nimali", "text" => text}),
               rid,
               ctx.harsha.user.id
             )

    assert_receive {:risi_post, rc, body, %{"kind" => "confirm"} = card}
    assert rc == ctx.rc
    assert card["tool"] == "send_message" and card["request_id"] == rid
    assert card["buttons"] == ["send", "edit", "cancel"]
    assert card["args"]["text"] == text
    assert body =~ "Send to Nimali Perera (Private): " <> text

    assert %{state: "pending"} = Writes.get(card["write_id"])
    assert Writes.cancel_request(ctx.harsha.user.id, rid) == :ok
    assert %{state: "cancelled", args: nil} = Writes.get(card["write_id"])
    # Only a pending card is cancelled (twice, or another user's: nothing).
    assert Writes.cancel_request(ctx.harsha.user.id, rid) == :none
    assert Writes.cancel_request(Ecto.UUID.generate(), rid) == :none
  end

  test "a JSON envelope's \"call_send\" / \"__send\" is ignored (the model's loop runs)", ctx do
    rid = Ecto.UUID.generate()

    e =
      env(ctx, rid, %{"to" => "Nimali", "text" => "x"})
      |> Map.delete(:call_send)
      |> Map.put("call_send", %{"to" => "Nimali", "text" => "x"})
      |> Map.put("__send", %{"to" => "Nimali", "text" => "x"})

    _ = RisiMe.Agent.Turn.run(ctx.rc, e, rid, ctx.harsha.user.id)
    refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
  end
end
