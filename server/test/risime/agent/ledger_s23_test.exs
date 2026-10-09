defmodule RisiMe.Agent.LedgerS23Test do
  @moduledoc """
  v1.27 S23 (§27.5, §27.9): item actions in the actor's own Risi chat (owner-only confirm,
  edit and decline; done by the owner or a counterpart; 48-h expiry; idempotent repeats),
  `item_update` to every recipient's copy, and confirmed items in My promises
  (`GET /api/v1/risi/commitments` v1.27 fields). Fake `risi-l1`, fixed clock.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Agent.{Clock, Commitment, Fact, Ledger, Rest}
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
    ledger_devices!([harsha, shenika, kamal])
    og = risi_chat!([harsha, shenika, kamal])
    rc = %{harsha.id => own_risi_chat!(harsha), shenika.id => own_risi_chat!(shenika)}

    discussion_llm!(fn body ->
      %{
        "items" => [
          item(body, "Harsha", "Send the revised quote",
            to: ["Shenika"],
            due: "2026-12-01T17:00",
            due_text: "today 5 pm"
          ),
          item(body, "Shenika", "Book the site visit transport",
            to: ["Harsha"],
            due: "2026-12-07"
          )
        ]
      }
    end)

    {_, last} = talk!(og, [harsha, shenika], t0)
    clock!(DateTime.add(last, 600))
    :ok = quiet!(og)
    drain()

    [mine] = Repo.all(from c in Commitment, where: c.owner_id == ^harsha.id)
    [theirs] = Repo.all(from c in Commitment, where: c.owner_id == ^shenika.id)

    %{
      harsha: harsha,
      shenika: shenika,
      kamal: kamal,
      og: og,
      rc: rc,
      mine: mine.id,
      theirs: theirs.id
    }
  end

  defp tz!(u, tz), do: u |> Ecto.Changeset.change(tz: tz) |> Repo.update!()

  defp drain do
    receive do
      {:risi_post, _, _, _} -> drain()
    after
      20 -> :ok
    end
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  # A risi_action from `user` in `conv` (their Risi chat by default), run as the worker would.
  defp act!(ctx, user, target, action, edit \\ nil, conv \\ nil) do
    conv = conv || ctx.rc[user.id]

    id =
      envelope!(conv, user, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => target,
        "action" => action,
        "edit" => edit
      })

    args = %{"kind" => "action", "conv" => conv, "message_id" => id, "user_id" => user.id}
    perform_job(Job, args)
  end

  defp item_state(id) do
    case Repo.get(Commitment, id) do
      nil -> :deleted
      c -> c.item_state
    end
  end

  test "only the owner confirms; item_update goes to every recipient's Risi chat", ctx do
    # Shenika (a counterpart) can't confirm Harsha's item; Kamal can't either.
    act!(ctx, ctx.shenika, ctx.mine, "item_confirm")
    assert item_state(ctx.mine) == "proposed"
    assert posts() == []

    # Harsha confirms from his Risi chat.
    act!(ctx, ctx.harsha, ctx.mine, "item_confirm")
    assert item_state(ctx.mine) == "confirmed"
    assert Repo.get!(Commitment, ctx.mine).state == "confirmed"

    out = posts()
    assert Enum.sort(for {c, _, _} <- out, do: c) == Enum.sort(Map.values(ctx.rc))

    for {_, body, u} <- out do
      ex = RisiMe.TabsHelpers.example("envelope_risi_item_update.json")["risi"]
      assert Enum.sort(Map.keys(u)) == Enum.sort(Map.keys(ex))
      assert u["kind"] == "item_update" and u["state"] == "confirmed" and u["by"] == ctx.harsha.id
      assert u["item_id"] == ctx.mine and u["notify"] == []
      assert u["made_by"]["model"] == nil
      assert body == "Harsha confirmed 'Send the revised quote' (today 5 pm)."
    end

    # Facts are written for the owner and the counterpart.
    assert Repo.aggregate(from(f in Fact, where: f.commitment_id == ^ctx.mine), :count) == 2

    # A repeat is idempotent (nothing posted).
    act!(ctx, ctx.harsha, ctx.mine, "item_confirm")
    assert posts() == []
  end

  test "actions count only in the actor's own Risi chat, on summaries they received", ctx do
    # In Official, or in someone else's Risi chat: ignored.
    act!(ctx, ctx.harsha, ctx.mine, "item_confirm", nil, ctx.og)
    assert item_state(ctx.mine) == "proposed"
    act!(ctx, ctx.harsha, ctx.mine, "item_confirm", nil, ctx.rc[ctx.shenika.id])
    assert item_state(ctx.mine) == "proposed"

    # A v1.24 confirm on an item_id is ignored, also in the Risi chat.
    act!(ctx, ctx.harsha, ctx.mine, "confirm")
    assert item_state(ctx.mine) == "proposed"

    # Kamal (not a recipient) can't mark it done even once confirmed.
    act!(ctx, ctx.harsha, ctx.mine, "item_confirm")
    rc_k = own_risi_chat!(ctx.kamal)
    ctx = put_in(ctx.rc[ctx.kamal.id], rc_k)
    act!(ctx, ctx.kamal, ctx.mine, "done")
    assert item_state(ctx.mine) == "confirmed"
  end

  test "edit (owner), decline → declined/cancelled, done by a counterpart", ctx do
    due = "2026-12-02T06:30:00.000Z"

    act!(ctx, ctx.harsha, ctx.mine, "item_edit", %{
      "text" => "Send the quote with transport",
      "due" => due,
      "all_day" => false
    })

    c = Repo.get!(Commitment, ctx.mine)
    {:ok, c} = Commitment.open(c)
    assert c.item_state == "edited" and c.text == "Send the quote with transport"
    assert Clock.ts(c.due) == due and c.due_text == nil and c.confirmed_at != nil
    [{_, body, u} | _] = posts()
    assert u["state"] == "edited" and u["due"] == due and body =~ "changed it to"

    # Invalid edits are ignored: a past due, empty or over-long text.
    for bad <- [
          %{"due" => "2020-01-01T00:00:00.000Z"},
          %{"text" => " "},
          %{"text" => String.duplicate("x", 201)}
        ] do
      act!(ctx, ctx.harsha, ctx.mine, "item_edit", bad)
      assert Repo.get!(Commitment, ctx.mine).schedule_v == c.schedule_v
    end

    # Done by the counterpart.
    act!(ctx, ctx.shenika, ctx.mine, "done")
    assert item_state(ctx.mine) == "done"
    assert [{_, _, %{"state" => "done", "by" => by}} | _] = posts()
    assert by == ctx.shenika.id

    # Shenika declines her proposal: declined and deleted.
    act!(ctx, ctx.shenika, ctx.theirs, "item_decline")
    assert item_state(ctx.theirs) == :deleted
    assert [{_, _, %{"state" => "declined"}} | _] = posts()
  end

  test "decline after confirmation cancels; nothing is kept", ctx do
    act!(ctx, ctx.shenika, ctx.theirs, "item_confirm")
    posts()
    act!(ctx, ctx.shenika, ctx.theirs, "item_decline")
    assert item_state(ctx.theirs) == :deleted
    assert [{_, _, %{"state" => "cancelled"}} | _] = posts()
    assert Repo.aggregate(from(f in Fact, where: f.commitment_id == ^ctx.theirs), :count) == 0
  end

  test "48 h after the summary a proposed item can't be confirmed and expires (deleted)", ctx do
    clock!(DateTime.add(Clock.now(), 48 * 3600))
    act!(ctx, ctx.harsha, ctx.mine, "item_confirm")
    assert item_state(ctx.mine) == "proposed"

    [job] = for j <- all_enqueued(worker: Job), j.args["item_id"] == ctx.mine, do: j
    assert job.args["kind"] == "item_expire"
    assert :ok = perform_job(Job, job.args)
    assert item_state(ctx.mine) == :deleted

    # A confirmed item is not expired by the job.
    clock!(DateTime.add(Clock.now(), -48 * 3600))
    act!(ctx, ctx.shenika, ctx.theirs, "item_confirm")
    assert :ok = Ledger.expire(ctx.theirs)
    assert item_state(ctx.theirs) == "confirmed"
  end

  test "My promises: confirmed items with the v1.27 fields; proposed ones are nobody's yet",
       ctx do
    {:ok, []} = Rest.commitments(ctx.harsha.id, :all)

    act!(ctx, ctx.harsha, ctx.mine, "item_confirm")
    act!(ctx, ctx.shenika, ctx.theirs, "item_confirm")

    {:ok, list} = Rest.commitments(ctx.harsha.id, :open)
    wire = list |> Jason.encode!() |> Jason.decode!()
    ex = RisiMe.TabsHelpers.example("risi_commitments_reply_v127.json")["commitments"]
    assert length(wire) == 2

    for c <- wire do
      # Item 9 (2026-10-09) adds optional fields (server status "Contract asks").
      assert Enum.sort(Map.keys(c)) ==
               Enum.sort(
                 Map.keys(hd(ex)) ++
                   ~w(direction owner_name status needs_clarification source_conversation_id
                      source_message_id source_message_ids)
               )

      assert c["source"] == "chat" and is_binary(c["summary_id"])
    end

    roles = Map.new(wire, &{&1["owner"], &1["role"]})
    assert roles == %{ctx.harsha.id => "owner", ctx.shenika.id => "counterpart"}
    assert Enum.find(wire, &(&1["owner"] == ctx.shenika.id))["all_day"] == true

    # Kamal (not owner or counterpart) sees none.
    {:ok, []} = Rest.commitments(ctx.kamal.id, :all)
  end
end
