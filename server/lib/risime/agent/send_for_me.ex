defmodule RisiMe.Agent.SendForMe do
  @moduledoc """
  "Send a message for me" (proposal `2026-10-14-send-for-me`, §41; Harsha's P1 2026-10-14): the
  client tool **`send_message`**, separate from scheduled messages.

  * **One card, one tap** (§41.2): Risi drafts the message in the asker's language and posts ONE
    `confirm` card in the Risi chat: To: <name> · the text · [Send] [Edit]. [Send] is the
    `confirm_write`; [Edit] is a `confirm_write` with `edit: {"text"}` (`RisiMe.Agent.Writes`).
    Then the **phone** sends it, end-to-end encrypted, as the user (the `send_message` tool call,
    the §41.3 phone rule), and Risi posts `message_sent`: "✓ Sent to <name>" (never the text).
  * **Phase 1** (an asker without a `risi_send` device, e.g. nightly.7): the §25.4 `draft` card
    ([Use] opens the chat with the composer filled, it never sends) and an honest line.
  * **Recipient** (deterministic, before any card): a friend's or co-member's name; several
    matches → "Which Upali?" (the §34.5 clarify, no Keep); no 1:1 chat → said so; Private unless
    the asker says Official; never a Risi chat.
  * **Switch** `RISI_SEND_FOR_ME` = `off | canary | on` (default `canary` = `RISI_DEV_USERS`).
  * **Not consequential** (decision 085 principle 4): sending the user's own chat message is not
    money, pay, HR, deletion or legal. The tool says so (`consequential: false`) for the
    consequential-action guard; the user's own [Send] (or their per-contact standing choice on
    the phone) is the approval.

  Privacy: the text passes the server once (the sealed pending write and the 2-minute tool-call
  row, both wiped when done); it is never logged, never in the learning log (the turn step is a
  hash) and never in `message_sent`.
  """
  require Logger

  alias RisiMe.Agent.{NameCheck, Out, ToolCalls, Writes}
  alias RisiMe.{Chats, Devices}

  @flag "RISI_SEND_FOR_ME"

  @doc "`:off | :canary | :on` (`canary` = on only for `RISI_DEV_USERS`)."
  def mode, do: RisiMe.Agents.Flags.feature(@flag)

  @doc "True when the switch is on for `user`."
  def on_for?(user), do: RisiMe.Agents.Flags.feature_for(@flag, user)

  @doc "The consequential-action guard's class for this tool: never consequential (§41.0)."
  def consequential?, do: false

  @max_text 4096

  defp obj(props, required),
    do: %{
      "type" => "object",
      "properties" => props,
      "required" => required,
      "additionalProperties" => false
    }

  @doc "The `send_message` registry entry (§41.2)."
  def tool do
    %{
      name: "send_message",
      description:
        "send a message NOW from the asker's phone to one person (the asker taps Send on a card " <>
          "first): to (the friend's name, or \"this chat\" for this Official chat), text (the " <>
          "message itself, written for the recipient in the asker's language and style, in " <>
          "first person as the asker; never \"Risi says\"), tab: \"private\" (default) or " <>
          "\"official\" only when the asker says Official. Use this, not schedule_message, " <>
          "whenever the asker gives no time",
      where: :client,
      personal: true,
      write: true,
      finds: false,
      skill: nil,
      consequential: false,
      args:
        obj(
          %{
            "to" => %{"type" => "string", "minLength" => 1, "maxLength" => 64},
            "text" => %{"type" => "string", "minLength" => 1, "maxLength" => @max_text},
            "tab" => %{"enum" => ["private", "official"]}
          },
          ["to", "text"]
        ),
      authorize: fn ctx -> if on_for?(ctx.asker), do: :ok, else: {:error, :denied} end,
      run: &run/2,
      exec: &exec/3
    }
  end

  ## The step (§41.2)

  @doc false
  def run(%{"to" => to, "text" => text} = a, ctx) when is_binary(to) and is_binary(text) do
    text = String.trim(text)
    tab = if a["tab"] == "official", do: "official", else: "private"

    cond do
      text == "" or String.length(text) > @max_text ->
        {:error, "failed", "bad_text"}

      true ->
        case by_id(a, tab, ctx) || recipient(to, tab, ctx) do
          {:ok, r} -> proposal(r, text, ctx)
          {:clarify, c} -> {:clarify, %{c | args: Map.put(c.args, "text", text)}}
          {:answer, _status, _text} = ans -> ans
          {:error, _, _} = e -> e
        end
    end
  end

  def run(_args, _ctx), do: {:error, "failed", "bad_args"}

  defp proposal(r, text, ctx) do
    lang = Map.get(ctx, :lang, "en")

    if Devices.risi_send_device?(ctx.asker, ctx.device_id) do
      wire = %{"conversation_id" => r.conv, "text" => text}

      {:propose,
       %{
         args: %{
           "wire" => wire,
           "to_name" => r.name,
           "to_user_id" => r.user_id,
           "tab" => r.tab,
           "lang" => lang
         },
         card_args: wire,
         summary: "Send to #{r.name} (#{tab_label(r.tab)}): #{text}",
         when: nil,
         text: text,
         personal: true,
         skill_id: nil,
         send: send_display(r, lang)
       }}
    else
      phase1(r, text, lang, ctx)
    end
  end

  @doc "The card's `send` display object (§41.2)."
  def send_display(r, lang),
    do: %{
      "to" => %{"name" => r.name, "user_id" => r.user_id},
      "tab" => r.tab,
      "language" => lang
    }

  defp tab_label("official"), do: "Official"
  defp tab_label(_), do: "Private"

  # §41.1 Phase 1 (no `risi_send` device): the §25.4 `draft` card, [Use] opens the chat with the
  # composer filled. Nothing is claimed sent.
  defp phase1(r, text, lang, ctx) do
    rc = RisiMe.RisiChat.active_id(ctx.asker) || ctx.conv

    risi = %{
      "kind" => "draft",
      "request_id" => ctx.request_id,
      "text" => text,
      "language" => if(lang in ~w(en si ta), do: lang, else: "en"),
      "target_conversation_id" => r.conv,
      "turn_ref" => ctx.turn_id,
      "call_ref" => Map.get(ctx, :call_ref),
      "notify" => [ctx.asker]
    }

    case Out.post(rc, "Message for #{r.name}: #{text}", risi) do
      {:ok, _} -> {:answer, "ok", line(:draft, lang, r.name)}
      {:error, _} -> {:error, "failed", "not_posted"}
    end
  end

  ## The recipient (§41.2)

  @this ["this chat", "this group", "here"]

  @doc false
  def recipient(to, tab, ctx) do
    q = NameCheck.norm(to)

    cond do
      String.downcase(String.trim(to)) in @this ->
        if ctx.in_risi_chat? or not RisiMe.Groups.Tabs.official?(ctx.conv),
          do: {:error, "failed", "unknown_recipient: ask who the message is for"},
          else: {:ok, %{conv: ctx.conv, name: "this chat", user_id: nil, tab: "official"}}

      q == "" ->
        {:error, "failed", "unknown_recipient: ask who the message is for"}

      true ->
        people = RisiMe.Agent.CalendarTools.contacts(ctx.asker)
        exact = for {id, n} <- people, NameCheck.norm(n) == q, do: {id, n}

        word =
          for {id, n} <- people, q in String.split(NameCheck.norm(n)), do: {id, n}

        case if(exact != [], do: exact, else: word) |> Enum.uniq_by(&elem(&1, 0)) do
          [{id, n}] ->
            chat(ctx, id, n, tab)

          [] ->
            {:answer, "failed", "I couldn't find #{String.trim(to)} in your contacts."}

          many ->
            options =
              many
              |> Enum.sort_by(&elem(&1, 1))
              |> Enum.take(3)
              |> Enum.with_index()
              |> Enum.map(fn {{id, n}, i} -> %{name: n, user_id: id, rank: i} end)

            {:clarify,
             %{
               tool: "send_message",
               args: %{"to" => to, "tab" => tab},
               said: String.trim(to),
               options: options,
               keep: false,
               question: "Which #{String.trim(to)}?"
             }}
        end
    end
  end

  # The asker's 1:1 with `id` (Private), or its Official conversation when asked and on.
  defp chat(ctx, id, name, tab) do
    dm = RisiMe.Messaging.conversation_id(ctx.asker, id)
    friend? = id in RisiMe.Social.friend_ids(ctx.asker)

    cond do
      not friend? or not match?({:ok, _}, Chats.resolve(ctx.asker, dm)) ->
        {:answer, "failed",
         "You don't have a 1:1 chat with #{name} yet. Open Contacts → #{name} to start one, " <>
           "then ask me again."}

      tab == "official" ->
        case Chats.official_group(dm) do
          %{state: "active", id: og} ->
            if Chats.off?(dm),
              do: {:answer, "failed", "Official is off in your chat with #{name}."},
              else: {:ok, %{conv: og, name: name, user_id: id, tab: "official"}}

          _ ->
            {:answer, "failed", "Official is off in your chat with #{name}."}
        end

      true ->
        {:ok, %{conv: dm, name: name, user_id: id, tab: "private"}}
    end
  end

  # The person a "Which Upali?" chip named (`to_user_id`): only on the server's own clarify path
  # (`names_ok`), never from the model (its schema has no such key).
  defp by_id(%{"to_user_id" => uid}, tab, %{names_ok: true} = ctx) when is_binary(uid) do
    case Enum.find(RisiMe.Agent.CalendarTools.contacts(ctx.asker), &(elem(&1, 0) == uid)) do
      {id, n} -> chat(ctx, id, n, tab)
      nil -> nil
    end
  end

  defp by_id(_a, _tab, _ctx), do: nil

  @doc """
  A held "Which Upali?" question was answered with `choice` (`%{"name", "user_id"}`): the
  step's args name that person exactly (`to_user_id`, honoured only from a clarify answer).
  """
  def resolved_args(args, %{"name" => n, "user_id" => uid}),
    do: args |> Map.put("to", n) |> Map.put("to_user_id", uid)

  def resolved_args(args, _choice), do: args

  ## Running the confirmed write (§41.2, §41.3)

  @doc """
  `exec` of a confirmed `send_message`: the tool call (the card's args, maybe with the edited
  text) to the confirming device when it advertises `risi_send`, else the turn's; then
  `message_sent` (`sent`/`queued`), the `tool_timeout` error with Retry, or an honest line.
  """
  def exec(%Writes.Write{} = w, args, device) do
    dev = if device && Devices.risi_send_device?(w.user_id, device), do: device, else: w.device_id
    wire = Map.put(args["wire"], "write_id", w.write_id)

    spec = %{
      user: w.user_id,
      device: dev,
      tool: "send_message",
      args: wire,
      turn_id: w.turn_id,
      request_id: w.request_id,
      conv: w.conversation_id,
      write_id: w.write_id
    }

    case ToolCalls.call(spec) do
      {:ok, "ok", %{"state" => state}} when state in ~w(sent queued) ->
        sent(w, args, state)
        :ok

      {:error, :timeout} ->
        Writes.post(w, "I couldn't reach your phone to send it. Tap Retry to try again.", %{
          "kind" => "error",
          "request_id" => w.request_id,
          "code" => "tool_timeout",
          "notify" => [w.user_id]
        })

        {:error, "timeout"}

      {:ok, status, result} ->
        answer(w, status, failed_text(status, result, args["to_name"] || "them"))
        {:error, status}
    end
  end

  defp failed_text("error", %{"code" => "not_member"}, who),
    do: "I couldn't send it: you're not in a chat with #{who} on this phone."

  defp failed_text("declined", _r, _who), do: "Your phone declined it, so nothing was sent."
  defp failed_text(_s, _r, _who), do: "Your phone couldn't send it, so nothing was sent."

  defp sent(w, args, state) do
    name = args["to_name"] || "them"
    lang = args["lang"] || "en"
    body = if state == "sent", do: line(:sent, lang, name), else: line(:queued, lang, name)

    Writes.post(w, body, %{
      "kind" => "message_sent",
      "request_id" => w.request_id,
      "write_id" => w.write_id,
      "conversation_id" => get_in(args, ["wire", "conversation_id"]),
      "to" => %{"name" => name, "user_id" => args["to_user_id"]},
      "tab" => args["tab"] || "private",
      "state" => state,
      "turn_ref" => w.turn_id,
      "call_ref" => nil,
      "made_by" => RisiMe.Agent.MadeBy.rule(),
      "notify" => [w.user_id]
    })
  end

  defp answer(w, status, text) do
    Writes.post(w, text, %{
      "kind" => "answer",
      "request_id" => w.request_id,
      "answer" => text,
      "refs" => [],
      "confidence" => 1.0,
      "steps" => [%{"tool" => "send_message", "status" => step_status(status)}],
      "sources" => [],
      "next_steps" => [],
      "turn_ref" => w.turn_id,
      "notify" => [w.user_id]
    })
  end

  defp step_status("error"), do: "failed"
  defp step_status(s), do: s

  ## Words (en / si / ta)

  @lines %{
    sent: %{
      "en" => "✓ Sent to %{name}",
      "si" => "✓ %{name} ට යැව්වා",
      "ta" => "✓ %{name} க்கு அனுப்பப்பட்டது"
    },
    queued: %{
      "en" => "Queued for %{name}: it goes as soon as your phone is online.",
      "si" => "%{name} ට යවන්න ලෑස්තියි: ඔබේ phone එක online වුණ ගමන් යනවා.",
      "ta" => "%{name} க்கு அனுப்ப தயார்: உங்கள் phone online ஆனதும் அனுப்பப்படும்."
    },
    card: %{
      "en" => "Tap Send to send it to %{name}, or Edit to change it.",
      "si" => "%{name} ට යවන්න Send ඔබන්න, වෙනස් කරන්න Edit ඔබන්න.",
      "ta" => "%{name} க்கு அனுப்ப Send ஐ அழுத்துங்கள், மாற்ற Edit ஐ அழுத்துங்கள்."
    },
    draft: %{
      "en" =>
        "Here's the message for %{name}. Tap Use to open your chat with it ready, then send it.",
      "si" => "%{name} ට පණිවිඩය මෙන්න. Use ඔබලා chat එක open කරන්න, පණිවිඩය ලෑස්තියි, ඊට පස්සේ යවන්න.",
      "ta" => "%{name} க்கான செய்தி இதோ. Use ஐ அழுத்தி chat ஐத் திறந்து, பிறகு அனுப்புங்கள்."
    }
  }

  @doc "A server-built line in `lang` (en/si/ta; English otherwise)."
  def line(key, lang, name) do
    t = Map.get(@lines[key], lang, @lines[key]["en"])
    String.replace(t, "%{name}", name)
  end

  @doc "The turn's short reply once the card is in the chat (§28.3: the card is the action)."
  def card_line(ctx, args) do
    who =
      case args do
        %{"to" => to} when is_binary(to) -> to
        _ -> "them"
      end

    line(:card, Map.get(ctx, :lang, "en"), who)
  end
end
