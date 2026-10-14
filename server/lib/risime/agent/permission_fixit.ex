defmodule RisiMe.Agent.PermissionFixit do
  @moduledoc """
  "I need your calendar to do that." [Allow calendar] (proposal `2026-10-14-permission-fixit`,
  §43; Harsha 2026-10-14 10:55/10:57): Risi never just says "can't access".

  * **The card** `needs_permission` (in the asker's Risi chat): one sentence, one button. It
    replaces `skill_needed` (§26.5) for a skill that is off or lacks its phone permission, and
    follows a client tool's `no_permission` in a turn, for an asker whose device advertises
    `risi_fixit` while `RISI_PERMISSION_FIXIT` is on for them (default `canary`).
  * **The resume record:** a `risi_pending_writes` row (`state: "resume"`) holding only ids
    (sealed): the request's conversation and `request_id`, the permission and the skill. 24 h,
    one use. The request's text stays in the sealed 24-h buffer.
  * **`permission_granted`** (the asker's own leaf): the original request runs again, a fresh
    turn with the same `request_id` (a `resume` job). **`permission_declined`:** "OK, I won't use
    your calendar. You can change this in Risi skills." and nothing more.

  Not an agent decision: a deterministic consent step (infrastructure for the Risi Brain's tools).
  """
  import Ecto.Query, only: [from: 2]

  require Logger

  alias RisiMe.Agent.{Audience, Out, Seal, Writes}
  alias RisiMe.{Repo, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @flag "RISI_PERMISSION_FIXIT"
  @ttl_s 24 * 3600

  # The skill → the permission its tools need on the phone (nil: no fix-it card; skill_needed).
  @permission %{
    "calendar" => "calendar",
    "reminders" => "notifications",
    "alarm" => "alarms",
    # Proposal 2026-10-14-calendar-v2 §44.9: permissions without a Risi skill (skill_id null).
    "contacts" => "contacts",
    "microphone" => "microphone",
    "location" => "location"
  }

  # The ids above that are Risi skills (§26.1); the others are phone permissions only.
  @skills ~w(calendar reminders alarm)

  @doc "The permissions a card may name (§43.2)."
  def permissions, do: ~w(calendar contacts microphone notifications location alarms)

  @doc "`:off | :canary | :on`."
  def mode, do: RisiMe.Agents.Flags.feature(@flag)

  @doc "True when the switch is on for `user`."
  def on_for?(user), do: RisiMe.Agents.Flags.feature_for(@flag, user)

  @doc "The permission behind `skill_id`, or nil."
  def permission_of(skill_id), do: Map.get(@permission, skill_id)

  @doc """
  True when the fix-it card replaces `skill_needed` for `skill_id` in `ctx`: the switch, a skill
  with a phone permission, and a `risi_fixit` device (the asking one, else any of the asker's).
  """
  def applies?(ctx, skill_id) do
    is_binary(permission_of(skill_id)) and on_for?(ctx.asker) and fixit_device?(ctx)
  rescue
    _ -> false
  end

  defp fixit_device?(%{asker: asker} = ctx) do
    case Map.get(ctx, :device_id) do
      d when is_binary(d) -> device_has?(asker, d)
      _ -> any_device?(asker)
    end
  end

  defp device_has?(user, device) do
    case Ecto.UUID.cast(device) do
      {:ok, d} ->
        Repo.exists?(
          from x in RisiMe.Devices.Device,
            where: x.user_id == ^user and x.device_id == ^d and "risi_fixit" in x.capabilities
        )

      :error ->
        false
    end
  end

  defp any_device?(user),
    do:
      Repo.exists?(
        from x in RisiMe.Devices.Device,
          where: x.user_id == ^user and "risi_fixit" in x.capabilities
      )

  @lines %{
    need: %{
      "en" => "I need your %{what} to do that.",
      "si" => "ඒක කරන්න මට ඔබේ %{what} ඕන.",
      "ta" => "அதைச் செய்ய எனக்கு உங்கள் %{what} தேவை."
    },
    account: %{
      "en" => "Your Google calendar isn't on this phone yet.",
      "si" => "ඔබේ Google calendar එක තවම මේ phone එකේ නැහැ.",
      "ta" => "உங்கள் Google calendar இன்னும் இந்த phone இல் இல்லை."
    },
    declined: %{
      "en" => "OK, I won't use your %{what}. You can change this in Risi skills.",
      "si" => "හරි, මම ඔබේ %{what} පාවිච්චි කරන්නේ නැහැ. Risi skills වල මේක වෙනස් කරන්න පුළුවන්.",
      "ta" => "சரி, உங்கள் %{what} ஐ நான் பயன்படுத்த மாட்டேன். Risi skills இல் இதை மாற்றலாம்."
    }
  }

  @what %{
    "calendar" => "calendar",
    "contacts" => "contacts",
    "microphone" => "microphone",
    "notifications" => "notifications",
    "location" => "location",
    "alarms" => "alarms"
  }

  @doc "The sentence for `key` (`:need` | `:declined`) about `permission`, in `lang`."
  def line(key, permission, lang) do
    t = Map.get(@lines[key], lang, @lines[key]["en"])
    String.replace(t, "%{what}", Map.get(@what, permission, permission))
  end

  @doc """
  Ends a turn with the card for `skill_id` (the skill is off or its permission is missing): the
  resume record, then the card in the asker's Risi chat (the group gets the pointer). Oban result.
  """
  def post(ctx, skill_id, call_ref \\ nil, opts \\ []) do
    permission = permission_of(skill_id)
    resume_id = Ecto.UUID.generate()
    rc = RisiMe.RisiChat.active_id(ctx.asker) || ctx.conv
    skill? = skill_id in @skills
    off? = skill? and RisiMe.Agent.Skills.state(ctx.asker, skill_id) == "off"
    lang = Map.get(ctx, :lang, "en")
    # Proposal 2026-10-14-calendar-v2 §44.9: the guide's step ("account": no calendars yet).
    step = Keyword.get(opts, :step)

    text =
      if step == "account",
        do: line(:account, permission, lang),
        else: line(:need, permission, lang)

    :ok =
      hold(ctx, resume_id, rc, %{
        "conv" => ctx.conv,
        "permission" => permission,
        "skill_id" => skill_id,
        "lang" => lang
      })

    risi = %{
      "kind" => "needs_permission",
      "request_id" => ctx.request_id,
      "resume_id" => resume_id,
      "permission" => permission,
      "skill_id" => if(skill?, do: skill_id),
      "turn_on" => off?,
      "text" => text,
      "buttons" => ["allow"],
      "expires_at" => RisiMe.Agent.Clock.ts(DateTime.add(DateTime.utc_now(), @ttl_s, :second)),
      "turn_ref" => Map.get(ctx, :turn_id),
      "call_ref" => call_ref,
      "made_by" => RisiMe.Agent.MadeBy.rule(),
      "notify" => [ctx.asker]
    }

    risi = if step, do: Map.put(risi, "step", step), else: risi

    body =
      text <>
        " Update RisiMe to allow it here, or turn it on in Settings → Risi skills."

    Audience.deliver(ctx, body, risi, true)
  end

  defp hold(ctx, resume_id, target, payload) do
    {:ok, key} = Seal.data()
    now = DateTime.utc_now()

    Repo.insert!(%Writes.Write{
      write_id: resume_id,
      user_id: ctx.asker,
      conversation_id: ctx.conv,
      card_conversation_id: target,
      request_id: ctx.request_id,
      turn_id: Map.get(ctx, :turn_id),
      device_id: Map.get(ctx, :device_id),
      tool: "needs_permission",
      args: Seal.seal(key, "risi_pending_writes:" <> resume_id, payload),
      state: "resume",
      personal: true,
      expires_at: DateTime.add(now, @ttl_s, :second),
      inserted_at: now
    })

    :ok
  end

  @doc """
  `permission_granted` / `permission_declined` from `user` in `conv` (the card's chat): only the
  asker, once, before it expires. Oban result (always `:ok`).
  """
  def act(conv, user, %{"action" => action, "target" => target})
      when action in ~w(permission_granted permission_declined) do
    with {:ok, rid} <- Ecto.UUID.cast(target),
         %Writes.Write{state: "resume", tool: "needs_permission"} = w <-
           Repo.get(Writes.Write, rid),
         true <- w.user_id == user and w.card_conversation_id == conv,
         true <- DateTime.compare(DateTime.utc_now(), w.expires_at) == :lt,
         {1, _} <-
           Repo.update_all(
             from(x in Writes.Write, where: x.write_id == ^rid and x.state == "resume"),
             set: [state: "done"]
           ),
         {:ok, payload} <- open(w) do
      Repo.update_all(from(x in Writes.Write, where: x.write_id == ^rid), set: [args: nil])

      # Proposal 2026-10-14-calendar-v2 §44.4: a new grant: the next read says so, once.
      if action == "permission_granted" and payload["permission"] == "calendar",
        do: RisiMe.Agent.CalendarV2.reset_first_read(w.user_id)

      if action == "permission_granted",
        do: resume(w, payload),
        else: declined(w, payload)
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi permission answer failed: #{inspect(e.__struct__)}")
      :ok
  end

  def act(_conv, _user, _env), do: :ok

  defp open(w) do
    with {:ok, key} <- Seal.data(),
         bin when is_binary(bin) <- w.args do
      Seal.open(key, "risi_pending_writes:" <> w.write_id, bin)
    else
      _ -> :error
    end
  end

  # The original request runs again: its buffered `risi_request` (the same request_id).
  defp resume(w, %{"conv" => conv}) do
    case request_message(conv, w.user_id, w.request_id) do
      nil ->
        Logger.info("Risi permission resume: the request is no longer in the 24-h buffer")

      mid ->
        %{
          "kind" => "resume",
          "conv" => conv,
          "message_id" => mid,
          "request_id" => w.request_id,
          "user_id" => w.user_id,
          "t0" => System.system_time(:second)
        }
        |> Job.new(queue: :risi_requests)
        |> Oban.insert()
    end
  end

  defp declined(w, payload) do
    text = line(:declined, payload["permission"], payload["lang"] || "en")

    Out.post(w.card_conversation_id, text, %{
      "kind" => "answer",
      "request_id" => w.request_id,
      "answer" => text,
      "refs" => [],
      "confidence" => 1.0,
      "sources" => [],
      "next_steps" => [],
      "next_actions" => [
        %{"label" => "Risi skills", "action" => "open", "target" => "settings.risi_skills"}
      ],
      "call_ref" => nil,
      "made_by" => RisiMe.Agent.MadeBy.rule(),
      "notify" => [w.user_id]
    })
  end

  @doc "The buffered `risi_request` of `user` with `request_id` in `conv` (24 h), or nil."
  def request_message(conv, user, request_id) do
    since = TimeUUID.at(DateTime.add(DateTime.utc_now(), -@ttl_s, :second))

    conv
    |> RisiMe.Agent.Transcript.list(since, 2_000)
    |> Enum.find_value(fn row ->
      with true <- row.sender_id == user,
           {:ok, %{"type" => "risi_request", "request_id" => ^request_id}} <-
             Jason.decode(row.plaintext) do
        row.message_id
      else
        _ -> nil
      end
    end)
  end
end
