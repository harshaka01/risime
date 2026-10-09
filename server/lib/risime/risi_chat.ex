defmodule RisiMe.RisiChat do
  @moduledoc """
  The Risi chat (contract v1.25 §25.2, decision 068): one per user, an Official `grp:` with
  `chat_kind: "risi"` and `chat_id` = its own id; members the owner (`admin`) and Risi
  (`member`, `kind: "agent"`). It has no Private tab, no toggle and no member calls.

    * `create/2`: `POST /api/v1/risi/chat` (a `risi_tools` device of the caller; `503
      agent_unavailable` while Risi is off or down or `RISI_TOOLS=off`). `201` with the new group
      in `creating` (the caller's device builds epoch 0 with every `risi_tools` device of the
      owner plus Risi's device), or `200` with the existing one. A partial unique index on the
      owner decides races: the loser gets the winner's chat.
    * `chat_json/2`: its `Chat` (`kind: "risi"`, `private: null`, `can_toggle: false`).
    * `activated/1`: its epoch-0 commit landed.
    * `leave/2`: the owner's leave ends it: both memberships close, Risi's data for it is deleted
      now and again within the hour (`RisiMe.Agent.forget/1`), Risi's MLS state is purged; the
      conversation stays on the phone read-only (hard rule 9).

  Its events, listing and push wake-ups reach only `risi_tools` devices (and Risi's device):
  `RisiMe.Groups.Tabs.risi_only?/1`.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Devices, Groups, Messaging, MLS, Repo, Risi}
  alias RisiMe.Groups.{Group, Member}

  @creating_ttl_s 600

  @doc "True if `id` is a Risi chat."
  def risi_chat?(id), do: RisiMe.Groups.Tabs.risi_chat?(id)

  @doc "The user's current Risi chat (creating or active, not left) as a `Group`, or nil."
  def of_owner(user_id) do
    Repo.one(
      from g in Group,
        join: r in "risi_chats",
        on: r.conversation_id == g.id,
        where: r.owner_id == type(^user_id, :binary_id) and r.state != "left",
        select: g
    )
  end

  @doc "The owner of a Risi chat (any state), or nil."
  def owner(conv) do
    Repo.one(
      from r in "risi_chats",
        where: r.conversation_id == ^conv,
        select: type(r.owner_id, :binary_id)
    )
  end

  @doc "True if the user has an active Risi chat (§25.5 personal tools)."
  def active?(user_id) do
    Repo.exists?(
      from r in "risi_chats",
        where: r.owner_id == type(^user_id, :binary_id) and r.state == "active"
    )
  end

  @doc "The user's active Risi chat id, or nil."
  def active_id(user_id) do
    Repo.one(
      from r in "risi_chats",
        where: r.owner_id == type(^user_id, :binary_id) and r.state == "active",
        select: r.conversation_id
    )
  end

  ## POST /api/v1/risi/chat

  @doc """
  `{:ok, :created | :existing, %{chat, group}}` or `{:error, :invalid_device |
  :agent_unavailable | :mls_unavailable}`.
  """
  def create(me, device_id) do
    with true <- MLS.available?() || {:error, :mls_unavailable},
         true <- Devices.risi_tools_device?(me, device_id) || {:error, :invalid_device},
         true <- (Risi.tools_on?() and Risi.available?()) || {:error, :agent_unavailable} do
      case of_owner(me) do
        %Group{} = g -> {:ok, :existing, reply(me, g)}
        nil -> insert(me)
      end
    end
  end

  defp insert(me) do
    now = DateTime.utc_now()
    id = "grp:" <> Ecto.UUID.generate()

    result =
      Repo.transaction(fn ->
        Repo.insert_all(Group, [
          %{
            id: id,
            created_by: me,
            client_group_id: Ecto.UUID.generate(),
            state: "creating",
            generation: 1,
            created_at: now,
            chat_id: id,
            tab: "official",
            chat_kind: "risi"
          }
        ])

        {n, _} =
          Repo.insert_all(
            "risi_chats",
            [
              %{
                conversation_id: id,
                owner_id: Ecto.UUID.dump!(me),
                state: "creating",
                inserted_at: now
              }
            ],
            on_conflict: :nothing,
            conflict_target: {:unsafe_fragment, "(owner_id) WHERE state <> 'left'"}
          )

        if n == 1 do
          Repo.insert_all(Member, [
            member_row(id, me, "admin", "user", now),
            # The owner first in the member list (members are ordered by insertion).
            member_row(id, Risi.user_id(), "member", "agent", DateTime.add(now, 1, :microsecond))
          ])

          {:ok, _} =
            Oban.insert(
              RisiMe.Workers.GroupTimer.new(%{"kind" => "creating", "group_id" => id},
                schedule_in: @creating_ttl_s
              )
            )

          :created
        else
          Repo.rollback(:existing)
        end
      end)

    case result do
      {:ok, :created} ->
        RisiMe.Groups.Tabs.put(id, "official", "risi")
        {:ok, :created, reply(me, Repo.get!(Group, id))}

      {:error, :existing} ->
        {:ok, :existing, reply(me, of_owner(me))}
    end
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

  defp reply(me, g), do: %{chat: chat_json(me, g), group: Groups.group_json(g, me)}

  ## The Chat object (§25.2)

  @doc "`Chat` of a Risi chat (`chat_reply_risi.json`)."
  def chat_json(_me, %Group{} = g) do
    available? = Risi.available?()

    %{
      chat_id: g.id,
      kind: "risi",
      private: nil,
      official: %{
        state: if(g.state == "active", do: "on", else: "none"),
        conversation_id: g.id,
        changed_by: nil,
        changed_at: nil
      },
      official_ready: available?,
      missing:
        if(available?,
          do: [],
          else: [%{user_id: nil, device_id: nil, reason: "agent_unavailable"}]
        ),
      can_toggle: false
    }
  end

  @doc "`GET /api/v1/chats/{id}` for a Risi chat: only its owner on a `risi_tools` device."
  def show(me, device_id, conv) do
    with true <- Devices.risi_tools_device?(me, device_id) || {:error, :not_found},
         {:ok, g, _m} <- Groups.visible(me, conv),
         true <- Groups.risi_chat?(g) || {:error, :not_found} do
      {:ok, chat_json(me, g)}
    end
  end

  @doc "The caller's Risi chat for `GET /api/v1/chats` (a `risi_tools` device), as a list."
  def list(me, device_id) do
    with true <- Devices.risi_tools_device?(me, device_id),
         %Group{state: "active"} = g <- of_owner(me),
         {:ok, _g, _m} <- Groups.visible(me, g.id) do
      [chat_json(me, g)]
    else
      _ -> []
    end
  end

  ## Epoch 0 and leave

  @doc "Epoch 0 of a Risi chat landed (inside its lock): the chat is active."
  def activated(%Group{id: id}) do
    Repo.update_all(from(r in "risi_chats", where: r.conversation_id == ^id),
      set: [state: "active"]
    )

    []
  end

  @doc """
  `POST /groups/{id}/leave` on a Risi chat, inside the group's lock (`Groups.leave/3`): both
  memberships close (no member is left to commit a removal, so none is asked for), the chat is
  marked left, and the owner gets `group_event` `removed` (targets: the owner and Risi). Returns
  `{:ok, events}`; `after_leave/1` runs after the transaction.
  """
  def leave(me, %Group{} = g) do
    for u <- [me, Risi.user_id()], match?(%Member{state: "active"}, Groups.member(g.id, u)) do
      if Groups.creating?(g), do: Groups.delete_member(g.id, u), else: Groups.mark_removing(g, u)
    end

    Repo.update_all(from(r in "risi_chats", where: r.conversation_id == ^g.id),
      set: [state: "left", left_at: DateTime.utc_now()]
    )

    events =
      if Groups.creating?(g),
        do: [],
        else:
          Groups.group_events(g, "removed", me, [me, Risi.user_id()], epoch: Groups.epoch(g.id))

    {:ok, events}
  end

  @doc """
  After a Risi chat was left: Risi deletes its data for it now and again within the hour (notes
  and facts whose chat is the Risi chat, its buffer rows, derived rows, jobs;
  `RisiMe.Agent.forget/1`) and purges its MLS state for it (best effort, off the caller).
  """
  def after_leave(conv) do
    RisiMe.Agent.forget(conv)

    Repo.delete_all(from f in RisiMe.Agent.Fact, where: f.chat_id == ^conv)

    if RisiMe.Agent.running?() do
      Task.start(fn ->
        case RisiMe.Agent.ConversationSup.ensure(conv) do
          {:ok, pid} -> RisiMe.Agent.Conversation.removed(pid)
          _ -> :ok
        end
      end)
    end

    :ok
  rescue
    e -> Logger.warning("Risi chat cleanup failed: #{inspect(e.__struct__)}")
  catch
    :exit, _ -> :ok
  end

  @doc false
  def publish(events), do: Messaging.publish_batch(events)
end
