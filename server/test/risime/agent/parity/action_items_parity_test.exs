defmodule RisiMe.Agent.Parity.ActionItemsTest do
  @moduledoc """
  Deep agents step 0: parity pins for §38 action items (`extract_actions`) as they are today
  (fake `risi-l1`, Risi's clock fixed): a voice note → one `action_items` model call → the
  Official `action_items` card, the owner's `action_confirm` card, the ledger row and the extra;
  a call's transcript rows → the call card; the unclear note → the unclear card with no model
  call; a clear note with no items → nothing.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers, only: [ledger_on!: 0, clock!: 1, ledger_devices!: 1]

  alias RisiMe.Agent.{ActionItems, Commitment, Secretary, Transcript}
  alias RisiMe.Agent.CallTranscribe.Session
  alias RisiMe.TimeUUID

  @moduletag :risi_parity
  @moduletag capture_log: true

  @bound_ms 3_000

  @examples Path.expand("../../../../../contract/v1/examples", __DIR__)
  defp example(name), do: @examples |> Path.join(name) |> File.read!() |> Jason.decode!()
  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  setup do
    ledger_on!()
    restore_on_exit([:risi_action_items])
    Application.put_env(:risime, :risi_action_items, true)
    now = DateTime.utc_now() |> DateTime.truncate(:millisecond)
    clock!(%{now | microsecond: {elem(now.microsecond, 0), 6}})
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    ledger_devices!([harsha])
    og = risi_chat!([harsha, kamal])
    rc = own_risi_chat!(harsha)
    %{harsha: harsha, kamal: kamal, og: og, rc: rc}
  end

  defp llm!(fun, summary \\ "Quotation and site visit.") do
    fake_llm!(fn
      "action_items", body -> %{"summary" => summary, "items" => fun.(body)}
      _, _ -> %{"commitments" => []}
    end)
  end

  defp it(body, kind, text, owner, line, opts \\ []) do
    %{
      "kind" => kind,
      "text" => text,
      "owner" => owner && (ref_of(body, owner) || owner),
      "counterparts" => Enum.map(Keyword.get(opts, :to, []), &ref_of(body, &1)),
      "due_local" => Keyword.get(opts, :due),
      "due_text" => Keyword.get(opts, :due_text),
      "source" => [ref_of(body, line)],
      "lang" => Keyword.get(opts, :lang, "en"),
      "confidence" => 0.9
    }
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      80 -> Enum.reverse(acc)
    end
  end

  defp post_in(posts, conv, kind),
    do: Enum.find(posts, fn {c, _, r} -> c == conv and r["kind"] == kind end)

  defp schema_names,
    do: Enum.map(llm_requests(), &get_in(&1, ~w(response_format json_schema name)))

  defp timed(fun) do
    t = System.monotonic_time(:millisecond)
    r = fun.()
    assert System.monotonic_time(:millisecond) - t < @bound_ms
    r
  end

  defp voice!(w, user, parts, opts \\ []) do
    id = TimeUUID.at(DateTime.add(RisiMe.Agent.Clock.now(), -60, :second))

    env = %{
      "v" => 1,
      "type" => "voice_transcript",
      "status" => Keyword.get(opts, :status, "ok"),
      "text" => Enum.map_join(parts, " ", & &1["t"]),
      "language" => Keyword.get(opts, :language, "en"),
      "reason" => Keyword.get(opts, :reason),
      "p" => 0.95,
      "romanised" => false,
      "model" => "faster-whisper-large-v3",
      "parts" => parts,
      "duration_ms" => Keyword.get(opts, :duration_ms, 12_400)
    }

    :ok =
      Transcript.put(w.og, %{
        message_id: id,
        sender_id: user.id,
        sender_device: nil,
        plaintext: Jason.encode!(env)
      })

    id
  end

  test "a voice note: one action_items call, the Official card, the owner's confirm card, the rows",
       w do
    llm!(fn body ->
      [
        it(body, "promise", "Send the quotation", "Harsha", "I'll send the quotation",
          to: ["Kamal"]
        ),
        it(body, "number", "Budget: LKR 450,000", nil, "the budget is 450,000")
      ]
    end)

    parts = [
      %{"o" => 0, "t" => "the budget is 450,000"},
      %{"o" => 6100, "t" => "I'll send the quotation"}
    ]

    id = voice!(w, w.harsha, parts)
    assert :ok = timed(fn -> ActionItems.from_voice(w.og, id) end)
    assert schema_names() == ["action_items"]

    out = posts()

    assert Enum.map(out, fn {c, _, r} -> {c, r["kind"]} end) |> Enum.sort() ==
             Enum.sort([{w.og, "action_items"}, {w.rc, "action_confirm"}])

    {_, body, card} = post_in(out, w.og, "action_items")
    ex = example("envelope_risi_action_items_voice_note.json")
    assert keys(card) == keys(ex["risi"])
    assert body == "Risi found 2 items in Harsha's voice note: 1 promise, 1 number."

    assert %{"source" => "voice_note", "status" => "ok", "summary" => nil, "notify" => []} =
             card

    assert card["source_ref"] == %{
             "type" => "voice_note",
             "call_id" => nil,
             "session_id" => nil,
             "message_id" => id,
             "turn" => nil,
             "offset_ms" => nil,
             "at" => RisiMe.Agent.Clock.ts(TimeUUID.to_datetime(id))
           }

    [promise, number] = card["items"]
    for i <- card["items"], do: assert(keys(i) == keys(hd(ex["risi"]["items"])))
    assert %{"kind" => "promise", "state" => "proposed", "text" => "Send the quotation"} = promise
    assert promise["owner"] == w.harsha.id and promise["counterpart"] == [w.kamal.id]
    assert promise["source_ref"]["offset_ms"] == 6100
    assert %{"kind" => "number", "owner" => nil, "state" => nil} = number

    {_, cbody, confirm} = post_in(out, w.rc, "action_confirm")
    assert keys(confirm) == keys(example("envelope_risi_action_confirm_v143.json")["risi"])
    assert confirm["for"] == [w.harsha.id] and confirm["notify"] == [w.harsha.id]

    assert [
             %{
               "item_id" => pid,
               "buttons" => ["add_promise", "add_calendar", "remind", "not_mine", "edit"]
             }
           ] =
             confirm["items"]

    assert pid == promise["item_id"]
    assert cbody =~ "From your voice note" and cbody =~ "• Send the quotation"

    c = Repo.get!(Commitment, pid)
    assert c.kind == "promise" and c.source == "voice_note" and c.item_state == "proposed"
    assert c.owner_id == w.harsha.id and c.counterpart_ids == [w.kamal.id]

    assert [%{kind: "number", text: "Budget: LKR 450,000"}] =
             ActionItems.extras(card["summary_id"])

    assert Secretary.envelope(w.og, id) == nil
  end

  test "a call: one action_items call, the call card with turns and offsets", w do
    started = DateTime.add(RisiMe.Agent.Clock.now(), -600, :second)

    s = %Session{
      session_id: Ecto.UUID.generate(),
      call_id: Ecto.UUID.generate(),
      conversation_id: w.og,
      chat_id: Secretary.chat_id(w.og),
      state: "ended",
      discard: false,
      started_at: started,
      ended_at: DateTime.add(started, 300, :second)
    }

    llm!(fn body ->
      [
        it(body, "meeting", "Site visit at level 3", "Harsha", "site visit Wednesday 10",
          due: "2099-10-14T10:00",
          due_text: "Wednesday 10 am"
        ),
        it(body, "decision", "Use the local supplier", nil, "we use the local supplier")
      ]
    end)

    parts = [
      %{"o" => 0, "t" => "site visit Wednesday 10"},
      %{"o" => 4200, "t" => "we use the local supplier"}
    ]

    rows = [
      %{
        message_id: TimeUUID.generate(),
        speaker: w.harsha.id,
        text: Enum.map_join(parts, " ", & &1["t"]),
        language: "en",
        model: "faster-whisper-large-v3",
        ms: 20_000,
        start: DateTime.add(started, 30, :second),
        turn: 1,
        parts: parts,
        p: 0.95,
        romanised: false
      },
      %{
        message_id: TimeUUID.generate(),
        speaker: w.kamal.id,
        text: "ok, Wednesday works",
        language: "en",
        model: "faster-whisper-large-v3",
        ms: 8_000,
        start: DateTime.add(started, 60, :second),
        turn: 2,
        parts: [%{"o" => 0, "t" => "ok, Wednesday works"}],
        p: 0.95,
        romanised: false
      }
    ]

    assert :ok = timed(fn -> ActionItems.from_call(w.og, s, rows) end)
    assert schema_names() == ["action_items"]

    out = posts()
    {_, body, card} = post_in(out, w.og, "action_items")
    assert keys(card) == keys(example("envelope_risi_action_items.json")["risi"])
    assert body =~ "Risi found 2 items in this call: 1 meeting, 1 decision."
    assert card["summary"] == "Quotation and site visit." and card["source"] == "call"
    assert card["source_ref"]["session_id"] == s.session_id and card["source_ref"]["turn"] == nil

    meeting = Enum.find(card["items"], &(&1["kind"] == "meeting"))
    decision = Enum.find(card["items"], &(&1["kind"] == "decision"))
    assert meeting["owner"] == w.harsha.id and meeting["state"] == "proposed"
    assert %{"turn" => 1, "offset_ms" => 0, "call_id" => cid} = meeting["source_ref"]
    assert cid == s.call_id
    assert %{"turn" => 1, "offset_ms" => 4200} = decision["source_ref"]
    assert decision["owner"] == nil and decision["state"] == nil

    {_, cbody, confirm} = post_in(out, w.rc, "action_confirm")
    assert cbody =~ ~r/From your call with Kamal \(\d\d:\d\d\)/
    assert [%{"buttons" => buttons}] = confirm["items"]
    assert "add_calendar" in buttons
  end

  test "unclear: the unclear card, no items, no model call", w do
    llm!(fn _ -> flunk("no model call") end)
    id = voice!(w, w.harsha, [], status: "unclear", reason: "script_check", language: "si")
    assert :ok = timed(fn -> ActionItems.from_voice(w.og, id) end)
    [{conv, body, card}] = posts()
    assert conv == w.og and card["kind"] == "action_items"
    assert keys(card) == keys(example("envelope_risi_action_items_unclear.json")["risi"])

    assert %{"status" => "unclear", "reason" => "script_check", "items" => [], "lang" => "si"} =
             card

    assert body == RisiMe.Agent.ActionItems.unclear_text(card["lang"])
    assert llm_requests() == []
  end

  test "a clear note with no items: one model call, nothing posted or stored", w do
    llm!(fn _ -> [] end)
    id = voice!(w, w.harsha, [%{"o" => 0, "t" => "hello"}])
    assert :ok = timed(fn -> ActionItems.from_voice(w.og, id) end)
    assert schema_names() == ["action_items"]
    assert posts() == []
    assert Repo.aggregate(Commitment, :count) == 0
  end

  # Fix 2026-10-13 (found by these parity tests): the owner as the call's only speaker crashed
  # the confirm card (`LedgerOut.name_list([], _)`).
  test "a call whose item owner was the only speaker: the confirm card says From your call", w do
    started = DateTime.add(RisiMe.Agent.Clock.now(), -600, :second)

    s = %Session{
      session_id: Ecto.UUID.generate(),
      call_id: Ecto.UUID.generate(),
      conversation_id: w.og,
      chat_id: Secretary.chat_id(w.og),
      state: "ended",
      discard: false,
      started_at: started,
      ended_at: DateTime.add(started, 300, :second)
    }

    llm!(fn body ->
      [it(body, "task", "Send the quotation", "Harsha", "I will send the quotation")]
    end)

    rows = [
      %{
        message_id: TimeUUID.generate(),
        speaker: w.harsha.id,
        text: "I will send the quotation",
        language: "en",
        model: "faster-whisper-large-v3",
        ms: 20_000,
        start: DateTime.add(started, 30, :second),
        turn: 1,
        parts: [%{"o" => 0, "t" => "I will send the quotation"}],
        p: 0.95,
        romanised: false
      }
    ]

    assert :ok = ActionItems.from_call(w.og, s, rows)
    {_, cbody, _} = post_in(posts(), w.rc, "action_confirm")
    assert cbody =~ ~r/From your call \(\d\d:\d\d\)/
  end
end
