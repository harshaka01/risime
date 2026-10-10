defmodule RisiMe.Agent.Skills do
  @moduledoc """
  Risi skills (contract v1.26 §26, decision 069; server S14): **the permission layer every tool
  runs under.**

  * **Registry** (`registry/0`, §26.1): `alarm`, `reminders`, `calendar`,
    `scheduled_messages`, `email` (not available yet), in that order, the same for everyone.
  * **Per-user state** (`risi_skills`): `off` (no row; the default) | `ask` | `allowed`; and
    **per device** the Android permission it last reported (`risi_skill_devices`, deleted with
    the device). `GET`/`PATCH /api/v1/risi/skills` (`list/2`, `patch/3`).
  * **Who is gated** (§26.9): a user any of whose devices advertises `risi_skills` while
    `RISI_SKILLS=on` and the skills can run (`on?/0`: the switch **and** `RISI_MEMORY_KEY`). A
    user with only v1.25 devices keeps §25 exactly (confirm-always calendar and reminders, none
    of the v1.26 tools).
  * **`authorize/3` gates** (`allows?/2`, §26.8): a skill tool is offered only while the skill
    is `ask` or `allowed`; a phone tool only on a `risi_skills` asking device whose reported
    permission isn't `denied` or `unsupported`. Otherwise the skill goes into the `need_skill`
    enum (`needed/1`); choosing it ends the turn with a `skill_needed` card (`needed_card/5`).
  * **Allowed vs Ask** (`write_mode/3`, §26.3): only `set_alarm`, `calendar_add` and a
    `set_reminder` for the asker alone run without a card when the skill is `allowed`; a
    message to others, a conversation reminder and a cancel by request always confirm.
  * **Revoke** (§26.5): `PATCH` `state: "off"` voids the skill's open cards (a later confirm
    gets `skill_needed`), and an `ok` arriving after it is ignored and logs nothing;
    `cancel_pending` also cancels pending reminders.
  * **Activity log** (§26.4, `risi_skill_activity`): written when an action succeeds or a state
    changes; `summary`, the undo hint, token and data **sealed with `RISI_MEMORY_KEY`**; kept
    90 days; never in the learning log, `risi_turn_steps`, server logs or a push. Undo: `server`
    (a reminder, at once, 200) or `client` (a `risi_tool_call` with `undo_entry_id` to the
    entry's device, 202; the result, or 15 s, settles it).
  """
  import Ecto.Query

  alias RisiMe.Agent.{Audience, Out, Seal, ToolCalls}
  alias RisiMe.{Devices, Messaging, Repo}

  ## The registry (§26.1)

  @registry [
    %{
      id: "alarm",
      kind: "builtin",
      title: "Alarm",
      description: "Sets alarms in your phone's Clock app when you ask Risi.",
      can: ["Set a one-time or repeating alarm on the phone you asked from"],
      cannot: [
        "Delete or change an alarm (you remove it in Clock)",
        "Set an alarm on anyone else's phone"
      ],
      permissions: [
        %{
          scope: "android",
          name: "com.android.alarm.permission.SET_ALARM",
          label: "Set alarms in your Clock app",
          runtime: false
        }
      ],
      tools: ["set_alarm"],
      where: "phone",
      modes: ["ask", "allowed"],
      undo: "manual",
      available: true,
      noun: "your alarms"
    },
    %{
      id: "reminders",
      kind: "builtin",
      title: "Reminders",
      description: "Reminds you, or people who tap Me too, at a time you choose.",
      can: [
        "Remind you with a notification and a message in the chat",
        "Let others in an Official chat tap Me too"
      ],
      cannot: ["Remind anyone who didn't tap Me too", "Repeat a reminder"],
      permissions: [
        %{
          scope: "risime",
          name: "risi.reminders",
          label: "Post reminder messages in your chats with Risi",
          runtime: false
        },
        %{
          scope: "android",
          name: "android.permission.POST_NOTIFICATIONS",
          label: "Show notifications",
          runtime: true
        }
      ],
      tools: ["set_reminder"],
      where: "server",
      modes: ["ask", "allowed"],
      undo: "until_fired",
      available: true,
      noun: "your reminders"
    },
    %{
      id: "calendar",
      kind: "builtin",
      title: "Calendar",
      description: "Checks when you're free and adds events to your phone's calendar.",
      can: [
        "See when you're busy (never titles or attendees)",
        "Add events you agree to",
        "Remove events it added"
      ],
      cannot: [
        "Read event titles, attendees or places",
        "Change or delete events it didn't add",
        "See anyone else's calendar"
      ],
      permissions: [
        %{
          scope: "android",
          name: "android.permission.READ_CALENDAR",
          label: "See your calendar's busy times",
          runtime: true
        },
        %{
          scope: "android",
          name: "android.permission.WRITE_CALENDAR",
          label: "Add events to your calendar",
          runtime: true
        }
      ],
      tools: ["calendar_check", "calendar_add", "calendar_remove"],
      where: "phone",
      modes: ["ask", "allowed"],
      undo: "full",
      available: true,
      noun: "your calendar"
    },
    %{
      id: "scheduled_messages",
      kind: "builtin",
      title: "Scheduled messages",
      description: "Sends a message you wrote, from your phone, at the time you choose.",
      can: ["Schedule a message, once or every day", "Cancel a scheduled message"],
      cannot: [
        "Send anything without your confirm",
        "Send from the server (your phone sends it, encrypted at that moment)"
      ],
      permissions: [
        %{
          scope: "android",
          name: "android.permission.SCHEDULE_EXACT_ALARM",
          label: "Send at the exact time (Alarms & reminders)",
          runtime: true
        }
      ],
      tools: ["schedule_message", "cancel_scheduled"],
      where: "phone",
      modes: ["ask"],
      undo: "until_sent",
      available: true,
      noun: "scheduled messages"
    },
    %{
      id: "email",
      kind: "builtin",
      title: "Email",
      description: "Reads your email when you ask (Gmail or Outlook). Coming later.",
      can: ["Read and search your email (later)"],
      cannot: ["Send email without a full-draft confirm", "Delete email"],
      permissions: [
        %{
          scope: "oauth",
          name: "https://www.googleapis.com/auth/gmail.readonly",
          label: "Read your Gmail",
          runtime: false
        },
        %{scope: "oauth", name: "Mail.Read", label: "Read your Outlook mail", runtime: false}
      ],
      tools: [],
      where: "server",
      modes: ["ask"],
      undo: "none",
      available: false,
      noun: "your email"
    }
  ]

  @ids Enum.map(@registry, & &1.id)
  @perms ~w(granted denied not_asked not_needed unsupported unknown)

  @doc "The registry, in order (§26.1)."
  def registry, do: @registry

  @doc "A skill by id (or nil)."
  def skill(id), do: Enum.find(@registry, &(&1.id == id))

  @doc "The skill a tool runs under (or nil)."
  def skill_of(tool), do: Enum.find_value(@registry, &(tool in &1.tools && &1.id))

  ## Switch and health (§26.9)

  @doc "True while `RISI_SKILLS=on` and `RISI_MEMORY_KEY` is set (the skills can run)."
  def on?, do: RisiMe.Risi.skills_switch?() and match?({:ok, _}, Seal.memory())

  @doc """
  `/health` `checks.risi_skills`: `"off"` | `"ok"` | `"unavailable: <reason>"` (names only,
  never key material). A missing `RISI_MEMORY_KEY` makes the skills unavailable, never the boot.
  """
  def health do
    cond do
      not RisiMe.Risi.skills_switch?() -> "off"
      Seal.memory() == :error -> "unavailable: missing_key RISI_MEMORY_KEY (base64, 32 bytes)"
      true -> "ok"
    end
  end

  @doc "True if the user is gated by skills (§26.9, see the module doc)."
  def gated?(user_id), do: on?() and Devices.any_risi_skills?(user_id)

  @doc "True if `device_id` is a `risi_skills` device of the user (§26.9)."
  def device?(user_id, device_id), do: on?() and Devices.risi_skills_device?(user_id, device_id)

  ## State

  defmodule State do
    @moduledoc false
    use Ecto.Schema

    @primary_key false
    schema "risi_skills" do
      field :user_id, :binary_id, primary_key: true
      field :skill_id, :string, primary_key: true
      field :state, :string
      field :ever_on, :boolean, default: false
      field :changed_at, :utc_datetime_usec
    end
  end

  defmodule DevicePermission do
    @moduledoc false
    use Ecto.Schema

    @primary_key false
    schema "risi_skill_devices" do
      field :device_ref, :binary_id, primary_key: true
      field :skill_id, :string, primary_key: true
      field :user_id, :binary_id
      field :device_id, :binary_id
      field :permission, :string
      field :reported_at, :utc_datetime_usec
    end
  end

  @doc "The user's state of a skill: `off` | `ask` | `allowed`."
  def state(user_id, skill_id) do
    case Repo.get_by(State, user_id: user_id, skill_id: skill_id) do
      %State{state: s} -> s
      nil -> "off"
    end
  end

  defp states(user_id),
    do: Repo.all(from s in State, where: s.user_id == ^user_id) |> Map.new(&{&1.skill_id, &1})

  defp permissions(user_id, device_id) do
    case Devices.get(user_id, device_id) do
      nil ->
        nil

      d ->
        Repo.all(from p in DevicePermission, where: p.device_ref == ^d.id)
        |> Map.new(&{&1.skill_id, &1})
        |> Map.put(:__device, d)
    end
  end

  defp permission(user_id, device_id, skill_id) do
    case permissions(user_id, device_id) do
      nil -> nil
      ps -> client_permission(skill(skill_id), ps)
    end
  end

  # The device's last report, or the default before any (an alarm needs no runtime permission).
  defp client_permission(skill, ps) do
    case ps[skill.id] do
      %DevicePermission{permission: p} -> p
      nil -> if Enum.any?(skill.permissions, & &1.runtime), do: "unknown", else: "not_needed"
    end
  end

  ## Authorisation (§26.8)

  @doc """
  The skill gates of `authorize/3` for `tool` in `ctx` (`asker`, `device_id`): true when the
  tool may be offered (and run) here. Tools without a skill keep their §25 rules.
  """
  def allows?(tool, ctx) do
    case Map.get(tool, :skill) do
      nil ->
        true

      skill_id ->
        cond do
          ctx_gated?(ctx) ->
            state(ctx.asker, skill_id) in ~w(ask allowed) and
              (tool.where != :client or phone_ok?(ctx, skill_id))

          # §26.9: a user with only v1.25 devices gets none of the new tools.
          ToolCalls.v126?(tool.name) ->
            false

          true ->
            true
        end
    end
  end

  defp ctx_gated?(%{asker: asker} = ctx),
    do: Map.get_lazy(ctx, :gated?, fn -> gated?(asker) end)

  defp phone_ok?(ctx, skill_id) do
    Devices.risi_skills_device?(ctx.asker, ctx.device_id) and
      permission(ctx.asker, ctx.device_id, skill_id) not in ~w(denied unsupported)
  end

  @doc """
  The `need_skill` enum (§26.5): the skills that are off, unavailable, or without the phone
  permission for this asker. `[]` for a user who isn't gated.
  """
  def needed(ctx) do
    if ctx_gated?(ctx), do: for(s <- @registry, reason(ctx, s), do: s.id), else: []
  end

  @doc "Why `skill_id` is missing for this asker (`off` | `no_permission` | `unavailable`), or nil."
  def missing_reason(ctx, skill_id) do
    case skill(skill_id) do
      nil -> nil
      s -> if ctx_gated?(ctx), do: reason(ctx, s)
    end
  end

  defp reason(ctx, s) do
    cond do
      not s.available -> "unavailable"
      state(ctx.asker, s.id) == "off" -> "off"
      s.where == "phone" and not phone_ok?(ctx, s.id) -> "no_permission"
      true -> nil
    end
  end

  @doc "The `skill_needed` card (§26.5) as `{body, risi}`."
  def needed_card(user_id, skill_id, ctx_or_nil, request_id, turn_id, call_ref \\ nil) do
    s = skill(skill_id)

    reason =
      if ctx_or_nil,
        do: reason(ctx_or_nil, s) || "off",
        else: if(s.available, do: "off", else: "unavailable")

    was_on = match?(%State{ever_on: true}, Repo.get_by(State, user_id: user_id, skill_id: s.id))

    body =
      case reason do
        "unavailable" ->
          "#{s.title} is coming later."

        "no_permission" ->
          "#{s.title} permission is off on this phone. Turn it on in Settings → Risi skills."

        _ when was_on ->
          "I no longer have access to #{s.noun}. Turn it on in Settings → Risi skills."

        _ ->
          "I don't have access to #{s.noun} yet. Turn it on in Settings → Risi skills."
      end

    {body,
     %{
       "kind" => "skill_needed",
       "request_id" => request_id,
       "skill_id" => s.id,
       "reason" => reason,
       "was_on" => was_on,
       "buttons" => ["open_skills"],
       "turn_ref" => turn_id,
       "call_ref" => call_ref,
       "notify" => [user_id]
     }}
  end

  @doc "A turn chose `need_skill`: the card goes to the asker's Risi chat (§26.5). Oban result."
  def need_skill(ctx, skill_id, call_ref) do
    {body, risi} =
      needed_card(ctx.asker, skill_id, ctx, ctx.request_id, ctx.turn_id, call_ref)

    Audience.deliver(ctx, body, risi, true)
  end

  ## Writes (§26.3, §26.5)

  @allowable ~w(set_alarm calendar_add set_reminder)

  @doc "`:allowed` (no card, the asker's own phone or account only) or `:ask` (§26.3)."
  def write_mode(ctx, tool, card) do
    with skill_id when is_binary(skill_id) <- Map.get(tool, :skill),
         true <- tool.name in @allowable,
         true <- tool.name != "set_reminder" or get_in(card, [:args, "audience"]) == "me",
         true <- ctx_gated?(ctx),
         "allowed" <- state(ctx.asker, skill_id) do
      :allowed
    else
      _ -> :ask
    end
  end

  @doc "The skill's gate when a confirmed write runs (§26.5): `:ok` or `{:error, :off}`."
  def confirm_gate(%{skill_id: nil}), do: :ok

  def confirm_gate(w) do
    if gated?(w.user_id) and state(w.user_id, w.skill_id) == "off",
      do: {:error, :off},
      else: :ok
  end

  @doc "A confirm of a void card (revoked skill) gets `skill_needed` in the Risi chat."
  def void_reply(%{skill_id: skill_id} = w) when is_binary(skill_id) do
    if gated?(w.user_id) do
      {body, risi} = needed_card(w.user_id, skill_id, nil, w.request_id, w.turn_id)
      post_personal(w.user_id, w.card_conversation_id, body, risi)
    end

    :ok
  end

  def void_reply(_w), do: :ok

  defp post_personal(user_id, fallback, body, risi) do
    case Out.post(RisiMe.RisiChat.active_id(user_id) || fallback, body, risi) do
      {:ok, _} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  @doc """
  A client write's `ok` for a gated asker: the activity entry and the `skill_done` card in the
  Risi chat (§26.5). `:ignored` when the skill was revoked meanwhile (nothing logged); nil for
  an asker who isn't gated (the caller posts the §25.4 `answer`).
  """
  def client_done(w, args, device, result) do
    cond do
      not gated?(w.user_id) ->
        nil

      w.skill_id && state(w.user_id, w.skill_id) == "off" ->
        :ignored

      true ->
        {action, summary, opts} = describe(w, args, result)

        e =
          log!(
            w.user_id,
            w.skill_id,
            action,
            summary,
            Keyword.merge(opts,
              via: w.via || "confirm",
              conversation_id: w.conversation_id,
              device_id: device
            )
          )

        # A cancel by request settles the schedule's own entry (nothing left to undo).
        if w.tool == "cancel_scheduled" and args["entry_id"], do: settle(args["entry_id"])

        # P0 2026-10-09: "Added to your Google Calendar: Interview with Shenika · Mon 12 Oct,
        # 2–3 PM" (the calendar's name from the phone's result).
        body =
          if w.tool == "calendar_add",
            do:
              RisiMe.Agent.ClientTools.added_text(
                args,
                result,
                RisiMe.Agent.Clock.user_tz(w.user_id)
              )

        done_card(w, e, body, RisiMe.Agent.ClientTools.added_event(w, result))
        :ok
    end
  end

  # What a client write did, for its entry (never a scheduled message's text, §26.4).
  defp describe(%{tool: "calendar_add"} = w, args, _result) do
    {"calendar_added", "Added '#{args["title"]}' to calendar",
     undo_kind: "client",
     undo_until: DateTime.add(DateTime.utc_now(), 30 * 86_400, :second),
     data: %{"target_write_id" => w.write_id, "title" => args["title"]}}
  end

  defp describe(%{tool: "set_alarm"}, args, _result) do
    wire = args["wire"]
    label = wire["label"] || ""
    summary = "Set an alarm for #{wire["time"]}" <> if(label == "", do: "", else: ", '#{label}'")
    {"alarm_set", summary, undo_kind: "manual", hint: "Open Clock to remove it"}
  end

  defp describe(%{tool: "schedule_message"}, args, result) do
    wire = args["wire"]
    to = args["to_name"] || "a group"

    {"message_scheduled", "Scheduled a message to #{to}, #{args["when_text"]}",
     undo_kind: "client",
     undo_until: if(wire["repeat"] == "daily", do: nil, else: parse_ts(wire["at"])),
     target_conversation_id: wire["conversation_id"],
     data: %{"schedule_id" => result["schedule_id"], "to_name" => to}}
  end

  defp describe(%{tool: "cancel_scheduled"}, args, _result) do
    {"scheduled_cancelled", "Cancelled a scheduled message to #{args["to_name"] || "a chat"}",
     undo_kind: "none", target_conversation_id: args["target_conversation_id"]}
  end

  defp describe(w, _args, _result), do: {w.tool, "Done", undo_kind: "none"}

  defp parse_ts(nil), do: nil

  defp parse_ts(s) do
    case DateTime.from_iso8601(s) do
      {:ok, dt, _} -> dt
      _ -> nil
    end
  end

  defp done_card(w, e, body, extra) do
    j = entry_json(e)

    body =
      case j["action"] do
        _ when is_binary(body) -> body
        "alarm_set" -> j["summary"] <> ". To remove it, open Clock."
        "calendar_added" -> String.replace(j["summary"], "to calendar", "to your calendar") <> "."
        _ -> j["summary"] <> "."
      end

    post_personal(
      w.user_id,
      w.card_conversation_id,
      body,
      Map.merge(extra, %{
        "kind" => "skill_done",
        "request_id" => w.request_id,
        "skill_id" => w.skill_id,
        "entry_id" => j["entry_id"],
        "action" => j["action"],
        "summary" => j["summary"],
        "via" => j["via"],
        "undo" => j["undo"],
        "undo_token" => j["undo_token"],
        "turn_ref" => w.turn_id,
        "notify" => [w.user_id]
      })
    )
  end

  ## The activity log (§26.4)

  defmodule Entry do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:entry_id, :binary_id, autogenerate: false}
    schema "risi_skill_activity" do
      field :user_id, :binary_id
      field :skill_id, :string
      field :action, :string
      field :via, :string
      field :conversation_id, :string
      field :target_conversation_id, :string
      field :device_id, :binary_id
      field :undo_kind, :string
      field :undo_state, :string
      field :undo_until, :utc_datetime_usec
      field :sealed, :binary
      field :at, :utc_datetime_usec
    end
  end

  @keep_s 90 * 86_400

  @doc """
  Writes an entry (an action that succeeded, or a state change). `opts`: `via`,
  `conversation_id`, `target_conversation_id`, `device_id`, `undo_kind` (default `none`),
  `undo_until`, `hint`, `data` (the undo's own data, sealed).
  """
  def log!(user_id, skill_id, action, summary, opts) do
    {:ok, key} = Seal.memory()
    id = Ecto.UUID.generate()
    kind = Keyword.get(opts, :undo_kind, "none")
    state = if kind in ~w(server client), do: "available"

    sealed = %{
      "summary" => summary,
      "hint" => opts[:hint],
      "token" => if(state, do: token()),
      "data" => Keyword.get(opts, :data, %{})
    }

    Repo.insert!(%Entry{
      entry_id: id,
      user_id: user_id,
      skill_id: skill_id,
      action: action,
      via: Keyword.fetch!(opts, :via),
      conversation_id: opts[:conversation_id],
      target_conversation_id: opts[:target_conversation_id],
      device_id: opts[:device_id],
      undo_kind: kind,
      undo_state: state,
      undo_until: opts[:undo_until] && RisiMe.Agent.Clock.usec(opts[:undo_until]),
      sealed: Seal.seal(key, aad(id), sealed),
      at: DateTime.utc_now()
    })
  end

  defp aad(id), do: "risi_skill_activity:" <> id

  defp token, do: "u1." <> Base.url_encode64(:crypto.strong_rand_bytes(18), padding: false)

  defp open(%Entry{} = e) do
    with {:ok, key} <- Seal.memory(),
         {:ok, m} <- Seal.open(key, aad(e.entry_id), e.sealed) do
      {:ok, m}
    else
      _ -> :error
    end
  end

  defp reseal!(%Entry{} = e, sealed, changes) do
    {:ok, key} = Seal.memory()

    e
    |> Ecto.Changeset.change(
      Keyword.put(changes, :sealed, Seal.seal(key, aad(e.entry_id), sealed))
    )
    |> Repo.update!()
  end

  # The undo state as shown now: an available undo past `until` has expired.
  defp shown_state(%Entry{undo_state: s, undo_until: until}, now)
       when s in ~w(available failed) do
    if until && DateTime.compare(now, until) == :gt, do: "expired", else: s
  end

  defp shown_state(%Entry{undo_state: s}, _now), do: s

  @doc "An entry as its wire map (`Entry`, §26.4)."
  def entry_json(%Entry{} = e, now \\ RisiMe.Agent.Clock.now()) do
    sealed =
      case open(e) do
        {:ok, m} -> m
        :error -> %{"summary" => "Activity", "hint" => nil, "token" => nil}
      end

    state = shown_state(e, now)

    %{
      "entry_id" => e.entry_id,
      "skill_id" => e.skill_id,
      "action" => e.action,
      "summary" => sealed["summary"],
      "at" => Messaging.iso(e.at),
      "via" => e.via,
      "conversation_id" => e.conversation_id,
      "target_conversation_id" => e.target_conversation_id,
      "device_id" => e.device_id,
      "undo" => %{
        "kind" => e.undo_kind,
        "state" => state,
        "until" => e.undo_until && Messaging.iso(e.undo_until),
        "hint" => sealed["hint"]
      },
      "undo_token" => if(state in ~w(available failed), do: sealed["token"])
    }
  end

  @doc "`GET …/skills/{id}/activity` (newest first): `{:ok, %{entries, has_more}}`."
  def activity(user_id, skill_id, params) do
    with :ok <- known(skill_id),
         {:ok, limit} <- limit(params["limit"]),
         {:ok, q} <- before(user_id, skill_id, params["before"]) do
      rows = Repo.all(from e in q, order_by: [desc: e.at, desc: e.entry_id], limit: ^(limit + 1))
      now = RisiMe.Agent.Clock.now()

      {:ok,
       %{
         "entries" => rows |> Enum.take(limit) |> Enum.map(&entry_json(&1, now)),
         "has_more" => length(rows) > limit
       }}
    end
  end

  defp known(skill_id), do: if(skill_id in @ids, do: :ok, else: {:error, :not_found})

  defp limit(nil), do: {:ok, 50}

  defp limit(s) when is_binary(s) do
    case Integer.parse(s) do
      {n, ""} when n in 1..100 -> {:ok, n}
      _ -> {:error, :bad_request}
    end
  end

  defp limit(_), do: {:error, :bad_request}

  defp before(user_id, skill_id, nil),
    do: {:ok, from(e in Entry, where: e.user_id == ^user_id and e.skill_id == ^skill_id)}

  defp before(user_id, skill_id, id) do
    with {:ok, id} <- Ecto.UUID.cast(id),
         %Entry{user_id: ^user_id, skill_id: ^skill_id} = b <- Repo.get(Entry, id) do
      {:ok,
       from(e in Entry,
         where:
           e.user_id == ^user_id and e.skill_id == ^skill_id and
             (e.at < ^b.at or (e.at == ^b.at and e.entry_id < ^b.entry_id))
       )}
    else
      _ -> {:error, :bad_request}
    end
  end

  @doc "`DELETE …/skills/{id}/activity` (\"Clear activity\")."
  def clear(user_id, skill_id) do
    with :ok <- known(skill_id) do
      Repo.delete_all(from e in Entry, where: e.user_id == ^user_id and e.skill_id == ^skill_id)
      :ok
    end
  end

  @doc "Deletes the entries of requests made in `conv` (a Risi chat that was left, §25.2)."
  def forget_conversation(conv) do
    Repo.delete_all(from e in Entry, where: e.conversation_id == ^conv)
    :ok
  end

  @doc "Deletes entries older than 90 days."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@keep_s, :second)
    Repo.delete_all(from e in Entry, where: e.at < ^cutoff)
    :ok
  end

  @doc """
  The user's pending scheduled messages whose recipient matches `to` (any when `to` is
  blank), newest first: `[%{entry_id, schedule_id, to_name, target_conversation_id, at,
  summary_when}]` (from the sealed activity; never the text).
  """
  def scheduled(user_id, to) do
    q = (to || "") |> String.trim() |> String.downcase()
    now = DateTime.utc_now()

    Repo.all(
      from e in Entry,
        where:
          e.user_id == ^user_id and e.skill_id == "scheduled_messages" and
            e.action == "message_scheduled" and e.undo_state in ["available", "failed"],
        order_by: [desc: e.at]
    )
    |> Enum.filter(&(shown_state(&1, now) in ~w(available failed)))
    |> Enum.flat_map(fn e ->
      with {:ok, %{"data" => %{"schedule_id" => sid} = d, "summary" => summary}} <- open(e),
           true <- q == "" or String.downcase(d["to_name"] || "a group") == q do
        [
          %{
            entry_id: e.entry_id,
            schedule_id: sid,
            to_name: d["to_name"],
            target_conversation_id: e.target_conversation_id,
            at: e.undo_until || e.at,
            summary_when: summary |> String.split(", ", parts: 2) |> List.last()
          }
        ]
      else
        _ -> []
      end
    end)
  end

  defp settle(entry_id) do
    with %Entry{} = e <- Repo.get(Entry, entry_id),
         {:ok, sealed} <- open(e) do
      reseal!(e, Map.put(sealed, "token", nil), undo_state: "done")
    end
  end

  ## Undo (§26.4)

  @doc """
  `POST …/skills/{id}/activity/{entry_id}/undo` with `{"undo_token"}`: `{:ok, 200 | 202,
  entry}` or `{:error, :not_found | :undo_unavailable | :bad_request}`.
  """
  def undo(user_id, skill_id, entry_id, params) do
    with :ok <- known(skill_id),
         {:ok, token} <- undo_token(params),
         {:ok, id} <- Ecto.UUID.cast(entry_id) |> or_not_found(),
         %Entry{user_id: ^user_id, skill_id: ^skill_id} = e <-
           Repo.get(Entry, id) || {:error, :not_found},
         {:ok, sealed} <- open(e) |> or_unavailable(),
         true <-
           (shown_state(e, RisiMe.Agent.Clock.now()) in ~w(available failed) and
              is_binary(sealed["token"]) and
              Plug.Crypto.secure_compare(sealed["token"], token)) ||
             {:error, :undo_unavailable} do
      case e.undo_kind do
        "server" -> server_undo(e, sealed)
        "client" -> client_undo(e, sealed)
        _ -> {:error, :undo_unavailable}
      end
    else
      %Entry{} -> {:error, :not_found}
      {:error, _} = err -> err
      _ -> {:error, :not_found}
    end
  end

  defp undo_token(%{"undo_token" => t} = p) when is_binary(t) and map_size(p) == 1,
    do: {:ok, t}

  defp undo_token(_), do: {:error, :bad_request}

  defp or_not_found({:ok, v}), do: {:ok, v}
  defp or_not_found(_), do: {:error, :not_found}

  defp or_unavailable({:ok, v}), do: {:ok, v}
  defp or_unavailable(_), do: {:error, :undo_unavailable}

  # §26.4 `server`: Risi undoes it itself, at once (a reminder that hasn't fired).
  defp server_undo(e, sealed) do
    case RisiMe.Agent.Reminders.undo(e.user_id, sealed["data"]) do
      :ok ->
        e = reseal!(e, Map.put(sealed, "token", nil), undo_state: "done")
        followup!(e, sealed)
        {:ok, 200, entry_json(e)}

      _ ->
        {:error, :undo_unavailable}
    end
  end

  # §26.4 `client`: a tool call to the entry's device only (no write_id, null turn).
  defp client_undo(e, sealed) do
    with true <-
           Devices.risi_skills_device?(e.user_id, e.device_id) || {:error, :undo_unavailable},
         {:ok, {tool, args}} <- undo_call(e, sealed["data"] || %{}),
         {1, _} <-
           Repo.update_all(
             from(x in Entry,
               where: x.entry_id == ^e.entry_id and x.undo_state in ~w(available failed)
             ),
             set: [undo_state: "pending"]
           ) do
      e = reseal!(Repo.get!(Entry, e.entry_id), Map.put(sealed, "token", nil), [])

      {:ok, call} =
        ToolCalls.start(%{
          user: e.user_id,
          device: e.device_id,
          tool: tool,
          args: args,
          undo_entry_id: e.entry_id
        })

      %{"kind" => "undo_timeout", "entry_id" => e.entry_id, "tool_call_id" => call.tool_call_id}
      |> RisiMe.Workers.Risi.new(
        queue: :risi_timers,
        schedule_in: max(div(ToolCalls.deadline_ms(), 1000), 1)
      )
      |> Oban.insert!()

      {:ok, 202, entry_json(e)}
    else
      {0, _} -> {:error, :undo_unavailable}
      {:error, _} = err -> err
      _ -> {:error, :undo_unavailable}
    end
  end

  defp undo_call(%{action: "calendar_added"}, %{"target_write_id" => wid}),
    do: {:ok, {"calendar_remove", %{"target_write_id" => wid}}}

  defp undo_call(%{action: "message_scheduled"}, %{"schedule_id" => sid}) when is_binary(sid),
    do: {:ok, {"cancel_scheduled", %{"write_id" => nil, "schedule_id" => sid}}}

  defp undo_call(_e, _data), do: {:error, :undo_unavailable}

  @doc "The result of an undo tool call (`RisiMe.Agent.ToolCalls`): settles the entry."
  def undo_result(call, status, result) do
    with %Entry{undo_state: "pending"} = e <- Repo.get(Entry, call.undo_entry_id),
         {:ok, sealed} <- open(e) do
      undone? = status == "ok" and (result["removed"] == true or result["cancelled"] == true)

      if undone? do
        e = reseal!(e, sealed, undo_state: "done")
        followup!(e, sealed)
      else
        reseal!(e, Map.put(sealed, "token", token()), undo_state: "failed")
      end
    end

    :ok
  end

  @doc "An undo whose phone didn't answer in 15 s: `failed`, with a new token (retry)."
  def undo_timeout(entry_id, tool_call_id) do
    with %Entry{undo_state: "pending"} = e <- Repo.get(Entry, entry_id),
         {:ok, sealed} <- open(e) do
      ToolCalls.expire(tool_call_id)
      reseal!(e, Map.put(sealed, "token", token()), undo_state: "failed")
    end

    :ok
  end

  # The entry an undo writes (`via: "undo"`).
  defp followup!(e, sealed) do
    {action, summary} =
      case e.action do
        "calendar_added" ->
          {"calendar_removed", "Removed '#{sealed["data"]["title"]}' from calendar"}

        "message_scheduled" ->
          {"scheduled_cancelled",
           "Cancelled a scheduled message to #{sealed["data"]["to_name"] || "a chat"}"}

        "reminder_set" ->
          {"reminder_cancelled", "Cancelled the reminder"}

        other ->
          {other, "Undone"}
      end

    log!(e.user_id, e.skill_id, action, summary,
      via: "undo",
      conversation_id: e.conversation_id,
      target_conversation_id: e.target_conversation_id,
      device_id: e.device_id
    )
  end

  ## REST (§26.2)

  @doc "`GET /api/v1/risi/skills`: every skill in order, `client` for `device_id` (or nil)."
  def list(user_id, device_id) do
    states = states(user_id)
    ps = device_id && permissions(user_id, device_id)
    g? = google_device?(user_id, device_id)
    Enum.map(@registry, &skill_json(&1, states, ps, g?))
  end

  defp google_device?(_user_id, nil), do: false
  defp google_device?(user_id, device_id), do: Devices.google_calendar_device?(user_id, device_id)

  # v1.31 §26.1: the Calendar skill's texts and the two OAuth permissions, for devices that
  # advertise `google_calendar` only (older devices keep the v1.30 entry unchanged).
  @calendar_v131_description "Checks when you're free in your calendars and adds events you agree to."
  @calendar_v131_permissions [
    %{
      scope: "oauth",
      name: "https://www.googleapis.com/auth/calendar.events",
      label: "See busy times and add your Risi events in the Google calendars you pick",
      runtime: true
    },
    %{
      scope: "oauth",
      name: "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
      label: "List your Google calendars so you can pick them",
      runtime: true
    }
  ]

  defp for_device(%{id: "calendar"} = s, true),
    do: %{
      s
      | description: @calendar_v131_description,
        permissions: s.permissions ++ @calendar_v131_permissions
    }

  defp for_device(s, _google?), do: s

  defp skill_json(s, states, ps, google?) do
    s = for_device(s, google?)
    st = states[s.id]

    %{
      "id" => s.id,
      "kind" => s.kind,
      "title" => s.title,
      "description" => s.description,
      "can" => s.can,
      "cannot" => s.cannot,
      "permissions" =>
        for(
          p <- s.permissions,
          do: %{"scope" => p.scope, "name" => p.name, "label" => p.label, "runtime" => p.runtime}
        ),
      "tools" => s.tools,
      "where" => s.where,
      "modes" => s.modes,
      "undo" => s.undo,
      "available" => s.available,
      "state" => if(st, do: st.state, else: "off"),
      "state_changed_at" => st && Messaging.iso(st.changed_at),
      "client" => client_json(s, ps)
    }
  end

  defp client_json(_s, nil), do: nil

  defp client_json(s, ps) do
    if Enum.any?(s.permissions, &(&1.scope == "android")) do
      d = ps[:__device]

      reported =
        case ps[s.id] do
          %DevicePermission{reported_at: at} -> at
          nil -> d.last_seen_at || d.inserted_at
        end

      %{
        "device_id" => d.device_id,
        "permission" => client_permission(s, ps),
        "reported_at" => Messaging.iso(reported)
      }
    end
  end

  @doc """
  `PATCH /api/v1/risi/skills` from a `risi_skills` device: all changes or none. `{:ok, skills}`
  (the changed ones) or `{:error, :invalid_device | :not_found | :skill_unavailable |
  :bad_request}`.
  """
  def patch(user_id, device_id, body) do
    with true <- Devices.risi_skills_device?(user_id, device_id) || {:error, :invalid_device},
         {:ok, changes, cancel?} <- parse_patch(body),
         :ok <- check_changes(changes) do
      d = Devices.get(user_id, device_id)
      now = DateTime.utc_now()

      {:ok, _} =
        Repo.transaction(fn ->
          for c <- changes, do: apply_change(user_id, d, c, cancel?, now)
        end)

      states = states(user_id)
      ps = permissions(user_id, device_id)
      ids = Enum.map(changes, & &1["id"])
      g? = google_device?(user_id, device_id)
      {:ok, for(s <- @registry, s.id in ids, do: skill_json(s, states, ps, g?))}
    end
  end

  defp parse_patch(%{"changes" => changes} = b) when is_list(changes) do
    cancel = Map.get(b, "cancel_pending", false)

    cond do
      Map.keys(b) -- ["changes", "cancel_pending"] != [] -> {:error, :bad_request}
      not is_boolean(cancel) -> {:error, :bad_request}
      length(changes) not in 1..10 -> {:error, :bad_request}
      not Enum.all?(changes, &change_ok?/1) -> {:error, :bad_request}
      length(Enum.uniq_by(changes, & &1["id"])) != length(changes) -> {:error, :bad_request}
      true -> {:ok, changes, cancel}
    end
  end

  defp parse_patch(_), do: {:error, :bad_request}

  defp change_ok?(%{"id" => id} = c) when is_binary(id) do
    Map.keys(c) -- ["id", "state", "client_permission", "calendar"] == [] and
      (Map.has_key?(c, "state") or Map.has_key?(c, "client_permission") or
         Map.has_key?(c, "calendar")) and
      Map.get(c, "state", "off") in ~w(off ask allowed) and
      Map.get(c, "client_permission", "unknown") in @perms and
      calendar_ok?(c)
  end

  defp change_ok?(_), do: false

  # P0 2026-10-09: the phone's calendar choice, only on the calendar skill (null forgets it).
  defp calendar_ok?(%{"calendar" => nil, "id" => "calendar"}), do: true

  defp calendar_ok?(%{"calendar" => c, "id" => "calendar"}),
    do: RisiMe.Agent.CalendarChoice.valid?(c)

  defp calendar_ok?(c), do: not Map.has_key?(c, "calendar")

  defp check_changes(changes) do
    Enum.reduce_while(changes, :ok, fn c, :ok ->
      s = skill(c["id"])

      cond do
        s == nil ->
          {:halt, {:error, :not_found}}

        c["state"] not in [nil, "off"] and not s.available ->
          {:halt, {:error, :skill_unavailable}}

        c["state"] == "allowed" and "allowed" not in s.modes ->
          {:halt, {:error, :bad_request}}

        true ->
          {:cont, :ok}
      end
    end)
  end

  defp apply_change(user_id, d, c, cancel?, now) do
    id = c["id"]

    if Map.has_key?(c, "calendar"),
      do: RisiMe.Agent.CalendarChoice.put(user_id, c["calendar"])

    if p = c["client_permission"] do
      Repo.insert_all(
        DevicePermission,
        [
          %{
            device_ref: d.id,
            skill_id: id,
            user_id: user_id,
            device_id: d.device_id,
            permission: p,
            reported_at: now
          }
        ],
        on_conflict: [set: [permission: p, reported_at: now]],
        conflict_target: [:device_ref, :skill_id]
      )
    end

    with new when is_binary(new) <- c["state"],
         old = state(user_id, id),
         true <- new != old do
      Repo.insert_all(
        State,
        [
          %{
            user_id: user_id,
            skill_id: id,
            state: new,
            ever_on: new != "off",
            changed_at: now
          }
        ],
        on_conflict: [set: [state: new, changed_at: now]],
        conflict_target: [:user_id, :skill_id]
      )

      if new != "off",
        do:
          Repo.update_all(from(s in State, where: s.user_id == ^user_id and s.skill_id == ^id),
            set: [ever_on: true]
          )

      log!(user_id, id, state_action(old, new), state_summary(skill(id), old, new),
        via: "settings",
        device_id: d.device_id
      )

      # §26.5 revoke: open cards are void; "Also cancel N pending" cancels the reminders.
      if new == "off" do
        RisiMe.Agent.Writes.void_skill(user_id, id)
        if cancel? and id == "reminders", do: RisiMe.Agent.Reminders.cancel_pending(user_id)
      end
    end

    :ok
  end

  defp state_action("off", _new), do: "skill_on"
  defp state_action(_old, "off"), do: "skill_off"
  defp state_action(_old, "allowed"), do: "skill_allowed"
  defp state_action(_old, "ask"), do: "skill_ask"

  defp mode_text("ask"), do: "Ask me each time"
  defp mode_text("allowed"), do: "Allowed"

  defp state_summary(s, "off", new), do: "Turned on #{s.title} (#{mode_text(new)})"
  defp state_summary(s, _old, "off"), do: "Turned off #{s.title}"
  defp state_summary(s, _old, new), do: "Set #{s.title} to #{mode_text(new)}"
end
