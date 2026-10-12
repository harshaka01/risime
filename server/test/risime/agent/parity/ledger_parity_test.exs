defmodule RisiMe.Agent.Parity.LedgerTest do
  @moduledoc """
  Deep agents step 0: parity pins for the Commitment Ledger's detection/extraction as it is
  today (§27.2–§27.4, fake `risi-l1`, Risi's clock fixed): a quiet discussion → one
  `discussion_summary` model call → `Discussion` + `Commitment` rows (owner, counterparts,
  states), each ledger member's copy in their Risi chat and the short `discussion_card` in
  Official; and "no items" → nothing posted or stored.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Agent.{Commitment, Discussion}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :risi_parity
  @moduletag capture_log: true

  @bound_ms 3_000

  setup do
    ledger_on!()
    t0 = DateTime.utc_now() |> DateTime.add(-5 * 3600) |> DateTime.truncate(:second)
    l = RisiMe.Agent.Clock.local(t0, "Asia/Colombo")
    t0 = if l.hour >= 22, do: DateTime.add(t0, -3 * 3600), else: t0
    t0 = %{t0 | microsecond: {0, 6}}
    clock!(t0)

    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    shenika = RisiMe.GroupHelpers.fast_user!("Shenika")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")

    for u <- [harsha, shenika, kamal],
        do: u |> Ecto.Changeset.change(tz: "Asia/Colombo") |> Repo.update!()

    RisiMe.MLSHelpers.with_attestation_key(%{})
    ledger_devices!([harsha, shenika, kamal])
    og = risi_chat!([harsha, shenika, kamal])
    rc_h = own_risi_chat!(harsha)
    rc_s = own_risi_chat!(shenika)

    %{
      harsha: harsha,
      shenika: shenika,
      kamal: kamal,
      og: og,
      t0: t0,
      rc_h: rc_h,
      rc_s: rc_s
    }
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      80 -> Enum.reverse(acc)
    end
  end

  defp timed_quiet!(og) do
    t = System.monotonic_time(:millisecond)
    assert :ok = quiet!(og)
    System.monotonic_time(:millisecond) - t
  end

  test "a quiet discussion: one discussion_summary call, the rows, the copies and the card",
       ctx do
    discussion_llm!(fn body ->
      %{
        "items" => [
          item(body, "Shenika", "Send the revised quote",
            to: ["Harsha"],
            due: "2026-12-01T17:00",
            due_text: "today 5 pm"
          )
        ]
      }
    end)

    {_ids, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 600))
    ms = timed_quiet!(ctx.og)
    assert ms < @bound_ms

    # The model: exactly one call, the discussion_summary schema.
    assert [req] = llm_requests()
    assert get_in(req, ~w(response_format json_schema name)) == "discussion_summary"
    assert List.last(req["messages"])["content"] =~ "line 0 from Harsha"

    # Stored rows.
    [d] = Repo.all(Discussion)
    assert d.source == "chat" and d.conversation_id == ctx.og
    assert d.started_at == ctx.t0 and d.ended_at == last and d.items_count == 1
    assert Enum.sort(d.participants) == Enum.sort([ctx.harsha.id, ctx.shenika.id])

    [c] = Repo.all(Commitment)
    assert c.state == "proposed" and c.item_state == "proposed"
    assert c.owner_id == ctx.shenika.id and c.counterpart_ids == [ctx.harsha.id]
    assert c.summary_id == d.summary_id and c.source == "chat"
    assert c.due == ~U[2026-12-01 11:30:00.000000Z] and c.all_day == false

    # The 48-h expiry job.
    assert [_] = for(j <- all_enqueued(worker: Job), j.args["kind"] == "item_expire", do: j)

    out = posts()

    assert Enum.map(out, fn {c, _, r} -> {c, r["kind"]} end) |> Enum.sort() ==
             Enum.sort([
               {ctx.rc_h, "discussion_summary"},
               {ctx.rc_s, "discussion_summary"},
               {ctx.og, "discussion_card"}
             ])

    [{_, body_h, h}] = for {c, _, _} = p <- out, c == ctx.rc_h, do: p
    [{_, _, s}] = for {c, _, _} = p <- out, c == ctx.rc_s, do: p
    [{_, card_body, card}] = for {c, _, _} = p <- out, c == ctx.og, do: p

    ex = RisiMe.TabsHelpers.example("envelope_risi_discussion_summary_chat.json")
    assert keys(h) == keys(ex["risi"]) and keys(s) == keys(ex["risi"])
    assert h["kind"] == "discussion_summary" and h["for"] == ctx.harsha.id
    assert h["with"] == [ctx.shenika.id] and h["notify"] == [ctx.harsha.id]
    assert h["conversation_id"] == ctx.og and h["source"] == "chat"
    assert h["summary_id"] == d.summary_id and h["items"] == s["items"]
    assert h["made_by"]["model"] == "risi-l1" and h["made_by"]["provider"] == "risime"

    [i] = h["items"]
    assert keys(i) == keys(hd(ex["risi"]["items"]))
    assert i["owner"] == ctx.shenika.id and i["state"] == "proposed"
    assert i["due"] == "2026-12-01T11:30:00.000Z" and i["all_day"] == false

    # Harsha owns nothing: no "Confirm your items" line.
    [header | rest] = String.split(body_h, "\n")
    assert header =~ ~r/^Summary of your discussion with Shenika \(\d\d:\d\d–\d\d:\d\d, chat\)$/

    assert rest == [
             "• The quote needs the new transport cost.",
             "Shenika agreed:",
             "• Send the revised quote (today 5 pm)"
           ]

    [{_, body_s, _}] = for {c, _, _} = p <- out, c == ctx.rc_s, do: p
    assert body_s =~ "You agreed:\n• Send the revised quote (today 5 pm)"
    assert List.last(String.split(body_s, "\n")) == "Confirm your items in RisiMe."

    exc = RisiMe.TabsHelpers.example("envelope_risi_discussion_card.json")
    assert keys(card) == keys(exc["risi"])
    assert card["summary_id"] == d.summary_id and card["items_count"] == 1
    assert card["notify"] == [] and card["summary"] == "Quote talk."
    assert Enum.sort(card["with"]) == Enum.sort([ctx.harsha.id, ctx.shenika.id])
    assert card_body == "Risi summarised this discussion (1 item). Details in your Risi chat."
  end

  test "no items: one model call, nothing posted or stored; no second call", ctx do
    discussion_llm!(fn _ -> %{"items" => []} end)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert timed_quiet!(ctx.og) < @bound_ms

    assert Enum.map(llm_requests(), &get_in(&1, ~w(response_format json_schema name))) ==
             ["discussion_summary"]

    assert posts() == []
    assert Repo.aggregate(Discussion, :count) == 0
    assert Repo.aggregate(Commitment, :count) == 0

    assert :ok = quiet!(ctx.og)
    assert length(llm_requests()) == 1
  end

  test "not quiet yet (newest + 599 s): no model call, nothing stored", ctx do
    discussion_llm!(fn _ -> flunk("no model call") end)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 599))
    assert timed_quiet!(ctx.og) < @bound_ms
    assert llm_requests() == []
    assert posts() == []
    assert Repo.aggregate(Discussion, :count) == 0
  end
end
