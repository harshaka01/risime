defmodule RisiMeWeb.RisiRestS8Test do
  @moduledoc """
  v1.24 §24.11 (server S8): `/api/v1/risi/{feedback,facts,commitments}`: shapes equal the contract
  examples, authz (other users' data invisible, non-member feedback 404), the `tabs` device gate,
  delete cascade (embedding included), RISI off.
  """
  use RisiMeWeb.ChannelCase, async: false

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.GroupHelpers, only: [api: 4, api: 5]
  import RisiMe.RisiHelpers, only: [risi_chat!: 1]
  import RisiMe.TabsHelpers
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.Agent.{Commitment, Fact, LearningLog}
  alias RisiMe.{Repo, TimeUUID}

  @moduletag capture_log: true
  setup :with_attestation_key

  setup do
    [a, b, c] = for n <- ~w(Harsha Kamal Nimal), do: logged_in_user(display_name: n)
    %{a: a, b: b, c: c, a_dev: tabs_device!(a), b_dev: tabs_device!(b), c_dev: tabs_device!(c)}
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  defp fact!(user_id, og, text, kind \\ "commitment", chat \\ nil) do
    id = Ecto.UUID.generate()

    {:ok, fact} =
      Fact.seal(%Fact{
        id: id,
        subject_user_id: user_id,
        chat_id: chat || og,
        conversation_id: og,
        kind: kind,
        text: text,
        inserted_at: DateTime.utc_now()
      })

    Repo.insert!(fact)

    Repo.query!(
      "INSERT INTO risi_fact_embeddings (fact_id, model, embedding, inserted_at) VALUES ($1, 'm', '[1,2,3]', now())",
      [Ecto.UUID.dump!(id)]
    )

    id
  end

  defp embeddings(id),
    do:
      Repo.query!("SELECT 1 FROM risi_fact_embeddings WHERE fact_id = $1", [Ecto.UUID.dump!(id)]).num_rows

  defp commitment!(og, owner, counterpart, state, text) do
    now = DateTime.utc_now()

    {:ok, c} =
      Commitment.seal(%Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: og,
        chat_id: "grp:" <> Ecto.UUID.generate(),
        state: state,
        text: text,
        owner_id: owner,
        counterpart_ids: counterpart,
        due: DateTime.add(now, 86_400),
        due_text: "tomorrow",
        proposed_at: now
      })

    Repo.insert!(c)
  end

  describe "facts" do
    test "list own facts only, shaped like the example; delete one, delete all", ctx do
      og = risi_chat!([ctx.a.user, ctx.b.user])
      other = risi_chat!([ctx.c.user])
      mine = fact!(ctx.a.user.id, og, "Send the revised quote", "commitment")
      mine2 = fact!(ctx.a.user.id, og, "Prefers calls after 4 pm", "preference")
      theirs = fact!(ctx.b.user.id, og, "Kamal's fact")
      # A chat the caller is no longer in.
      gone = fact!(ctx.a.user.id, other, "From a chat I left")

      {200, %{"facts" => facts}} = api(:get, "/api/v1/risi/facts", ctx.a.token, nil, ctx.a_dev)
      assert Enum.sort(Enum.map(facts, & &1["fact_id"])) == Enum.sort([mine, mine2])
      ex = RisiMe.TabsHelpers.example("risi_facts_reply.json")["facts"]
      for f <- facts, do: assert(keys(f) == keys(hd(ex)))

      # Someone else's fact: 404, and it survives.
      assert {404, _} = api(:delete, "/api/v1/risi/facts/#{theirs}", ctx.a.token, nil, ctx.a_dev)
      assert {404, _} = api(:delete, "/api/v1/risi/facts/not-a-uuid", ctx.a.token, nil, ctx.a_dev)
      assert Repo.get(Fact, theirs)

      # Hard delete, embedding included.
      assert embeddings(mine) == 1
      assert {204, nil} = api(:delete, "/api/v1/risi/facts/#{mine}", ctx.a.token, nil, ctx.a_dev)
      assert Repo.get(Fact, mine) == nil and embeddings(mine) == 0
      assert {404, _} = api(:delete, "/api/v1/risi/facts/#{mine}", ctx.a.token, nil, ctx.a_dev)

      # Delete all own facts (even in chats left), nobody else's.
      assert {204, nil} = api(:delete, "/api/v1/risi/facts", ctx.a.token, nil, ctx.a_dev)

      assert Repo.all(from f in Fact, where: f.subject_user_id == ^ctx.a.user.id) == []
      assert Enum.all?([mine2, gone], &(embeddings(&1) == 0))
      assert Repo.get(Fact, theirs) && embeddings(theirs) == 1

      assert {200, %{"facts" => []}} =
               api(:get, "/api/v1/risi/facts", ctx.a.token, nil, ctx.a_dev)
    end
  end

  describe "commitments" do
    test "open ones where the caller is owner or counterpart, in chats they are still in", ctx do
      og = risi_chat!([ctx.a.user, ctx.b.user])
      other = risi_chat!([ctx.c.user])
      own = commitment!(og, ctx.a.user.id, [ctx.b.user.id], "confirmed", "Send the quote")
      cp = commitment!(og, ctx.b.user.id, [ctx.a.user.id], "edited", "Review the draft")
      commitment!(og, ctx.a.user.id, [], "proposed", "Not yet tracked")
      commitment!(og, ctx.a.user.id, [], "done", "Already done")
      commitment!(og, ctx.b.user.id, [ctx.c.user.id], "confirmed", "Not mine")
      commitment!(other, ctx.a.user.id, [ctx.c.user.id], "confirmed", "Chat I am not in")

      {200, %{"commitments" => cs} = reply} =
        api(:get, "/api/v1/risi/commitments?state=open", ctx.a.token, nil, ctx.a_dev)

      assert Enum.sort(Enum.map(cs, & &1["commitment_id"])) == Enum.sort([own.id, cp.id])
      # v1.28 §28.7: a v1.24 item as in the v1.28 example (its last item), key for key.
      ex = RisiMe.TabsHelpers.example("risi_commitments_reply_v128.json")
      legacy = ex["commitments"] |> Enum.reject(&Map.has_key?(&1, "summary_id")) |> hd()
      assert keys(reply) == keys(ex)
      assert keys(reply["totals"]) == keys(ex["totals"])

      for c <- cs, do: assert(keys(c) == keys(legacy))

      mine = Enum.find(cs, &(&1["commitment_id"] == own.id))

      assert {mine["state"], mine["owner"], mine["counterpart"], mine["due_text"]} ==
               {"confirmed", ctx.a.user.id, [ctx.b.user.id], "tomorrow"}

      assert mine["official_conversation_id"] == og and mine["due"] =~ ~r/Z$/

      {200, %{"commitments" => all}} =
        api(:get, "/api/v1/risi/commitments?state=all", ctx.a.token, nil, ctx.a_dev)

      assert length(all) == 4

      # Default is open; the stranger sees only their own chat's items.
      assert {200, %{"commitments" => ^cs}} =
               api(:get, "/api/v1/risi/commitments", ctx.a.token, nil, ctx.a_dev)

      assert {200, %{"commitments" => [%{"text" => "Chat I am not in"}]}} =
               api(:get, "/api/v1/risi/commitments", ctx.c.token, nil, ctx.c_dev)
    end
  end

  describe "feedback" do
    defp call!(og) do
      id = TimeUUID.generate()

      :ok =
        LearningLog.record(%{
          call_id: id,
          conversation_id: og,
          chat_id: og,
          task: "commitment_extract",
          model_alias: "risi-l1",
          provider: "local",
          status: "ok"
        })

      id
    end

    test "members only; repeat replaces; others and unknown calls 404", ctx do
      og = risi_chat!([ctx.a.user, ctx.b.user])
      ref = call!(og)
      ex = RisiMe.TabsHelpers.example("risi_feedback.json")
      body = %{"call_ref" => ref, "rating" => "down", "reason" => ex["reason"]}
      assert keys(body) == keys(ex)

      assert {204, nil} = api(:post, "/api/v1/risi/feedback", ctx.a.token, body, ctx.a_dev)

      assert [%{rating: "down", reason: "Wrong due date"}] =
               LearningLog.list_feedback(ref)

      up = %{"call_ref" => ref, "rating" => "up", "reason" => nil}
      assert {204, nil} = api(:post, "/api/v1/risi/feedback", ctx.a.token, up, ctx.a_dev)
      assert [%{rating: "up"}] = LearningLog.list_feedback(ref)

      assert {404, _} = api(:post, "/api/v1/risi/feedback", ctx.c.token, body, ctx.c_dev)
      unknown = %{body | "call_ref" => TimeUUID.generate()}
      assert {404, _} = api(:post, "/api/v1/risi/feedback", ctx.a.token, unknown, ctx.a_dev)
      junk = %{body | "call_ref" => "nope"}
      assert {404, _} = api(:post, "/api/v1/risi/feedback", ctx.a.token, junk, ctx.a_dev)

      for bad <- [%{body | "rating" => "meh"}, %{"rating" => "up"}] do
        assert {400, _} = api(:post, "/api/v1/risi/feedback", ctx.a.token, bad, ctx.a_dev)
      end

      assert [_] = LearningLog.list_feedback(ref)
    end

    test "rate limited", ctx do
      og = risi_chat!([ctx.a.user])
      body = %{"call_ref" => call!(og), "rating" => "up", "reason" => nil}

      codes =
        for _ <- 1..61,
            do: elem(api(:post, "/api/v1/risi/feedback", ctx.b.token, body, ctx.b_dev), 0)

      assert Enum.count(codes, &(&1 == 429)) == 1
    end
  end

  describe "gates" do
    test "no X-Device-Id (or a non-tabs one) still answers with the caller's own data; RISI off gives empty lists",
         ctx do
      old = old_device!(ctx.a)
      assert {200, %{"facts" => []}} = api(:get, "/api/v1/risi/facts", ctx.a.token, nil)
      assert {200, %{"facts" => []}} = api(:get, "/api/v1/risi/facts", ctx.a.token, nil, old)

      assert {200, %{"commitments" => []}} =
               api(:get, "/api/v1/risi/commitments", ctx.a.token, nil)

      assert {204, _} = api(:delete, "/api/v1/risi/facts", ctx.a.token, nil)

      assert {404, _} =
               api(:delete, "/api/v1/risi/facts/#{Ecto.UUID.generate()}", ctx.a.token, nil)

      body = %{"call_ref" => TimeUUID.generate(), "rating" => "up", "reason" => nil}
      assert {404, _} = api(:post, "/api/v1/risi/feedback", ctx.a.token, body, old)

      assert {200, %{"facts" => []}} =
               api(:get, "/api/v1/risi/facts", ctx.a.token, nil, ctx.a_dev)

      assert {200, %{"commitments" => []}} =
               api(:get, "/api/v1/risi/commitments", ctx.a.token, nil, ctx.a_dev)
    end
  end
end
