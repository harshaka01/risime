defmodule RisiMe.Agent.LedgerS22Test do
  @moduledoc """
  v1.27 S22 (§27.3–§27.4): the per-person `discussion_summary` in each recipient's own Risi
  chat (held 24 h when it has none), the owner fallback to the v1.24 in-group card for owners
  without `risi_ledger`, and the short `discussion_card` in Official. Fake `risi-l1`, fixed
  clock, sealed at rest.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Agent.{Commitment, LedgerOut}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    ledger_on!()
    t0 = DateTime.utc_now() |> DateTime.add(-5 * 3600) |> DateTime.truncate(:second)
    t0 = %{t0 | microsecond: {0, 6}}
    clock!(t0)

    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    shenika = RisiMe.GroupHelpers.fast_user!("Shenika")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    for u <- [harsha, shenika, kamal], do: tz!(u, "Asia/Colombo")
    RisiMe.MLSHelpers.with_attestation_key(%{})
    ledger_devices!([harsha, shenika])
    # Kamal is on an older app (risi_tools, no risi_ledger).
    RisiMe.TabsHelpers.risi_tools_device!(kamal)
    og = risi_chat!([harsha, shenika, kamal])
    rc_h = own_risi_chat!(harsha)
    %{harsha: harsha, shenika: shenika, kamal: kamal, og: og, t0: t0, rc_h: rc_h}
  end

  defp tz!(u, tz), do: u |> Ecto.Changeset.change(tz: tz) |> Repo.update!()

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp summarise!(ctx, items_fun) do
    discussion_llm!(fn body -> %{"items" => items_fun.(body)} end)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    posts()
  end

  defp two_items(body) do
    [
      item(body, "Harsha", "Send the revised quote",
        to: ["Shenika"],
        due: "2026-12-01T17:00",
        due_text: "today 5 pm"
      ),
      item(body, "Shenika", "Book the site visit transport",
        to: ["Harsha"],
        due: "2026-12-07",
        due_text: "by Monday"
      )
    ]
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  test "each ledger recipient gets their own copy in their Risi chat; Official gets the short card",
       ctx do
    rc_s = own_risi_chat!(ctx.shenika)
    out = summarise!(ctx, &two_items/1)

    [{_, body_h, h}] = for {c, _, _} = p <- out, c == ctx.rc_h, do: p
    [{_, _body_s, s}] = for {c, _, _} = p <- out, c == rc_s, do: p
    [{_, card_body, card}] = for {c, _, _} = p <- out, c == ctx.og, do: p

    ex = RisiMe.TabsHelpers.example("envelope_risi_discussion_summary_chat.json")

    for {copy, me, other} <- [{h, ctx.harsha, ctx.shenika}, {s, ctx.shenika, ctx.harsha}] do
      assert keys(copy) == keys(ex["risi"])
      assert copy["kind"] == "discussion_summary" and copy["for"] == me.id
      assert copy["with"] == [other.id] and copy["notify"] == [me.id]
      assert copy["conversation_id"] == ctx.og and copy["source"] == "chat"
      assert copy["call_id"] == nil and copy["media"] == nil and copy["duration_s"] == nil
      assert copy["made_by"]["model"] == "risi-l1" and copy["made_by"]["provider"] == "risime"
      assert length(copy["items"]) == 2
      assert Enum.all?(copy["items"], &(&1["state"] == "proposed"))
      assert Enum.all?(copy["items"], &(keys(&1) == keys(hd(ex["risi"]["items"]))))
    end

    # The same summary for everyone.
    assert h["summary_id"] == s["summary_id"] and h["items"] == s["items"]
    shenika_item = Enum.find(h["items"], &(&1["owner"] == ctx.shenika.id))
    assert shenika_item["all_day"] == true and shenika_item["due"] == "2026-12-07T18:29:59.999Z"
    harsha_item = Enum.find(h["items"], &(&1["owner"] == ctx.harsha.id))
    assert harsha_item["due"] == "2026-12-01T11:30:00.000Z" and harsha_item["all_day"] == false

    # The body, in Harsha's zone.
    [header | rest] = String.split(body_h, "\n")
    assert header =~ ~r/^Summary of your discussion with Shenika \(\d\d:\d\d–\d\d:\d\d, chat\)$/
    assert "• The quote needs the new transport cost." in rest
    assert body_h =~ "You agreed:\n• Send the revised quote (today 5 pm)"
    assert body_h =~ "Shenika agreed:\n• Book the site visit transport (by Monday)"
    assert List.last(rest) == "Confirm your items in RisiMe."

    # The short card: no items, owners or dues; silent.
    exc = RisiMe.TabsHelpers.example("envelope_risi_discussion_card.json")
    assert keys(card) == keys(exc["risi"])
    assert card["summary_id"] == h["summary_id"] and card["items_count"] == 2
    assert card["notify"] == [] and card["summary"] == "Quote talk."
    assert Enum.sort(card["with"]) == Enum.sort([ctx.harsha.id, ctx.shenika.id])
    assert card_body == "Risi summarised this discussion (2 items). Details in your Risi chat."
    refute card_body =~ "quote"

    # No in-group proposed card for ledger owners.
    refute Enum.any?(out, fn {_, _, r} -> r["kind"] == "commitment" end)

    # Both items expire 48 h after the summary.
    exp = for j <- all_enqueued(worker: Job), j.args["kind"] == "item_expire", do: j
    assert length(exp) == 2
  end

  test "an owner without risi_ledger gets the v1.24 card in Official; others see the item read-only",
       ctx do
    rc_s = own_risi_chat!(ctx.shenika)

    out =
      summarise!(ctx, fn body ->
        [item(body, "Kamal", "Call the transporter", to: ["Harsha"], due_text: "tomorrow")] ++
          two_items(body)
      end)

    # Kamal: the v1.24 card (with made_by), no copy of his own.
    [{_, cbody, c}] = for {_, _, r} = p <- out, r["kind"] == "commitment", do: p
    assert cbody =~ "Kamal will: Call the transporter"
    assert c["owner"] == ctx.kamal.id and c["state"] == "proposed"
    assert c["made_by"]["model"] == "risi-l1"
    refute Enum.any?(out, fn {_, _, r} -> r["for"] == ctx.kamal.id end)

    row = Repo.get!(Commitment, c["commitment_id"])
    assert row.item_state == nil and row.summary_id != nil
    assert Enum.any?(all_enqueued(worker: Job), &(&1.args["kind"] == "expire"))

    # Harsha's and Shenika's copies list it under "Kamal agreed:".
    [{_, body_h, h}] = for {conv, _, _} = p <- out, conv == ctx.rc_h, do: p
    assert body_h =~ "Kamal agreed:\n• Call the transporter (tomorrow)"
    assert Enum.find(h["items"], &(&1["owner"] == ctx.kamal.id))["state"] == "proposed"
    assert length(h["items"]) == 3
    assert [_] = for({conv, _, _} <- out, conv == rc_s, do: conv)

    [{_, card_body, _}] = for {_, _, r} = p <- out, r["kind"] == "discussion_card", do: p
    assert card_body =~ "(3 items)"

    # Kamal confirms his v1.24 card in Official: the copies hear about it (item_update).
    id =
      envelope!(ctx.og, ctx.kamal, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => c["commitment_id"],
        "action" => "confirm",
        "edit" => nil
      })

    perform_job(Job, %{
      "kind" => "action",
      "conv" => ctx.og,
      "message_id" => id,
      "user_id" => ctx.kamal.id
    })

    out = posts()

    assert [{_, _, %{"kind" => "commitment_update"}}] =
             for({c, _, _} = p <- out, c == ctx.og, do: p)

    updates = for {conv, _, %{"kind" => "item_update"} = u} <- out, do: {conv, u["state"]}
    assert Enum.sort(updates) == Enum.sort([{ctx.rc_h, "confirmed"}, {rc_s, "confirmed"}])
  end

  test "no Risi chat yet: the copy is held sealed for 24 h and posted when the chat is active",
       ctx do
    canary = "CANARY-#{Ecto.UUID.generate()}"

    out =
      summarise!(ctx, fn body ->
        [item(body, "Shenika", "#{canary} task", to: ["Harsha"])]
      end)

    # Harsha's copy and the card went out; Shenika's is held.
    refute Enum.any?(out, fn {_, _, r} -> r["for"] == ctx.shenika.id end)
    assert Enum.any?(out, fn {_, _, r} -> r["for"] == ctx.harsha.id end)
    %{rows: [[1]]} = Repo.query!("SELECT count(*)::int FROM risi_followups_pending")
    %{rows: rows} = Repo.query!("SELECT t::text FROM risi_followups_pending t")
    refute Enum.any?(rows, fn [r] -> r =~ canary end)

    # The chat becomes active: the activation enqueues the delivery.
    rc_s = own_risi_chat!(ctx.shenika)
    RisiMe.RisiChat.activated(%RisiMe.Groups.Group{id: rc_s})
    args = %{"kind" => "pending_copies", "user_id" => ctx.shenika.id}
    assert_enqueued(worker: Job, args: args)
    assert :ok = perform_job(Job, args)

    assert [{^rc_s, body, r}] = posts()
    assert r["kind"] == "discussion_summary" and r["for"] == ctx.shenika.id
    assert body =~ "You agreed:\n• #{canary} task"
    %{rows: [[0]]} = Repo.query!("SELECT count(*)::int FROM risi_followups_pending")
  end

  test "a held copy older than 24 h is dropped, never posted", ctx do
    summarise!(ctx, fn body -> [item(body, "Shenika", "Send it", to: ["Harsha"])] end)
    _rc_s = own_risi_chat!(ctx.shenika)
    clock!(DateTime.add(RisiMe.Agent.Clock.now(), 24 * 3600 + 1))
    assert :ok = LedgerOut.deliver_pending(ctx.shenika.id)
    assert posts() == []
    LedgerOut.prune()
    %{rows: [[0]]} = Repo.query!("SELECT count(*)::int FROM risi_followups_pending")
  end

  test "no recipient on a ledger app: only the v1.24 cards, no copy and no short card", ctx do
    Repo.update_all(RisiMe.Devices.Device,
      set: [capabilities: ~w(groups member_devices tabs risi_tools)]
    )

    out = summarise!(ctx, &two_items/1)
    kinds = for {_, _, r} <- out, do: r["kind"]
    assert Enum.sort(kinds) == ["commitment", "commitment"]
  end

  test "Official off deletes discussions, items and held copies", ctx do
    summarise!(ctx, &two_items/1)
    %{rows: [[n]]} = Repo.query!("SELECT count(*)::int FROM risi_followups_pending")
    assert n == 1
    RisiMe.Agent.Secretary.forget(ctx.og)

    for t <- ~w(risi_discussions risi_followups_pending risi_commitments) do
      %{rows: [[0]]} = Repo.query!("SELECT count(*)::int FROM #{t}")
    end
  end
end
