defmodule RisiMe.Agent.SealAtRestTest do
  @moduledoc """
  Privacy fix 2026-10-09: Risi-derived text is sealed at rest with `RISI_DATA_KEY`: the learning
  log's `output` (both call tables) and feedback `reason`, `risi_commitments.text`/`due_text`,
  `risi_facts.text`. A canary that reaches them is never in plaintext in a raw SQL/CQL read,
  while REST still returns it; pre-existing plaintext rows are sealed by the migration (Postgres)
  and `seal_existing/2` (Cassandra, TTL kept); a missing or wrong key makes the features
  unavailable, never a crash.
  """
  use RisiMeWeb.ChannelCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.GroupHelpers, only: [api: 5]
  import RisiMe.RisiHelpers
  import RisiMe.TabsHelpers, only: [tabs_device!: 1]
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.Agent.{Commitment, Commitments, Fact, LearningLog, Secretary, SealMigration}
  alias RisiMe.Agent.LearningLog.Cassandra, as: LogStore
  alias RisiMe.{Repo, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true
  @cluster RisiMe.Messaging.Store.Cassandra.Cluster

  setup :with_attestation_key

  setup do
    [a, b] = for n <- ~w(Harsha Kamal), do: logged_in_user(display_name: n)
    for u <- [a, b], do: u.user |> Ecto.Changeset.change(tz: "Asia/Colombo") |> Repo.update!()
    og = risi_chat!([a.user, b.user])
    %{a: a, b: b, a_dev: tabs_device!(a), b_dev: tabs_device!(b), og: og}
  end

  defp cql!(statement, params) do
    case Xandra.Cluster.execute(@cluster, statement, params, timeout: 10_000) do
      {:ok, %Xandra.Page{} = page} -> Enum.to_list(page)
      {:ok, _void} -> []
    end
  end

  defp sql_rows(table), do: inspect(Repo.query!("SELECT * FROM #{table}").rows)

  # Extraction over a line holding the canary: the model returns it as the commitment text and
  # due text; the owner (Kamal) confirms, so facts are learned too.
  defp tracked!(ctx, canary) do
    line = "Sure, I'll send the #{canary} quote to Harsha by Friday"

    fake_llm!(fn
      "commitments", body ->
        %{
          "commitments" => [
            %{
              "text" => "Send the #{canary} quote",
              "owner" => ref_of(body, "Kamal"),
              "counterparts" => [ref_of(body, "Harsha")],
              "due_local" => "#{Date.add(Date.utc_today(), 3)}T17:00",
              "due_text" => "by Friday #{canary}",
              "source" => [ref_of(body, line)],
              "confidence" => 0.9
            }
          ]
        }

      _, _ ->
        %{"commitments" => []}
    end)

    say!(ctx.og, ctx.b.user, line)
    assert [%{args: args}] = all_enqueued(worker: Job, queue: :risi)
    assert :ok = perform_job(Job, args)
    assert_receive {:risi_post, _, _, %{"kind" => "commitment"} = card}

    act =
      envelope!(ctx.og, ctx.b.user, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => card["commitment_id"],
        "action" => "confirm"
      })

    perform_job(Job, %{
      "kind" => "action",
      "conv" => ctx.og,
      "message_id" => act,
      "user_id" => ctx.b.user.id
    })

    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update"}}
    card
  end

  test "a canary in a message: no Risi column holds it in plaintext; REST returns it", ctx do
    canary = "CANARY" <> String.replace(Ecto.UUID.generate(), "-", "")
    card = tracked!(ctx, canary)
    ref = card["call_ref"]

    # Postgres, raw: every risi_* table and the jobs.
    %{rows: tables} =
      Repo.query!(
        "SELECT table_name FROM information_schema.tables " <>
          "WHERE table_schema = 'public' AND table_name LIKE 'risi_%'"
      )

    # (risi_fact_embeddings holds a pgvector column Postgrex can't decode; it stays empty while
    # RisiMe.Agent.Embeddings is a stub.)
    tables = for [t] <- tables, t != "risi_fact_embeddings", do: t
    assert "risi_commitments" in tables and "risi_facts" in tables
    for t <- tables ++ ["oban_jobs"], do: refute(sql_rows(t) =~ canary, t)

    [[text, due_text, ts, ds]] =
      Repo.query!(
        "SELECT text, due_text, text_sealed, due_text_sealed FROM risi_commitments WHERE id = $1",
        [Ecto.UUID.dump!(card["commitment_id"])]
      ).rows

    assert text == nil and due_text == nil and is_binary(ts) and is_binary(ds)
    assert Repo.aggregate(from(f in Fact, where: is_nil(f.text_sealed)), :count) == 0

    # Cassandra, raw: both call tables and the feedback (with a canary reason).
    assert {204, nil} =
             api(
               :post,
               "/api/v1/risi/feedback",
               ctx.a.token,
               %{
                 "call_ref" => ref,
                 "rating" => "down",
                 "reason" => "wrong #{canary}"
               },
               ctx.a_dev
             )

    by_day =
      cql!(
        "SELECT * FROM risi_llm_calls_by_day WHERE day = ? AND bucket = ? AND call_id = ?",
        [{"date", LearningLog.day(ref)}, {"int", LearningLog.bucket(ref)}, {"timeuuid", ref}]
      )

    by_chat =
      cql!(
        "SELECT * FROM risi_llm_calls_by_chat WHERE chat_id = ? AND month = ? AND call_id = ?",
        [{"text", Secretary.chat_id(ctx.og)}, {"text", LearningLog.month(ref)}, {"timeuuid", ref}]
      )

    feedback = cql!("SELECT * FROM risi_llm_feedback WHERE call_id = ?", [{"timeuuid", ref}])

    for rows <- [by_day, by_chat, feedback] do
      assert [_] = rows
      refute inspect(rows) =~ canary
      assert :binary.match(:erlang.term_to_binary(rows), canary) == :nomatch
    end

    assert [%{"output" => nil, "output_sealed" => <<_::binary>>}] = by_day
    assert [%{"output" => nil, "output_sealed" => <<_::binary>>}] = by_chat
    assert [%{"reason" => nil, "reason_sealed" => <<_::binary>>}] = feedback

    # Readers decrypt.
    assert {:ok, %{output: out}} = LearningLog.get(ref)
    assert out =~ canary
    assert [%{reason: "wrong " <> _}] = LearningLog.list_feedback(ref)

    assert {200, %{"commitments" => [c]}} =
             api(:get, "/api/v1/risi/commitments", ctx.a.token, nil, ctx.a_dev)

    assert c["text"] == "Send the #{canary} quote" and c["due_text"] == "by Friday #{canary}"

    assert {200, %{"facts" => [f]}} = api(:get, "/api/v1/risi/facts", ctx.b.token, nil, ctx.b_dev)
    assert f["text"] == "Send the #{canary} quote"

    assert {200, %{"facts" => [f]}} = api(:get, "/api/v1/risi/facts", ctx.a.token, nil, ctx.a_dev)
    assert f["text"] == "Kamal will: Send the #{canary} quote"

    # The digest and the reminder read the sealed text too.
    nine = DateTime.new!(Date.utc_today(), ~T[03:40:00], "Etc/UTC")
    assert :sent = Commitments.digest(ctx.og, nine)
    assert_receive {:risi_post, _, "Open items today: Send the " <> _, _}

    c = Repo.get(Commitment, card["commitment_id"])

    assert :ok =
             perform_job(Job, %{
               "kind" => "reminder",
               "conv" => ctx.og,
               "commitment_id" => c.id,
               "v" => c.schedule_v
             })

    assert_receive {:risi_post, _, "Reminder: Send the " <> _, _}
  end

  test "an edit re-seals the text (and the plaintext columns can't be written)", ctx do
    card = tracked!(ctx, "first")

    act =
      envelope!(ctx.og, ctx.b.user, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => card["commitment_id"],
        "action" => "edit",
        "edit" => %{"text" => "Send the second quote"}
      })

    perform_job(Job, %{
      "kind" => "action",
      "conv" => ctx.og,
      "message_id" => act,
      "user_id" => ctx.b.user.id
    })

    assert_receive {:risi_post, _, "Kamal changed it to: Send the second quote" <> _, _}
    {:ok, c} = Commitment.open(Repo.get(Commitment, card["commitment_id"]))
    assert c.text == "Send the second quote" and c.due_text == "by Friday first"
    refute sql_rows("risi_commitments") =~ "second"

    assert_raise Postgrex.Error, ~r/risi_commitments_no_plaintext/, fn ->
      Repo.query!("UPDATE risi_commitments SET text = 'plain' WHERE id = $1", [
        Ecto.UUID.dump!(c.id)
      ])
    end
  end

  test "migration: pre-existing plaintext rows are sealed and blanked, idempotently", ctx do
    # Rows as the old code wrote them (the constraints are lifted inside this test's transaction).
    Repo.query!("ALTER TABLE risi_commitments DROP CONSTRAINT risi_commitments_no_plaintext")
    Repo.query!("ALTER TABLE risi_facts DROP CONSTRAINT risi_facts_no_plaintext")
    Repo.query!("ALTER TABLE risi_commitments ALTER COLUMN text_sealed DROP NOT NULL")
    Repo.query!("ALTER TABLE risi_facts ALTER COLUMN text_sealed DROP NOT NULL")

    cid = Ecto.UUID.generate()
    fid = Ecto.UUID.generate()

    Repo.query!(
      "INSERT INTO risi_commitments (id, conversation_id, chat_id, state, text, owner_id, " <>
        "counterpart_ids, due_text, source_message_ids, proposed_at, inserted_at, updated_at) " <>
        "VALUES ($1, $2, $2, 'confirmed', 'Old plaintext promise', $3, $4, 'old due', '{}', now(), now(), now())",
      [
        Ecto.UUID.dump!(cid),
        ctx.og,
        Ecto.UUID.dump!(ctx.b.user.id),
        [Ecto.UUID.dump!(ctx.a.user.id)]
      ]
    )

    Repo.query!(
      "INSERT INTO risi_facts (id, subject_user_id, chat_id, conversation_id, kind, text, " <>
        "source_message_ids, inserted_at) VALUES ($1, $2, $3, $3, 'commitment', 'Old plaintext fact', '{}', now())",
      [Ecto.UUID.dump!(fid), Ecto.UUID.dump!(ctx.a.user.id), ctx.og]
    )

    # Without the key nothing changes: the migration stops.
    key = Application.get_env(:risime, :risi_data_key)
    Application.delete_env(:risime, :risi_data_key)
    assert_raise RuntimeError, ~r/RISI_DATA_KEY/, fn -> SealMigration.seal_postgres(Repo) end
    Application.put_env(:risime, :risi_data_key, key)
    assert sql_rows("risi_commitments") =~ "Old plaintext promise"

    assert %{commitments: 1, facts: 1} = SealMigration.seal_postgres(Repo)
    assert %{commitments: 0, facts: 0} = SealMigration.seal_postgres(Repo)

    for t <- ~w(risi_commitments risi_facts), do: refute(sql_rows(t) =~ "Old plaintext")

    assert {200,
            %{"commitments" => [%{"text" => "Old plaintext promise", "due_text" => "old due"}]}} =
             api(:get, "/api/v1/risi/commitments", ctx.a.token, nil, ctx.a_dev)

    assert {200, %{"facts" => [%{"text" => "Old plaintext fact"}]}} =
             api(:get, "/api/v1/risi/facts", ctx.a.token, nil, ctx.a_dev)

    # The down direction opens them back.
    :ok = SealMigration.unseal_postgres(Repo)
    assert sql_rows("risi_facts") =~ "Old plaintext fact"
  end

  test "Cassandra: pre-existing plaintext outputs and reasons are sealed with their TTL", ctx do
    id = TimeUUID.generate()
    chat = Secretary.chat_id(ctx.og)
    out = ~s({"answer":"old plaintext answer"})
    day = [{"date", LearningLog.day(id)}, {"int", LearningLog.bucket(id)}, {"timeuuid", id}]
    by_chat = [{"text", chat}, {"text", LearningLog.month(id)}, {"timeuuid", id}]

    cql!(
      "INSERT INTO risi_llm_calls_by_day (day, bucket, call_id, chat_id, conversation_id, task, " <>
        "status, output) VALUES (?, ?, ?, ?, ?, 'ask', 'ok', ?) USING TTL 5000",
      day ++ [{"text", chat}, {"text", ctx.og}, {"text", out}]
    )

    cql!(
      "INSERT INTO risi_llm_calls_by_chat (chat_id, month, call_id, conversation_id, task, " <>
        "status, output) VALUES (?, ?, ?, ?, 'ask', 'ok', ?) USING TTL 5000",
      by_chat ++ [{"text", ctx.og}, {"text", out}]
    )

    cql!(
      "INSERT INTO risi_llm_feedback (call_id, user_id, rating, reason, at) VALUES (?, ?, 'down', " <>
        "'old plaintext reason', toTimestamp(now())) USING TTL 5000",
      [{"timeuuid", id}, {"uuid", ctx.a.user.id}]
    )

    # Before: the plaintext is read as it is.
    assert {:ok, %{output: ^out}} = LearningLog.get(id)

    assert {:ok, %{by_day: n, by_chat: m, feedback: k}} = LogStore.seal_existing()
    assert n >= 1 and m >= 1 and k >= 1

    where_day = "day = ? AND bucket = ? AND call_id = ?"
    where_chat = "chat_id = ? AND month = ? AND call_id = ?"

    for {t, w, key} <- [
          {"risi_llm_calls_by_day", where_day, day},
          {"risi_llm_calls_by_chat", where_chat, by_chat}
        ] do
      assert [%{"output" => nil, "output_sealed" => s, "ttl" => ttl}] =
               cql!(
                 "SELECT output, output_sealed, TTL(output_sealed) AS ttl FROM #{t} WHERE #{w}",
                 key
               )

      assert :binary.match(s, "old plaintext") == :nomatch
      assert ttl > 4900 and ttl <= 5000
    end

    assert [%{"reason" => nil, "ttl" => ttl}] =
             cql!(
               "SELECT reason, TTL(reason_sealed) AS ttl FROM risi_llm_feedback WHERE call_id = ?",
               [{"timeuuid", id}]
             )

    assert ttl > 4900 and ttl <= 5000

    assert {:ok, %{output: ^out}} = LearningLog.get(id)

    assert [%{output: ^out}] =
             Enum.filter(
               LearningLog.list_by_chat(chat, LearningLog.month(id)),
               &(&1.call_id == id)
             )

    assert [%{reason: "old plaintext reason"}] = LearningLog.list_feedback(id)

    # Idempotent.
    assert {:ok, _} = LogStore.seal_existing()
    assert {:ok, %{output: ^out}} = LearningLog.get(id)

    # Without the key nothing is touched.
    Application.delete_env(:risime, :risi_data_key)
    assert {:error, :missing_key} = LogStore.seal_existing()
  end

  test "a wrong or missing key: unavailable, never a crash, never plaintext", ctx do
    card = tracked!(ctx, "keyed")
    ref = card["call_ref"]
    c = Repo.get(Commitment, card["commitment_id"])
    nine = DateTime.new!(Date.utc_today(), ~T[03:40:00], "Etc/UTC")

    for key <- [Base.encode64(:crypto.strong_rand_bytes(32)), nil] do
      if key,
        do: Application.put_env(:risime, :risi_data_key, key),
        else: Application.delete_env(:risime, :risi_data_key)

      assert {503, %{"error" => %{"code" => "agent_unavailable"}}} =
               api(:get, "/api/v1/risi/commitments", ctx.a.token, nil, ctx.a_dev)

      assert {503, %{"error" => %{"code" => "agent_unavailable"}}} =
               api(:get, "/api/v1/risi/facts", ctx.a.token, nil, ctx.a_dev)

      assert {:ok, %{output: nil, output_unavailable: true}} = LearningLog.get(ref)
      assert :skipped = Commitments.digest(ctx.og, nine)

      result =
        perform_job(Job, %{
          "kind" => "reminder",
          "conv" => ctx.og,
          "commitment_id" => c.id,
          "v" => c.schedule_v
        })

      assert result == :ok or match?({:snooze, _}, result)
      refute_receive {:risi_post, _, _, _}
    end

    # No key at all: the learning-log entry is kept without its output; feedback keeps its
    # rating only.
    Application.delete_env(:risime, :risi_data_key)

    LearningLog.record(%{
      call_id: TimeUUID.generate(),
      conversation_id: ctx.og,
      chat_id: ctx.og,
      task: "ask",
      model_alias: "risi-l1",
      provider: "local",
      status: "ok",
      output: ~s({"answer":"nokey-canary"})
    })

    rows =
      cql!("SELECT * FROM risi_llm_calls_by_chat WHERE chat_id = ? AND month = ?", [
        {"text", ctx.og},
        {"text", Calendar.strftime(Date.utc_today(), "%Y-%m")}
      ])

    assert [%{"output" => nil, "output_sealed" => nil}] = rows
    LearningLog.put_feedback(ref, ctx.a.user.id, "down", "nokey-canary")

    refute inspect(cql!("SELECT * FROM risi_llm_feedback WHERE call_id = ?", [{"timeuuid", ref}])) =~
             "nokey"

    assert [%{rating: "down", reason: nil}] = LearningLog.list_feedback(ref)
  end
end
