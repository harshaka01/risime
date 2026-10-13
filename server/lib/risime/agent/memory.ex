defmodule RisiMe.Agent.Memory do
  @moduledoc """
  Risi's long-term memory of Official chats (contract v1.36 §35, decision 077, "Risi Brain v2").

  * **What is kept** (§35.1): every Official `text` an active human member sends that Risi
    decrypts as a member (`RisiMe.Agent.Conversation`), and Risi's own posts there
    (`RisiMe.Agent.Out`), sealed under `RISI_DATA_KEY` with the row AAD
    `risi_memory_messages:<id>:body`, **until deleted**. The Risi chat (the user's own chat with
    Risi) and Private are never stored here. The 24-h buffer (`Transcript`) stays the turn's
    working set; `seed/1` copies what it still holds when a chat's memory starts, and reads union
    it, so memory reaches back 24 h before the deploy.
  * **Opt-out** (§35.3): a sender who stopped Risi learning (everywhere, or in this chat) is
    dropped **before sealing**; nothing of theirs is stored or extracted from then on.
  * **Derived items** (`risi_memory_items`, `RisiMe.Agent.MemoryIngest`) and **vectors**
    (`risi_memory_vectors`, `RisiMe.Agent.Embeddings`, no text) sit next to the messages.
  * **Deletion** (§35.3, §15, §24.4): a user's own messages and Risi's posts to them per chat or
    everywhere (with the items derived from them and every vector), a §15 delete for everyone,
    and the whole chat when Risi leaves it or Official is turned off.

  `RISI_MEMORY=off` stops storing (reads still work). Nothing here logs content.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Embeddings, Seal}
  alias RisiMe.{Repo, TimeUUID}

  defmodule Message do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:id, :binary_id, autogenerate: false}
    schema "risi_memory_messages" do
      field :conversation_id, :string
      field :message_id, :string
      field :sender_id, :binary_id
      field :addressed_to, :binary_id
      field :from_risi, :boolean, default: false
      field :at, :utc_datetime_usec
      field :body_sealed, :binary
      field :inserted_at, :utc_datetime_usec
    end
  end

  defmodule Item do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:id, :binary_id, autogenerate: false}
    schema "risi_memory_items" do
      field :conversation_id, :string
      field :kind, :string
      field :people_ids, {:array, :binary_id}, default: []
      field :text_sealed, :binary
      field :at, :utc_datetime_usec
      field :source_message_ids, {:array, :string}, default: []
      field :inserted_at, :utc_datetime_usec
      field :updated_at, :utc_datetime_usec
    end
  end

  @kinds ~w(person project decision promise meeting deadline open_question summary)
  # Risi posts that are not worth remembering (progress, card plumbing, errors).
  # v1.39 §36.5: the consent lines and the developer comparison are not memory.
  @skip_kinds ~w(progress confirm_update error tool_call queued call_transcribe
                 call_transcript_compare)

  @doc "The item kinds of §35.1."
  def kinds, do: @kinds

  @doc "False only with `RISI_MEMORY=off`."
  def on? do
    case Application.get_env(:risime, :risi_memory) do
      nil -> System.get_env("RISI_MEMORY", "on") not in ~w(off false 0)
      v -> v not in [false, "off"]
    end
  end

  ## Ingest

  @doc """
  Stores one decrypted Official message of an active human member (§35.1): a `text` envelope's
  body, or (PART 9) the `ok` transcript of the member's voice note (Risi's buffered
  `voice_transcript` working copy, under the note's id), unless the sender opted out. `:ok`
  (stored or skipped).
  """
  def put_message(conv, %{message_id: id, sender_id: sender, plaintext: pt}) do
    with true <- on?(),
         true <- memory_chat?(conv),
         {:ok, env} <- Jason.decode(pt),
         body when is_binary(body) <- said(env),
         true <- String.trim(body) != "",
         true <- learning_enabled?(sender, conv),
         {:ok, _} <- store(conv, id, sender, nil, false, at(id), body) do
      RisiMe.Agent.MemoryIngest.schedule(conv)
      :ok
    else
      _ -> :ok
    end
  rescue
    e ->
      Logger.warning("Risi memory skipped a message: #{inspect(e.__struct__)}")
      :ok
  end

  # What a member said: a typed `text`, or the transcript of their voice note.
  defp said(%{"type" => "text", "body" => body}) when is_binary(body), do: body

  defp said(%{"type" => "voice_transcript", "status" => "ok", "text" => text})
       when is_binary(text),
       do: text

  defp said(_), do: nil

  @doc "Stores Risi's own post in an Official chat (§35.1); never in a Risi chat."
  def put_risi_post(conv, message_id, body, risi) do
    with true <- on?(),
         true <- is_binary(body) and String.trim(body) != "",
         true <- (risi || %{})["kind"] not in @skip_kinds,
         true <- memory_chat?(conv) do
      to =
        case (risi || %{})["notify"] do
          [one] when is_binary(one) -> one
          _ -> nil
        end

      store(conv, message_id, RisiMe.Risi.user_id(), to, true, at(message_id), body)
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi memory skipped a post: #{inspect(e.__struct__)}")
      :ok
  end

  # Official, not the user's own Risi chat.
  defp memory_chat?(conv),
    do: RisiMe.Agent.official?(conv) and not RisiMe.Groups.Tabs.risi_chat?(conv)

  defp at(message_id) do
    TimeUUID.to_datetime(message_id) |> RisiMe.Agent.Clock.usec()
  rescue
    _ -> RisiMe.Agent.Clock.usec(DateTime.utc_now())
  end

  defp store(conv, message_id, sender, to, risi?, at, text) do
    id = Ecto.UUID.generate()

    with {:ok, sealed} <- Seal.seal_text("risi_memory_messages", id, "body", text) do
      row = %{
        id: id,
        conversation_id: conv,
        message_id: message_id,
        sender_id: sender,
        addressed_to: to,
        from_risi: risi?,
        at: at,
        body_sealed: sealed,
        inserted_at: RisiMe.Agent.Clock.usec(DateTime.utc_now())
      }

      case Repo.insert_all(Message, [row],
             on_conflict: :nothing,
             conflict_target: [:conversation_id, :message_id]
           ) do
        {1, _} ->
          put_vector(id, "message", conv, text)
          {:ok, id}

        _ ->
          {:ok, :exists}
      end
    end
  end

  @doc false
  def put_vector(owner_id, kind, conv, text) do
    Repo.query!(
      "INSERT INTO risi_memory_vectors (owner_id, owner_kind, conversation_id, model, embedding, " <>
        "inserted_at) VALUES ($1, $2, $3, $4, $5::text::vector, $6) ON CONFLICT (owner_id) DO NOTHING",
      [
        Ecto.UUID.dump!(owner_id),
        kind,
        conv,
        Embeddings.model(),
        Embeddings.literal(Embeddings.embed(text)),
        NaiveDateTime.utc_now()
      ]
    )

    :ok
  end

  @doc """
  Copies the 24-h buffer's `text` messages of `conv` into memory once (when the chat's memory
  starts), honouring opt-outs. Idempotent.
  """
  def seed(conv) do
    if on?() and memory_chat?(conv) and not seeded?(conv) do
      for m <- RisiMe.Agent.Secretary.text_messages(conv),
          learning_enabled?(m.sender_id, conv),
          do: store(conv, m.message_id, m.sender_id, nil, false, at(m.message_id), m.text)

      now = RisiMe.Agent.Clock.usec(DateTime.utc_now())

      Repo.insert_all(
        "risi_memory_state",
        [%{conversation_id: conv, seeded_at: now, updated_at: now}],
        on_conflict: [set: [seeded_at: now, updated_at: now]],
        conflict_target: [:conversation_id]
      )
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi memory seed failed: #{inspect(e.__struct__)}")
      :ok
  end

  defp seeded?(conv),
    do:
      Repo.exists?(
        from s in "risi_memory_state",
          where: s.conversation_id == ^conv and not is_nil(s.seeded_at),
          select: 1
      )

  @doc "Seeds every Official chat Risi is in that has no memory yet (the hourly prune job)."
  def seed_all do
    if on?() do
      risi = RisiMe.Risi.user_id()

      Repo.all(
        from g in RisiMe.Groups.Group,
          join: m in RisiMe.Groups.Member,
          on: m.group_id == g.id,
          left_join: s in "risi_memory_state",
          on: s.conversation_id == g.id,
          where:
            g.tab == "official" and g.chat_kind != "risi" and g.state == "active" and
              m.user_id == ^risi and m.state == "active" and is_nil(s.seeded_at),
          select: g.id
      )
      |> Enum.each(&seed/1)
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi memory seed_all failed: #{inspect(e.__struct__)}")
      :ok
  end

  ## Items (RisiMe.Agent.MemoryIngest)

  @doc """
  Stores one derived item: `%{kind, text, people (names), people_ids, project, at,
  source_message_ids}`. `{:ok, id}` or `:error`.
  """
  def put_item(conv, %{kind: kind, text: text} = it) when kind in @kinds and is_binary(text) do
    id = Ecto.UUID.generate()
    now = RisiMe.Agent.Clock.usec(RisiMe.Agent.Clock.now())

    with {:ok, key} <- Seal.data() do
      sealed =
        Seal.seal(key, Seal.aad("risi_memory_items", id, "text"), %{
          "text" => text,
          "people" => it[:people] || [],
          "project" => it[:project]
        })

      Repo.insert_all(Item, [
        %{
          id: id,
          conversation_id: conv,
          kind: kind,
          people_ids: it[:people_ids] || [],
          text_sealed: sealed,
          at: it[:at] && RisiMe.Agent.Clock.usec(it[:at]),
          source_message_ids: it[:source_message_ids] || [],
          inserted_at: now,
          updated_at: now
        }
      ])

      put_vector(id, "item", conv, text <> " " <> Enum.join(it[:people] || [], " "))
      {:ok, id}
    end
  end

  def put_item(_conv, _it), do: :error

  @doc "Opens an item: `{:ok, %{id, conversation_id, kind, text, people, project, at, …}}`."
  def open_item(%Item{} = i) do
    with {:ok, key} <- Seal.data(),
         {:ok, %{"text" => t} = m} <-
           Seal.open(key, Seal.aad("risi_memory_items", i.id, "text"), i.text_sealed) do
      {:ok,
       %{
         id: i.id,
         conversation_id: i.conversation_id,
         kind: i.kind,
         text: t,
         people: m["people"] || [],
         people_ids: i.people_ids,
         project: m["project"],
         at: i.at,
         inserted_at: i.inserted_at,
         source_message_ids: i.source_message_ids
       }}
    else
      _ -> :error
    end
  end

  ## Opt-out (§35.3)

  @doc "`%{global: bool, chats: [%{conversation_id, enabled}]}` of `user`."
  def learning(user) do
    rows =
      Repo.all(
        from o in "risi_learning_optouts",
          where: o.user_id == type(^user, Ecto.UUID),
          order_by: [asc: o.scope],
          select: {o.scope, o.enabled}
      )

    %{
      global:
        case List.keyfind(rows, "*", 0) do
          {_, e} -> e
          nil -> true
        end,
      chats: for({s, e} <- rows, s != "*", do: %{conversation_id: s, enabled: e})
    }
  end

  @doc "Sets `user`'s learning switch for one chat (`conv`) or everywhere (nil)."
  def set_learning(user, conv, enabled) when is_boolean(enabled) do
    now = RisiMe.Agent.Clock.usec(DateTime.utc_now())

    Repo.insert_all(
      "risi_learning_optouts",
      [
        %{
          user_id: Ecto.UUID.dump!(user),
          scope: conv || "*",
          enabled: enabled,
          updated_at: now
        }
      ],
      on_conflict: [set: [enabled: enabled, updated_at: now]],
      conflict_target: [:user_id, :scope]
    )

    :ok
  end

  @doc "True unless `user` stopped Risi learning everywhere or in `conv`."
  def learning_enabled?(user, conv) do
    not Repo.exists?(
      from o in "risi_learning_optouts",
        where:
          o.user_id == type(^user, Ecto.UUID) and o.scope in ["*", ^conv] and o.enabled == false,
        select: 1
    )
  rescue
    _ -> true
  end

  ## Deletion

  @doc """
  §35.3: deletes `user`'s own messages and Risi's posts to `user` in `conv` (or `:all` chats),
  the items derived from them, and their vectors. Other members' rows stay.
  """
  def delete_user(user, conv) do
    q =
      from m in Message,
        where: m.sender_id == ^user or m.addressed_to == ^user,
        select: {m.id, m.conversation_id, m.message_id}

    q = if conv == :all, do: q, else: where(q, [m], m.conversation_id == ^conv)
    rows = Repo.all(q)

    Repo.transaction(fn ->
      rows
      |> Enum.group_by(&elem(&1, 1), &elem(&1, 2))
      |> Enum.each(fn {c, mids} -> drop_items_from(c, mids) end)

      ids = Enum.map(rows, &elem(&1, 0))
      drop_vectors(ids)
      Repo.delete_all(from m in Message, where: m.id in ^ids)

      # Everything forgotten: the user's spoken-language tally goes too.
      if conv == :all,
        do:
          Repo.delete_all(
            from l in "risi_speech_langs", where: l.user_id == type(^user, Ecto.UUID)
          )
    end)

    :ok
  end

  @doc "§15 delete for everyone: the messages, the items derived from them, their vectors."
  def message_deleted(conv, ids) when is_list(ids) and ids != [] do
    rows =
      Repo.all(
        from m in Message,
          where: m.conversation_id == ^conv and m.message_id in ^ids,
          select: m.id
      )

    drop_items_from(conv, ids)
    drop_vectors(rows)
    Repo.delete_all(from m in Message, where: m.id in ^rows)
    :ok
  end

  def message_deleted(_conv, _ids), do: :ok

  @doc "Everything of `conv` (§24.4 Official off, Risi removed)."
  def forget(conv) do
    Repo.delete_all(from v in "risi_memory_vectors", where: v.conversation_id == ^conv)
    Repo.delete_all(from m in Message, where: m.conversation_id == ^conv)
    Repo.delete_all(from i in Item, where: i.conversation_id == ^conv)
    Repo.delete_all(from s in "risi_memory_state", where: s.conversation_id == ^conv)
    :ok
  end

  defp drop_items_from(_conv, []), do: :ok

  defp drop_items_from(conv, mids) do
    ids =
      Repo.all(
        from i in Item,
          where:
            i.conversation_id == ^conv and
              fragment("? && ?::text[]", i.source_message_ids, ^mids),
          select: i.id
      )

    drop_vectors(ids)
    Repo.delete_all(from i in Item, where: i.id in ^ids)
    :ok
  end

  defp drop_vectors([]), do: :ok

  defp drop_vectors(ids) do
    Repo.delete_all(
      from v in "risi_memory_vectors", where: v.owner_id in type(^ids, {:array, Ecto.UUID})
    )

    :ok
  end

  ## "What Risi knows about me" (§24.13 facts, §35.1 items)

  @doc "The items about `user` in chats they are in, newest first, as fact maps."
  def facts(user) do
    Repo.all(
      from i in Item,
        where: ^user in i.people_ids and i.conversation_id in subquery(mine(user)),
        order_by: [desc: i.inserted_at, asc: i.id]
    )
    |> Enum.flat_map(fn i ->
      case open_item(i) do
        {:ok, it} ->
          [
            %{
              fact_id: it.id,
              kind: it.kind,
              text: it.text,
              chat_id: RisiMe.Agent.Secretary.chat_id(it.conversation_id),
              created_at: RisiMe.Messaging.iso(it.inserted_at)
            }
          ]

        :error ->
          []
      end
    end)
  end

  @doc "Deletes one item about `user`: `:ok` or `:not_found`."
  def delete_item(user, id) do
    case Repo.all(from i in Item, where: i.id == ^id and ^user in i.people_ids, select: i.id) do
      [] ->
        :not_found

      ids ->
        drop_vectors(ids)
        Repo.delete_all(from i in Item, where: i.id in ^ids)
        :ok
    end
  end

  @doc "Deletes every item about `user` (and their vectors)."
  def delete_items_about(user) do
    ids = Repo.all(from i in Item, where: ^user in i.people_ids, select: i.id)
    drop_vectors(ids)
    Repo.delete_all(from i in Item, where: i.id in ^ids)
    :ok
  end

  ## Reads (RisiMe.Agent.MemoryTools)

  @doc """
  The Official chats `user` is an active member of, except Risi chats (the scope of every read,
  §35.2): `[conversation_id]`.
  """
  def scope(user) do
    Repo.all(
      from m in RisiMe.Groups.Member,
        join: g in RisiMe.Groups.Group,
        on: g.id == m.group_id,
        where:
          m.user_id == ^user and m.state == "active" and g.tab == "official" and
            g.chat_kind != "risi",
        select: g.id
    )
  end

  defp mine(user),
    do:
      from(m in RisiMe.Groups.Member,
        where: m.user_id == ^user and m.state == "active",
        select: m.group_id
      )

  @doc """
  The messages of `convs` in `[from, to)` (nil = open), oldest first, opened:
  `[%{message_id, conversation_id, sender_id, from_risi, at, text}]`, at most `limit` (the newest
  ones when there are more). Buffered messages not stored yet (24 h) are included.
  """
  def messages(convs, from, to, limit \\ 400)
  def messages([], _from, _to, _limit), do: []

  def messages(convs, from, to, limit) do
    q =
      from m in Message,
        where: m.conversation_id in ^convs,
        order_by: [desc: m.at],
        limit: ^limit

    q = if from, do: where(q, [m], m.at >= ^from), else: q
    q = if to, do: where(q, [m], m.at < ^to), else: q

    stored = q |> Repo.all() |> Enum.flat_map(&open_message/1)
    have = MapSet.new(stored, & &1.message_id)

    (stored ++ buffered(convs, from, to, have))
    |> Enum.sort_by(& &1.at, DateTime)
    |> Enum.take(-limit)
  end

  @doc "The given stored messages by row id, opened, oldest first."
  def by_ids([]), do: []

  def by_ids(ids) do
    Repo.all(from m in Message, where: m.id in ^ids, order_by: [asc: m.at])
    |> Enum.flat_map(&open_message/1)
  end

  defp open_message(%Message{} = m) do
    case Seal.open_text("risi_memory_messages", m.id, "body", m.body_sealed) do
      {:ok, t} ->
        [
          %{
            id: m.id,
            message_id: m.message_id,
            conversation_id: m.conversation_id,
            sender_id: m.sender_id,
            from_risi: m.from_risi,
            at: m.at,
            text: t
          }
        ]

      _ ->
        []
    end
  end

  # The 24-h buffer's texts in range that memory doesn't hold yet (seed not run, opt-outs kept).
  defp buffered(convs, from, to, have) do
    Enum.flat_map(convs, fn conv ->
      conv
      |> RisiMe.Agent.Secretary.text_messages(from)
      |> Enum.flat_map(fn m ->
        at = at(m.message_id)

        if MapSet.member?(have, m.message_id) or (to && DateTime.compare(at, to) != :lt) or
             not learning_enabled?(m.sender_id, conv) do
          []
        else
          [
            %{
              id: nil,
              message_id: m.message_id,
              conversation_id: conv,
              sender_id: m.sender_id,
              from_risi: false,
              at: at,
              text: m.text
            }
          ]
        end
      end)
    end)
  rescue
    _ -> []
  end

  @doc """
  The stored messages of `convs` in range nearest to `query` (row ids, best first), at most
  `limit`. `[]` when the query has no words.
  """
  def nearest(convs, query, from, to, limit \\ 40)
  def nearest([], _query, _from, _to, _limit), do: []

  def nearest(convs, query, from, to, limit) do
    vec = Embeddings.embed(query)

    if Embeddings.empty?(vec) do
      []
    else
      lit = Embeddings.literal(vec)

      q =
        from m in Message,
          join: v in "risi_memory_vectors",
          on: v.owner_id == m.id,
          where: m.conversation_id in ^convs and v.model == ^Embeddings.model(),
          order_by: fragment("? <=> (?::text)::vector", v.embedding, ^lit),
          limit: ^limit,
          select: m.id

      q = if from, do: where(q, [m], m.at >= ^from), else: q
      q = if to, do: where(q, [m], m.at < ^to), else: q
      Repo.all(q)
    end
  end

  @doc """
  The items of `convs` (opened), filtered by range (on `at`, else when it was learnt), kinds and
  people ids, ranked by `query` when given; at most `limit`.
  """
  def items(convs, opts \\ [])
  def items([], _opts), do: []

  def items(convs, opts) do
    from = opts[:from]
    to = opts[:to]
    kinds = opts[:kinds] || []
    people = opts[:people_ids] || []
    limit = opts[:limit] || 60

    q =
      from i in Item,
        where: i.conversation_id in ^convs,
        order_by: [desc: i.inserted_at],
        limit: ^limit

    q =
      if from,
        do: where(q, [i], coalesce(i.at, i.inserted_at) >= ^from),
        else: q

    q = if to, do: where(q, [i], coalesce(i.at, i.inserted_at) < ^to), else: q
    q = if kinds != [], do: where(q, [i], i.kind in ^kinds), else: q

    q =
      if people != [],
        do:
          where(
            q,
            [i],
            fragment("? && ?::uuid[]", i.people_ids, type(^people, {:array, Ecto.UUID}))
          ),
        else: q

    q
    |> Repo.all()
    |> Enum.flat_map(fn i ->
      case open_item(i) do
        {:ok, it} -> [it]
        :error -> []
      end
    end)
    |> rank_items(opts[:query])
  end

  defp rank_items(items, q) when is_binary(q) and q != "" do
    v = Embeddings.embed(q)

    if Embeddings.empty?(v),
      do: items,
      else:
        Enum.sort_by(items, fn it ->
          -Embeddings.cosine(v, Embeddings.embed(it.text <> " " <> Enum.join(it.people, " ")))
        end)
  end

  defp rank_items(items, _q), do: items

  @doc """
  When Risi's memory of `convs` starts: the earliest stored (or still buffered) message, or nil
  when it holds nothing.
  """
  def known_since([]), do: nil

  def known_since(convs) do
    stored = Repo.one(from m in Message, where: m.conversation_id in ^convs, select: min(m.at))

    buffered =
      convs
      |> Enum.flat_map(fn c ->
        case RisiMe.Agent.Secretary.text_messages(c, nil, 1) do
          [m | _] -> [at(m.message_id)]
          [] -> []
        end
      end)

    case Enum.reject([stored | buffered], &is_nil/1) do
      [] -> nil
      list -> Enum.min(list, DateTime)
    end
  rescue
    _ -> nil
  end
end
