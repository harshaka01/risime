# Fixtures and store checks for scripts/risi-canary (contract v1.24 §24.14, decisions 065-067).
# Run with `mix run` in server/ (dev env) against the canary's OWN store (RISIME_DEV_DB /
# RISIME_DEV_KEYSPACE = risime_canary*, created fresh by the script and dropped afterwards).
#   setup <dir>   (app started, RISI=on): two throwaway dev-token users CA/CB (friends), seeds Risi,
#                 writes <dir>/canary.json (the LiveRisiInteropTest config) and <dir>/canaries.txt
#   check <dir>   (`mix run --no-start`, RISI_DATA_KEY set): every risi_* Postgres table and
#                 oban_jobs, the decrypted Cassandra risi_buffer and the learning log must hold no
#                 canary; prints positive controls (buffer rows open, an Official line is there,
#                 learning-log calls exist). Last line "canary check: OK" or "canary check: FAIL ...".
#   drop          (`mix run --no-start`): drops the canary keyspace (the DB is dropped by ecto.drop)
alias RisiMe.{Accounts, Repo}
alias RisiMe.Accounts.{User, UserToken}

[mode | rest] = System.argv()
dir = List.first(rest)
keyspace = System.fetch_env!("RISIME_DEV_KEYSPACE")
String.starts_with?(keyspace, "risime_canary") || raise "refusing to run against keyspace #{keyspace}"

xandra = fn ->
  {:ok, _} = Application.ensure_all_started(:xandra)
  [node | _] = Application.fetch_env!(:risime, :cassandra)[:nodes]
  {:ok, conn} = Xandra.start_link(nodes: [node])
  conn
end

case mode do
  "setup" ->
    dev_user = fn phone, name ->
      {:ok, e} = Accounts.allow(%{phone: phone, email: "canary#{String.slice(phone, -2, 2)}@example.com", display_name: name, company: "CodeGen"})
      u = Repo.get_by(User, phone: phone) || Repo.insert!(%User{phone: e.phone, email: e.email, display_name: e.display_name, company: e.company})
      t = :crypto.strong_rand_bytes(32) |> Base.url_encode64(padding: false)
      Repo.insert!(%UserToken{user_id: u.id, token_hash: :crypto.hash(:sha256, t), device_name: "canary"})
      %{"id" => u.id, "token" => t}
    end

    a = dev_user.("+94770000971", "ZZ Canary A")
    b = dev_user.("+94770000972", "ZZ Canary B")
    RisiMe.Social.make_friends!(a["id"], b["id"])
    :ok = RisiMe.Risi.seed()
    c = fn label -> "CANARY-#{label}-#{Ecto.UUID.generate()}" end
    canaries = %{
      "dm_private" => c.("dmp"),
      "grp_private" => c.("grpp"),
      "dm_private_after" => c.("dmp2"),
      "grp_private_after" => c.("grpp2"),
      "dm_official_off" => c.("off")
    }
    cfg = %{
      "url" => "http://127.0.0.1:#{System.fetch_env!("CANARY_PORT")}",
      "risi" => %{"A" => a, "B" => b, "canaries" => canaries, "official_marker" => "OFFICIAL-#{Ecto.UUID.generate()}"}
    }
    File.write!(Path.join(dir, "canary.json"), Jason.encode!(cfg))
    File.chmod!(Path.join(dir, "canary.json"), 0o600)
    File.write!(Path.join(dir, "canaries.txt"), Enum.join(Map.values(canaries), "\n") <> "\n")
    File.write!(Path.join(dir, "marker.txt"), cfg["risi"]["official_marker"] <> "\n")
    IO.puts("canary setup: users=2 risi=seeded canaries=#{map_size(canaries)}")

  "check" ->
    canaries = dir |> Path.join("canaries.txt") |> File.read!() |> String.split("\n", trim: true)
    marker = dir |> Path.join("marker.txt") |> File.read!() |> String.trim()
    # Plain text and the hex form (bytea renders as \x…), so a plaintext copy inside a binary shows too.
    needles = Enum.flat_map(canaries, &[&1, Base.encode16(&1, case: :lower)])
    hit = fn text -> Enum.find(needles, &String.contains?(text, &1)) end
    fails = :ets.new(:fails, [:bag])
    fail = fn where, n -> :ets.insert(fails, {where, n}); IO.puts("canary check: FOUND in #{where}") end

    # ---- Postgres: every risi_* table, and the Oban jobs (args, errors, meta)
    {:ok, _} = Application.ensure_all_started(:ecto_sql)
    {:ok, _} = Application.ensure_all_started(:postgrex)
    {:ok, _} = Repo.start_link(pool_size: 2)
    %{rows: t} = Repo.query!("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND (table_name LIKE 'risi\\_%' OR table_name = 'oban_jobs') ORDER BY 1")
    counts =
      for [table] <- t do
        %{rows: rows} = Repo.query!(~s(SELECT t::text FROM "#{table}" t))
        for [r] <- rows, n = hit.(r), do: fail.("postgres #{table}", n)
        "#{table}=#{length(rows)}"
      end
    IO.puts("canary check: postgres rows #{Enum.join(counts, " ")}")
    %{rows: off} = Repo.query!("SELECT id FROM groups WHERE tab = 'official'")
    official = MapSet.new(List.flatten(off))
    IO.puts("canary check: official conversations=#{MapSet.size(official)}")

    # ---- Cassandra: risi_buffer (decrypted with the run's RISI_DATA_KEY) and the learning log
    {:ok, key} = System.fetch_env!("RISI_DATA_KEY") |> Base.decode64()
    conn = xandra.()
    all = fn table ->
      {:ok, page} = Xandra.execute(conn, "SELECT * FROM #{keyspace}.#{table}", [], page_size: 5_000)
      Enum.to_list(page)
    end
    buf = all.("risi_buffer")
    opened =
      for row <- buf do
        conv = row["conversation_id"]
        MapSet.member?(official, conv) || fail.("risi_buffer row of a non-Official conversation #{conv}", conv)
        case RisiMe.Agent.Transcript.open(key, conv, row["message_id"], row["body"]) do
          {:ok, pt} -> (n = hit.(pt)) && fail.("risi_buffer (decrypted)", n); pt
          :error -> fail.("risi_buffer row that doesn't open with the run's key", conv); nil
        end
      end
    with_marker = Enum.count(opened, &(is_binary(&1) and String.contains?(&1, marker)))
    IO.puts("canary check: risi_buffer rows=#{length(buf)} opened=#{Enum.count(opened, &is_binary/1)} official_marker_rows=#{with_marker}")
    for table <- ~w(risi_llm_calls_by_day risi_llm_calls_by_chat risi_llm_feedback) do
      rows = all.(table)
      for r <- rows, n = hit.(inspect(r, limit: :infinity, printable_limit: :infinity)), do: fail.("learning log #{table}", n)
      IO.puts("canary check: #{table} rows=#{length(rows)}#{if table == "risi_llm_calls_by_day", do: " tasks=" <> (rows |> Enum.map(& &1["task"]) |> Enum.frequencies() |> inspect()), else: ""}")
    end
    calls = length(all.("risi_llm_calls_by_day"))

    found = :ets.tab2list(fails)
    cond do
      found != [] -> IO.puts("canary check: FAIL (#{length(found)} hits)")
      with_marker == 0 -> IO.puts("canary check: FAIL (positive control: no decrypted Official line with the marker in risi_buffer)")
      calls == 0 -> IO.puts("canary check: FAIL (positive control: the learning log recorded no model call)")
      true -> IO.puts("canary check: OK")
    end

  "drop" ->
    conn = xandra.()
    {:ok, _} = Xandra.execute(conn, "DROP KEYSPACE IF EXISTS #{keyspace}", [], timeout: 60_000)
    IO.puts("canary drop: keyspace #{keyspace}")
end

