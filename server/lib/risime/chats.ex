defmodule RisiMe.Chats do
  @moduledoc """
  Chats with two tabs (contract v1.24 §24, decision 065).

  A chat is its Private anchor conversation (`dm:` or a Private `grp:`; `chat_id` equals that id)
  plus at most one Official `grp:` conversation (`groups.chat_id`, `tab = 'official'`). The
  Official setting lives in `chats` (no row: on, and nothing created yet).

    * `create_official/3`: `POST /api/v1/chats/{chat_id}/official` (§24.2);
    * `official_created/2`: called by the epoch-0 commit of an Official group (§24.2);
    * `chat_json/2`, `show/3`, `list/2`: the `Chat` object (§24.1, §24.8);
    * `toggle/4`: `PATCH /api/v1/chats/{chat_id}` (§24.4).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Devices, Groups, Messaging, MLS, Repo, Risi, Social, TimeUUID}
  alias RisiMe.Groups.{Chat, Group, Member, Ops}

  @creating_ttl_s 600

  ## Resolving a chat

  @doc """
  The chat `chat_id` as seen by `me`: `{:ok, %{chat_id, kind, humans, admins}}` for a participant
  of the DM or an active member of the active Private group; otherwise `{:error, :not_found}`.
  """
  def resolve(me, "dm:" <> _ = chat_id) do
    case MLS.members(chat_id) do
      {:ok, [a, b] = humans} when me in [a, b] ->
        {:ok, %{chat_id: chat_id, kind: "dm", humans: humans, admins: humans}}

      _ ->
        {:error, :not_found}
    end
  end

  def resolve(me, "grp:" <> _ = chat_id) do
    with %Group{tab: "private", state: "active"} <- Groups.get_group(chat_id),
         %Member{state: "active"} <- Groups.member(chat_id, me) do
      humans =
        Repo.all(
          from m in Member,
            where: m.group_id == ^chat_id and m.state == "active" and m.kind == "user",
            order_by: [asc: m.inserted_at],
            select: m.user_id
        )

      {:ok, %{chat_id: chat_id, kind: "group", humans: humans, admins: Groups.admin_ids(chat_id)}}
    else
      _ -> {:error, :not_found}
    end
  end

  def resolve(_me, _chat_id), do: {:error, :not_found}

  @doc "The chat's Official group (any state), or nil."
  def official_group(chat_id),
    do: Repo.one(from g in Group, where: g.chat_id == ^chat_id and g.tab == "official")

  @doc "The `chats` row, or nil (Official on, nothing created yet)."
  def row(chat_id), do: Repo.get(Chat, chat_id)

  @doc "True while the chat's Official setting is off."
  def off?(chat_id), do: match?(%Chat{official: "off"}, row(chat_id))

  ## The Chat object (§24.1)

  @doc "`Chat` as seen by `me` for a resolved chat."
  def chat_json(me, chat) do
    {_ready, missing} = Groups.tabs_readiness(chat.humans)

    build_json(
      me,
      chat,
      row(chat.chat_id),
      official_group(chat.chat_id),
      missing,
      Risi.available?()
    )
  end

  # `missing` may cover more users than the chat's (a batch): only the chat's humans count.
  defp build_json(me, chat, r, og, missing, risi?) do
    humans = MapSet.new(chat.humans)
    missing = Enum.filter(missing, &MapSet.member?(humans, &1.user_id))

    state =
      cond do
        r && r.official == "off" -> "off"
        og && og.state == "active" -> "on"
        true -> "none"
      end

    missing =
      if risi?,
        do: missing,
        else: missing ++ [%{user_id: nil, device_id: nil, reason: "agent_unavailable"}]

    %{
      chat_id: chat.chat_id,
      kind: chat.kind,
      private: %{conversation_id: chat.chat_id},
      official: %{
        state: state,
        conversation_id: if((state != "none" and og) && og.state == "active", do: og.id),
        changed_by: r && r.changed_by,
        changed_at: r && r.changed_at && Messaging.iso(r.changed_at)
      },
      official_ready: missing == [],
      missing: missing,
      can_toggle: chat.kind == "dm" or me in chat.admins
    }
  end

  @doc "`GET /api/v1/chats/{chat_id}` (`tabs` devices only)."
  def show(me, device_id, chat_id) do
    with true <- Devices.tabs_device?(me, device_id) || {:error, :not_found},
         {:ok, chat} <- resolve(me, chat_id),
         do: {:ok, chat_json(me, chat)}
  end

  @doc """
  `GET /api/v1/chats` (`tabs` devices only): one chat per DM with a friend that is e2ee or has an
  Official conversation, and one per active Private group of the caller.
  """
  def list(me, device_id) do
    if Devices.tabs_device?(me, device_id) do
      {:ok, list_chats(me)}
    else
      {:error, :not_found}
    end
  end

  # A fixed number of queries for any number of chats (no per-chat round trips).
  defp list_chats(me) do
    group_ids =
      Repo.all(
        from g in Group,
          join: m in Member,
          on: m.group_id == g.id,
          where:
            m.user_id == ^me and m.state == "active" and g.state == "active" and
              g.tab == "private",
          order_by: [asc: g.created_at],
          select: g.id
      )

    dm_ids = for f <- Social.friend_ids(me), do: Messaging.conversation_id(me, f)

    officials =
      Repo.all(
        from g in Group, where: g.chat_id in ^(dm_ids ++ group_ids) and g.tab == "official"
      )
      |> Map.new(&{&1.chat_id, &1})

    e2ee =
      Repo.all(
        from g in "mls_groups", where: g.conversation_id in ^dm_ids, select: g.conversation_id
      )
      |> MapSet.new()

    dms =
      for conv <- dm_ids,
          MapSet.member?(e2ee, conv) or Map.has_key?(officials, conv),
          {:ok, humans} <- [MLS.members(conv)],
          do: %{chat_id: conv, kind: "dm", humans: humans, admins: humans}

    members =
      Repo.all(
        from m in Member,
          where: m.group_id in ^group_ids and m.state == "active",
          order_by: [asc: m.inserted_at],
          select: {m.group_id, m.user_id, m.kind, m.role}
      )
      |> Enum.group_by(&elem(&1, 0))

    groups =
      for id <- group_ids do
        ms = Map.get(members, id, [])

        %{
          chat_id: id,
          kind: "group",
          humans: for({_, u, "user", _} <- ms, do: u),
          admins: for({_, u, _, "admin"} <- ms, do: u)
        }
      end

    chats = dms ++ groups
    rows = Repo.all(from c in Chat, where: c.chat_id in ^Enum.map(chats, & &1.chat_id))
    rows = Map.new(rows, &{&1.chat_id, &1})
    {_ready, missing} = chats |> Enum.flat_map(& &1.humans) |> Groups.tabs_readiness()
    risi? = Risi.available?()

    for chat <- chats,
        do:
          build_json(
            me,
            chat,
            rows[chat.chat_id],
            officials[chat.chat_id],
            missing,
            risi?
          )
  end

  ## Creation (§24.2)

  @doc """
  `POST /api/v1/chats/{chat_id}/official`. `{:ok, :created | :existing, group_json}` or an error:
  `invalid_device`, `not_found`, `official_off`, `not_e2ee`, `not_friends`, `agent_unavailable`,
  `{:not_ready, missing, :official}`.
  """
  def create_official(me, device_id, chat_id) do
    with true <- MLS.available?() || {:error, :mls_unavailable},
         true <- Devices.tabs_device?(me, device_id) || {:error, :invalid_device},
         {:ok, chat} <- resolve(me, chat_id) do
      og = official_group(chat_id)

      cond do
        off?(chat_id) ->
          {:error, :official_off}

        og != nil ->
          {:ok, :existing, Groups.group_json(og, me)}

        true ->
          with :ok <- dm_ok(chat),
               true <- Risi.available?() || {:error, :agent_unavailable},
               :ok <- ready(chat.humans) do
            insert_official(me, chat)
          end
      end
    end
  end

  # §24.2: a DM chat must be e2ee and the two users friends with no block.
  defp dm_ok(%{kind: "dm", chat_id: id, humans: [a, b]}) do
    cond do
      not MLS.e2ee?(id) -> {:error, :not_e2ee}
      not Social.friends?(a, b) or Social.blocked_between?(a, b) -> {:error, :not_friends}
      true -> :ok
    end
  end

  defp dm_ok(_chat), do: :ok

  @doc "Every user is tabs-ready, else `{:error, {:not_ready, missing, :official}}`."
  def ready(user_ids) do
    case Groups.tabs_readiness(user_ids) do
      {_, []} -> :ok
      {_, missing} -> {:error, {:not_ready, missing, :official}}
    end
  end

  @doc false
  # The new Official group in `creating`; the unique (chat_id, tab) index decides a race: the
  # loser gets the winner's group (`:existing`).
  def insert_official(me, chat) do
    now = DateTime.utc_now()
    id = "grp:" <> Ecto.UUID.generate()

    {:ok, result} =
      Repo.transaction(fn ->
        {n, _} =
          Repo.insert_all(
            Group,
            [
              %{
                id: id,
                created_by: me,
                client_group_id: Ecto.UUID.generate(),
                state: "creating",
                generation: 1,
                created_at: now,
                chat_id: chat.chat_id,
                tab: "official",
                chat_kind: chat.kind
              }
            ],
            on_conflict: :nothing,
            conflict_target: [:chat_id, :tab]
          )

        if n == 1 do
          RisiMe.Groups.Tabs.put(id, "official", chat.kind)

          # §24.1: a 1:1 Official has both users as admins; a group's mirrors the Private roles.
          humans =
            for u <- chat.humans,
                do:
                  member_row(
                    id,
                    u,
                    if(u in chat.admins, do: "admin", else: "member"),
                    "user",
                    now
                  )

          Repo.insert_all(
            Member,
            humans ++ [member_row(id, Risi.user_id(), "member", "agent", now)]
          )

          {:ok, _} =
            Oban.insert(
              RisiMe.Workers.GroupTimer.new(%{"kind" => "creating", "group_id" => id},
                schedule_in: @creating_ttl_s
              )
            )

          {:created, Repo.get!(Group, id)}
        else
          {:existing, official_group(chat.chat_id)}
        end
      end)

    {kind, g} = result
    {:ok, kind, Groups.group_json(g, me)}
  end

  defp member_row(group_id, user_id, role, kind, now),
    do: %{
      group_id: group_id,
      user_id: user_id,
      role: role,
      kind: kind,
      state: "active",
      joined_at: now,
      inserted_at: now
    }

  @doc """
  The epoch-0 commit of an Official group landed (inside its lock): records it on the chat and
  returns the `chat_event` `official_created` for every human member.
  """
  def official_created(%Group{} = g, actor) do
    upsert_row(g.chat_id, g.chat_kind, %{official_conversation_id: g.id})
    chat_events(g.chat_id, "official_created", actor, g.id, human_ids(g.id))
  end

  @doc "Active human members of a group."
  def human_ids(group_id),
    do:
      Repo.all(
        from m in Member,
          where: m.group_id == ^group_id and m.state == "active" and m.kind == "user",
          select: m.user_id
      )

  @doc false
  def upsert_row(chat_id, kind, attrs) do
    row = Map.merge(%{chat_id: chat_id, kind: kind, official: "on"}, attrs)

    Repo.insert_all(Chat, [row],
      on_conflict: {:replace, Map.keys(attrs)},
      conflict_target: [:chat_id]
    )
  end

  @doc """
  §24.8: the stored `chat_event` (`tabs` sockets only) for each user, as
  `RisiMe.Messaging.publish_batch/1` items.
  """
  def chat_events(chat_id, action, actor, official_id, user_ids) do
    data = %{
      "chat_id" => chat_id,
      "action" => action,
      "actor" => actor,
      "official_conversation_id" => official_id,
      "server_ts" => Messaging.iso(DateTime.utc_now())
    }

    for u <- user_ids,
        do: {u, %{event_id: TimeUUID.generate(), kind: "chat_event", data: data}, [push: true]}
  end

  ## Official off and on (§24.4)

  @toggles_per_day 6

  @doc """
  `PATCH /api/v1/chats/{chat_id}` `{"official": "off" | "on"}` → `{:ok, chat_json}`. Either
  person of a 1:1, an admin of a group (`not_admin`); the current state is `200` with no change;
  at most #{@toggles_per_day} changes per chat per day (`rate_limited`).
  """
  def toggle(me, device_id, chat_id, params) do
    with true <- MLS.available?() || {:error, :mls_unavailable},
         true <- Devices.tabs_device?(me, device_id) || {:error, :invalid_device},
         {:ok, dev} <- Ecto.UUID.cast(device_id),
         %{"official" => want} when want in ["on", "off"] <- params,
         {:ok, chat} <- resolve(me, chat_id),
         true <- (chat.kind == "dm" or me in chat.admins) || {:error, :not_admin} do
      current = if off?(chat_id), do: "off", else: "on"

      cond do
        current == want ->
          {:ok, chat_json(me, chat)}

        want == "on" and official_group(chat_id) != nil and not Risi.available?() ->
          {:error, :agent_unavailable}

        RisiMe.RateLimiter.hit(:official_toggle, chat_id, @toggles_per_day, :timer.hours(24)) !=
            :ok ->
          {:error, :rate_limited}

        true ->
          og = official_group(chat_id)
          off_official = want == "off" and og != nil and og.state == "active"

          # §24.4 (S5): Risi's farewell goes out first, while Risi is still an active member and
          # the chat is still on; only then does the off transaction mark it `pending_remove`.
          if off_official, do: RisiMe.Agent.official_off(og.id, me)

          with {:ok, events} <- locked_toggle(me, dev, chat, want) do
            Messaging.publish_batch(events)
            # §24.4: what Risi learned in the chat is deleted at once (buffer rows; S6: facts).
            if off_official, do: RisiMe.Agent.forget(og.id)
            {:ok, chat_json(me, chat)}
          end
      end
    else
      :error -> {:error, :invalid_device}
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  # One per-chat critical section: the chat's lock, and the Official group's lock (shared with
  # its commits) when it exists.
  defp locked_toggle(me, dev, chat, want) do
    og = official_group(chat.chat_id)

    Repo.transaction(fn ->
      Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["chat:" <> chat.chat_id])

      if og,
        do: Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["mls:" <> og.id])

      og = og && Repo.get(Group, og.id)
      now = DateTime.utc_now()
      upsert_row(chat.chat_id, chat.kind, %{official: want, changed_by: me, changed_at: now})

      if og && og.state == "active" do
        if want == "off", do: agent_out(og, me, dev), else: agent_in(og, me, dev)
      end

      chat_events(chat.chat_id, "official_" <> want, me, og && og.id, chat.humans)
    end)
  end

  # Off: the agent's membership becomes `pending_remove` at once (no live push or replay of the
  # chat reaches it from now on) and a member-committable `remove` op for it is created, the
  # toggler's device first. A not-yet-committed `add` (Official turned on again) is cancelled.
  defp agent_out(og, me, dev) do
    for %Member{kind: "agent"} = m <- Groups.members(og.id) do
      case m.state do
        "active" ->
          Groups.mark_removing(og, m.user_id)
          Ops.create(og, "remove", me, %{user_ids: [m.user_id]}, first(og, me, dev))

        "pending_add" ->
          Ops.drop_user(og, m.user_id)
          Groups.delete_member(og.id, m.user_id)

        _ ->
          :ok
      end
    end
  end

  # On again: the same Official conversation gets the agent back: a pending removal that hasn't
  # landed is cancelled (it is still a leaf), otherwise an `add` op (completed by an admin
  # device; in a 1:1 both users are admins).
  defp agent_in(og, me, dev) do
    risi = Risi.user_id()

    case Groups.member(og.id, risi) do
      %Member{state: "pending_remove"} ->
        for %RisiMe.Groups.Op{type: "remove"} = op <- Ops.list(og.id),
            risi in (op.payload["user_ids"] || []),
            do: Repo.delete!(op)

        if MapSet.new(Groups.in_group(og.id), &elem(&1, 0)) |> MapSet.member?(risi) do
          Repo.update_all(
            from(m in Member, where: m.group_id == ^og.id and m.user_id == ^risi),
            set: [state: "active"]
          )

          RisiMe.Groups.Membership.open(og.id, [risi], DateTime.utc_now())
        else
          Groups.delete_member(og.id, risi)
          add_agent(og, me, dev, risi)
        end

      nil ->
        add_agent(og, me, dev, risi)

      _ ->
        :ok
    end
  end

  defp add_agent(og, me, dev, risi) do
    Repo.insert_all(Member, [
      %{
        group_id: og.id,
        user_id: risi,
        role: "member",
        kind: "agent",
        state: "pending_add",
        joined_at: nil,
        inserted_at: DateTime.utc_now()
      }
    ])

    first = if me in Groups.admin_ids(og.id), do: first(og, me, dev), else: :auto
    Ops.create(og, "add", me, %{user_ids: [risi]}, first)
  end

  defp first(og, me, dev),
    do: if(MapSet.member?(Groups.in_group(og.id), {me, dev}), do: {me, dev}, else: :auto)

  ## Sends (§24.1, §24.8)

  @doc """
  `msg:send` to an Official conversation: `official_off` while the chat is off; for a 1:1
  Official, `not_friends` while the two aren't friends or one blocks the other. `:ok` otherwise
  (and for every other conversation).
  """
  def send_check(conv) do
    case Groups.get_group(conv) do
      %Group{tab: "official"} = g ->
        cond do
          off?(g.chat_id) ->
            {:error, :official_off}

          g.chat_kind == "dm" ->
            case MLS.members(g.chat_id) do
              {:ok, [a, b]} ->
                if Social.friends?(a, b) and not Social.blocked_between?(a, b),
                  do: :ok,
                  else: {:error, :not_friends}

              _ ->
                :ok
            end

          true ->
            :ok
        end

      _ ->
        :ok
    end
  end
end
