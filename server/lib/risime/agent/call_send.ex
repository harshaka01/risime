defmodule RisiMe.Agent.CallSend do
  @moduledoc """
  **Risi sends Official messages on a voice yes** (contract v1.55 §41.11, decision 094, Harsha
  2026-10-16 15:25 SLT). During a Call Risi, after Risi read a message back and the caller said
  yes, Risi posts it into the Official 1:1 with that person as **its own** visible post
  (`risi.kind "sent_for"`, body `"<text>\\n— Sent by Risi for <caller>"`), never a message forged
  as the user. The call brain (`RisiMe.Agent.CallRisi.BrainV3`) calls `impl/0`'s functions
  (tests: `config :risime, :risi_call_send_impl, Module`).

    * **Switch** `RISI_CALL_SEND` (`off | canary | on`, default `canary`) plus the per-user
      `voice_send` setting (`GET`/`PUT /api/v1/me/risi/voice`, present only while the switch is on
      for the user; default true for canary users, false otherwise): `enabled_for?/1`.
    * **The target** (`target/2`): the Official conversation of the caller's 1:1 with a friend
      (§42.2 rules), on, with Risi an active member. Private is never in reach.
    * **Never by voice** (`needs_tap?/1`): money or payments, HR, legal matters, accusations or
      complaints about a person, deletion, and anything the guard can't classify: a short risi-l1
      label (task `call_send_guard`, only the label logged) plus a deterministic backstop; a
      model failure means "needs the tap".
    * **Send** (`send/1`): `{:ok, …}` only once the post is stored (its `message_id`: the claim
      guard says "Sent." only then); then the `message_sent` record in the caller's Risi chat with
      `tab: "official"`, `via: "risi"`, `undo_until` (+2 min).
    * **Undo** (`undo/2`): until `undo_until`, Risi deletes its post for everyone (§15, its own
      message, `RisiMe.Agent.Out.delete/2` → `Conversation.delete_own`) and posts `message_sent`
      `state: "undone"`. From the client action `undo_send` (the caller's own leaf, `act/3`), a
      typed "undo that" to Risi (`typed_undo?/2`) or the call.

  **Store:** `risi_call_sends` (a table, so an Undo survives a deploy inside the 2 minutes): the
  write id, the caller, the recipient's id and display name, the Official conversation, the post's
  `message_id`, the Risi chat, `undo_until` and `state` (`sent` | `undone`). **Never the text.**
  Rows older than a day are pruned on each send.

  **Learning log:** task `call_send`, the outcome (`sent` | `undone` | `needs_tap` | `declined`)
  and the latency; never the text.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{LearningLog, MadeBy, Out}
  alias RisiMe.{Chats, Repo, TimeUUID}

  @flag "RISI_CALL_SEND"
  @undo_s 120

  @callback enabled_for?(String.t()) :: boolean
  @callback target(String.t(), String.t()) ::
              {:ok, %{conv: String.t(), name: String.t()}} | {:error, :official_off | :no_chat}
  @callback needs_tap?(String.t()) :: boolean
  @callback send(map) :: {:ok, map} | {:error, term}
  @callback undo(String.t(), String.t() | :last) :: {:ok, map} | {:error, :expired | :none}
  @callback record_outcome(String.t(), String.t(), integer | nil) :: :ok

  @doc "The module the brain calls (tests: `config :risime, :risi_call_send_impl`)."
  def impl, do: Application.get_env(:risime, :risi_call_send_impl, __MODULE__)

  @doc "The flag's name."
  def flag, do: @flag

  @doc "The Undo window in seconds (2 minutes)."
  def undo_s, do: @undo_s

  ## The switch and the setting

  @doc "True when `RISI_CALL_SEND` is on for `user` (on, or canary and a dev user)."
  def switch_on?(user), do: RisiMe.Agents.Flags.feature_for(@flag, user)

  @doc "True when Risi may send Official messages for `user` on a voice yes."
  def enabled_for?(user), do: switch_on?(user) and voice_send(user)

  @doc "The stored `voice_send` setting, else its default (true for canary users)."
  def voice_send(user) do
    case Repo.one(
           from s in "risi_call_send_settings",
             where: s.user_id == type(^user, Ecto.UUID),
             select: s.voice_send
         ) do
      v when is_boolean(v) -> v
      nil -> RisiMe.Agents.Flags.dev_user?(user)
    end
  rescue
    _ -> false
  end

  @doc "Stores `voice_send` (only while the switch is on for `user`; else ignored)."
  def put_voice_send(user, v) when is_boolean(v) do
    if switch_on?(user) do
      Repo.insert_all(
        "risi_call_send_settings",
        [%{user_id: Ecto.UUID.dump!(user), voice_send: v, updated_at: DateTime.utc_now()}],
        on_conflict: {:replace, [:voice_send, :updated_at]},
        conflict_target: [:user_id]
      )
    end

    :ok
  end

  def put_voice_send(_user, _v), do: {:error, :bad_request}

  @doc """
  `setting` (the `/me/risi/voice` reply) with `voice_send` while the switch is on for `user`;
  unchanged otherwise (v1.54 byte-identical).
  """
  def with_setting(setting, user) do
    if switch_on?(user), do: Map.put(setting, :voice_send, voice_send(user)), else: setting
  end

  ## The target (§42.2 rules, Official only)

  @doc """
  The Official conversation of `user`'s 1:1 with `recipient`: `{:ok, %{conv, name}}`;
  `{:error, :no_chat}` (no friend, no 1:1) or `{:error, :official_off}` (no Official group, off,
  or Risi not an active member). Never a Risi chat, never Private.
  """
  def target(user, recipient) when is_binary(user) and is_binary(recipient) do
    dm = RisiMe.Messaging.conversation_id(user, recipient)

    cond do
      recipient not in RisiMe.Social.friend_ids(user) or
          not match?({:ok, _}, Chats.resolve(user, dm)) ->
        {:error, :no_chat}

      true ->
        case Chats.official_group(dm) do
          %{state: "active", id: og, chat_kind: kind} when kind != "risi" ->
            if Chats.off?(dm) or not RisiMe.Agent.may_act?(og),
              do: {:error, :official_off},
              else: {:ok, %{conv: og, name: name_of(recipient)}}

          _ ->
            {:error, :official_off}
        end
    end
  rescue
    _ -> {:error, :no_chat}
  end

  def target(_user, _recipient), do: {:error, :no_chat}

  defp name_of(id) do
    case Repo.get(RisiMe.Accounts.User, id) do
      %{display_name: n} when is_binary(n) and n != "" -> n
      _ -> "them"
    end
  end

  ## The guard: what never goes by voice (decision 085)

  @labels ~w(ok money hr legal accusation deletion unclear)

  @guard_schema %{
    "type" => "object",
    "properties" => %{"label" => %{"enum" => @labels}},
    "required" => ["label"],
    "additionalProperties" => false
  }

  @guard_system """
  Classify a short chat message a user wants sent to a colleague. Return {"label": …}: \
  "money" (payments, prices, salaries, transfers, invoices, loans, bank details), "hr" (hiring, \
  firing, pay, leave, promotions, warnings, performance, resignations), "legal" (contracts, \
  lawyers, courts, police, agreements, liability), "accusation" (blaming, accusing or complaining \
  about a person), "deletion" (asking to delete or remove data, files or accounts), "unclear" (you \
  can't tell), else "ok" (everyday talk, greetings, logistics, times, status updates).
  """

  @money ~r/\b(?:pay|paid|paying|payment|payments|salary|salaries|wage|wages|money|cash|bank|account number|transfer|transferred|invoice|invoices|loan|refund|deposit|fee|fees|price|cost|costs|rupees?|rs\.?\s?\d|lkr|usd|dollars?|euros?|budget|cheque|check for|credit card|debit|owe|owes|owed|debt)\b|[$€£]\s?\d/iu
  @hr ~r/\b(?:fire|fired|firing|terminate|terminated|termination|dismiss|dismissed|resign|resigned|resignation|promotion|promote|demote|disciplinary|warning letter|appraisal|performance review|hire|hired|hiring|lay ?off|laid off|hr|human resources|leave application|sick leave|annual leave|increment|bonus)\b/iu
  @legal ~r/\b(?:legal|lawyer|lawyers|attorney|lawsuit|sue|suing|court|courts|nda|contract|contracts|agreement|police|liability|liable|breach|notice period|tribunal|summons)\b/iu
  @accuse ~r/\b(?:stole|steal|stealing|theft|thief|lied|liar|lying|cheat|cheated|cheating|fraud|scam|blame|blamed|your fault|complain|complaint|harass|harassed|harassment|incompetent|lazy|useless|stupid|idiot|unacceptable|disappointed in you|misconduct)\b/iu
  @delete ~r/\b(?:delete|deleted|deleting|remove|removed|erase|erased|wipe|wiped|purge|destroy)\b/iu

  @doc "True when the deterministic backstop alone holds `text` back (see the moduledoc)."
  def backstop?(text) when is_binary(text),
    do: Enum.any?([@money, @hr, @legal, @accuse, @delete], &Regex.match?(&1, text))

  def backstop?(_), do: true

  @doc """
  True when `text` must keep the tap (money, HR, legal, accusations, deletion, unclear). The
  backstop first (no model call when it already says so), then the label (test stand-in:
  `config :risime, :risi_call_send_guard, fn text -> label end`); a failure is "needs the tap".
  """
  def needs_tap?(text) when is_binary(text) do
    String.trim(text) == "" or backstop?(text) or label(text) != "ok"
  end

  def needs_tap?(_), do: true

  @doc false
  def label(text) do
    case Application.get_env(:risime, :risi_call_send_guard) do
      f when is_function(f, 1) -> to_label(f.(text))
      _ -> model_label(text)
    end
  catch
    _, _ -> "unclear"
  end

  defp to_label(l) when l in @labels, do: l
  defp to_label(_), do: "unclear"

  defp model_label(text) do
    req = %{
      task: "call_send_guard",
      conversation_id: "call_send",
      chat_id: "call_send",
      system: @guard_system,
      user: "Message: \"#{String.slice(text, 0, 1000)}\"",
      schema_name: "call_send_guard",
      schema: @guard_schema,
      source_message_ids: [],
      model_alias: RisiMe.Agent.CallRisi.Decider.model(),
      max_tokens: 20,
      temperature: 0.0,
      timeout_ms: 4_000,
      retries: 0,
      invalid_output: :return,
      log_output: fn o -> Jason.encode!(%{"label" => o["label"]}) end
    }

    case RisiMe.Agent.LLM.complete(req) do
      {:ok, %{output: %{"label" => l}}} -> to_label(l)
      _ -> "unclear"
    end
  end

  ## Send

  @doc """
  Risi's own `sent_for` post into `conv`, then the `message_sent` record in the caller's Risi
  chat. `args`: `%{user_id, recipient_user_id, conv, name, text, call_id, request_id}` (optional
  `risi_chat`, `caller_name`). `{:ok, %{write_id, message_id, undo_until}}` only once the post is
  stored; `{:error, reason}` otherwise (nothing was sent).
  """
  def send(%{user_id: user, conv: conv, name: name, text: text} = a)
      when is_binary(user) and is_binary(conv) and is_binary(text) do
    t0 = System.monotonic_time(:millisecond)
    write_id = Ecto.UUID.generate()
    caller = a[:caller_name] || name_of(user)
    recipient = a[:recipient_user_id]

    risi = %{
      "kind" => "sent_for",
      "on_behalf_of" => %{"user_id" => user, "name" => caller},
      "write_id" => write_id,
      "call_ref" => nil,
      "made_by" => MadeBy.rule(),
      "notify" => if(is_binary(recipient), do: [recipient], else: [])
    }

    body = String.trim(text) <> "\n— Sent by Risi for " <> caller

    with true <- RisiMe.Agent.may_act?(conv) || {:error, :official_off},
         {:ok, %{message_id: mid}} when is_binary(mid) <- Out.post(conv, body, risi) do
      now = DateTime.utc_now()
      until = DateTime.add(now, @undo_s, :second)
      rc = a[:risi_chat] || risi_chat(user)

      Repo.insert_all("risi_call_sends", [
        %{
          write_id: Ecto.UUID.dump!(write_id),
          user_id: Ecto.UUID.dump!(user),
          recipient_user_id: if(is_binary(recipient), do: Ecto.UUID.dump!(recipient)),
          name: name,
          conversation_id: conv,
          message_id: mid,
          risi_chat_id: rc,
          request_id: a[:request_id],
          call_id: a[:call_id],
          state: "sent",
          undo_until: until,
          inserted_at: now
        }
      ])

      prune(now)

      record(rc, %{
        write_id: write_id,
        conv: conv,
        name: name,
        recipient: recipient,
        user: user,
        request_id: a[:request_id],
        state: "sent",
        undo_until: until,
        at: now
      })

      record_outcome(user, "sent", System.monotonic_time(:millisecond) - t0)
      {:ok, %{write_id: write_id, message_id: mid, undo_until: until}}
    else
      {:ok, _} -> fail(user, t0, :no_ack)
      {:error, reason} -> fail(user, t0, reason)
      _ -> fail(user, t0, :failed)
    end
  rescue
    e ->
      Logger.warning("call_send: send failed: #{inspect(e.__struct__)}")
      {:error, :failed}
  end

  def send(_), do: {:error, :bad_args}

  defp fail(user, t0, reason) do
    record_outcome(user, "declined", System.monotonic_time(:millisecond) - t0)
    {:error, reason}
  end

  defp risi_chat(user) do
    case RisiMe.RisiChat.of_owner(user) do
      %{id: id} -> id
      _ -> nil
    end
  rescue
    _ -> nil
  end

  # The §42.2 `message_sent` record in the caller's Risi chat (never the text).
  defp record(nil, _r), do: :ok

  defp record(rc, r) do
    body =
      case r.state do
        "sent" -> "✓ Sent to #{r.name} (Official) by Risi · Undo"
        "undone" -> "Undone: the message to #{r.name} was deleted."
      end

    risi =
      %{
        "kind" => "message_sent",
        "request_id" => r.request_id,
        "write_id" => r.write_id,
        "conversation_id" => r.conv,
        "to" => %{"name" => r.name, "user_id" => r.recipient},
        "tab" => "official",
        "state" => r.state,
        "via" => "risi",
        "turn_ref" => nil,
        "call_ref" => nil,
        "made_by" => MadeBy.rule(r.at),
        "notify" => [r.user]
      }
      |> then(fn m ->
        if r.state == "sent", do: Map.put(m, "undo_until", ts(r.undo_until)), else: m
      end)

    case Out.post(rc, body, risi) do
      {:ok, _} -> :ok
      e -> e
    end
  end

  defp ts(%DateTime{} = d),
    do: d |> DateTime.truncate(:millisecond) |> DateTime.to_iso8601()

  ## Undo

  @doc """
  Undoes `user`'s send `write_id` (or `:last`: the newest one still undoable): Risi deletes its
  post for everyone and posts `message_sent` `undone`. `{:ok, %{name}}`, `{:error, :expired}`
  (after `undo_until`, or already undone) or `{:error, :none}`.
  """
  def undo(user, which) when is_binary(user) do
    t0 = System.monotonic_time(:millisecond)
    now = DateTime.utc_now()

    case row(user, which) do
      nil ->
        {:error, :none}

      %{state: "sent", undo_until: until} = r ->
        if DateTime.compare(now, until) == :lt do
          case Out.delete(r.conversation_id, [r.message_id]) do
            :ok ->
              Repo.update_all(
                from(s in "risi_call_sends",
                  where: s.write_id == type(^r.write_id, Ecto.UUID) and s.state == "sent"
                ),
                set: [state: "undone"]
              )

              record(r.risi_chat_id, %{
                write_id: r.write_id,
                conv: r.conversation_id,
                name: r.name,
                recipient: r.recipient_user_id,
                user: user,
                request_id: r.request_id,
                state: "undone",
                undo_until: until,
                at: now
              })

              record_outcome(user, "undone", System.monotonic_time(:millisecond) - t0)
              {:ok, %{name: r.name}}

            _ ->
              {:error, :failed}
          end
        else
          {:error, :expired}
        end

      _ ->
        {:error, :expired}
    end
  rescue
    _ -> {:error, :none}
  end

  def undo(_user, _which), do: {:error, :none}

  @doc "True when `user` has a send still inside its Undo window."
  def undoable?(user) when is_binary(user) do
    case row(user, :last) do
      %{state: "sent", undo_until: until} -> DateTime.compare(DateTime.utc_now(), until) == :lt
      _ -> false
    end
  rescue
    _ -> false
  end

  def undoable?(_), do: false

  @select [
    :write_id,
    :user_id,
    :recipient_user_id,
    :name,
    :conversation_id,
    :message_id,
    :risi_chat_id,
    :request_id,
    :state,
    :undo_until
  ]

  defp row(user, :last) do
    from(s in "risi_call_sends",
      where: s.user_id == type(^user, Ecto.UUID),
      order_by: [desc: s.inserted_at],
      limit: 1,
      select: map(s, ^@select)
    )
    |> Repo.one()
    |> cast_row()
  end

  defp row(user, write_id) when is_binary(write_id) do
    case Ecto.UUID.cast(write_id) do
      {:ok, wid} ->
        from(s in "risi_call_sends",
          where: s.write_id == type(^wid, Ecto.UUID) and s.user_id == type(^user, Ecto.UUID),
          select: map(s, ^@select)
        )
        |> Repo.one()
        |> cast_row()

      :error ->
        nil
    end
  end

  defp cast_row(nil), do: nil

  defp cast_row(r) do
    load = fn
      nil -> nil
      b -> Ecto.UUID.load!(b)
    end

    %{
      r
      | write_id: load.(r.write_id),
        user_id: load.(r.user_id),
        recipient_user_id: load.(r.recipient_user_id),
        # A schemaless read returns a NaiveDateTime (UTC).
        undo_until: utc(r.undo_until)
    }
  end

  defp utc(%NaiveDateTime{} = n), do: DateTime.from_naive!(n, "Etc/UTC")
  defp utc(%DateTime{} = d), do: d

  defp prune(now) do
    cut = DateTime.add(now, -86_400, :second)
    Repo.delete_all(from s in "risi_call_sends", where: s.inserted_at < ^cut)
  rescue
    _ -> :ok
  end

  ## Undo from the Risi chat (§41.11): the client action and a typed "undo that"

  @doc """
  The client action `undo_send` (`target` = write id) from `user` (the action's sender, its own
  leaf) in `conv`: honoured only in `user`'s own Risi chat for `user`'s own send. Always `:ok`
  (an Oban result: a refused or late action is ignored).
  """
  def act(conv, user, %{"action" => "undo_send", "target" => target}) when is_binary(target) do
    with true <- RisiMe.Groups.Tabs.risi_chat?(conv),
         ^user <- RisiMe.RisiChat.owner(conv) do
      _ = undo(user, target)
    end

    :ok
  rescue
    _ -> :ok
  end

  def act(_conv, _user, _env), do: :ok

  @undo_words ~r/^\W*(?:(?:please|risi|rishi|ok(?:ay)?|no)[,!.]?\s+)*(?:undo|unsend|take back)(?:\s+(?:that|it|the (?:last )?(?:message|send)|my (?:last )?message|that message))?(?:\s+(?:please|now))?\W*$/iu

  @doc """
  True for a typed (or spoken) "undo that" while `user` has a send in its Undo window (a narrow
  phrase; anything longer is a normal request).
  """
  def typed_undo?(text, user) when is_binary(text),
    do: Regex.match?(@undo_words, text) and undoable?(user)

  def typed_undo?(_text, _user), do: false

  @doc "The line Risi says or posts for an undo result."
  def undo_line({:ok, %{name: n}}), do: "Undone: the message to #{n} was deleted."

  def undo_line({:error, :expired}),
    do: "It's too late to undo that one: the 2 minutes are over."

  def undo_line(_), do: "There's no message of mine to undo."

  ## The learning log

  @doc "One `call_send` row: the outcome and the latency only (never the text)."
  def record_outcome(_user, outcome, ms) when outcome in ~w(sent undone needs_tap declined) do
    LearningLog.record(%{
      call_id: TimeUUID.generate(),
      conversation_id: "call_send",
      chat_id: "call_send",
      task: "call_send",
      model_alias: "rule",
      model: "none",
      provider: "risime",
      status: "ok",
      latency_ms: ms,
      source_message_ids: [],
      meta: %{"outcome" => outcome},
      output: nil
    })

    :ok
  rescue
    e ->
      Logger.warning("call_send: learning-log row not written: #{inspect(e.__struct__)}")
      :ok
  catch
    _, _ -> :ok
  end

  def record_outcome(_user, _outcome, _ms), do: :ok
end
