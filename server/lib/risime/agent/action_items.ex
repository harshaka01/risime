defmodule RisiMe.Agent.ActionItems do
  @moduledoc """
  Risi voice → action items (contract v1.42 §38, decision 082). **One pipeline:** the §27.2
  extraction (one `extract_actions` model call, a `risi_discussions` row, ledger items as
  `risi_commitments`, the §27.5 life cycle and `item_update`s) run on Official call transcripts
  (§36) and Official voice-note transcripts (§37.6). Never Private, never a Risi chat.

  * **Switch** (§38.1): `RISI_ACTION_ITEMS=on` (default off) and the Commitment Ledger on
    (`RISI_LEDGER`). Off: §36.7 runs unchanged and voice notes are not extracted.
  * **Triggers** (§38.2): a call's §36.7 trigger (`from_call/3`, from
    `CallTranscribe.Summary`); a voice note's `voice_transcript` (`ok` or `unclear`) of ≥ 5 s
    whose sender allows it (`voice_transcribed/5` → a job → `from_voice/2`).
  * **Opt-out** (§38.6): a speaker whose `/me/risi/voice` `action_items` is false or who opted
    out of memory (§35.3) is removed from the input; they may be a counterpart, never an owner.
  * **Quality gate** (§38.2): the source's majority-language probability under 0.6
    (`low_confidence`), more than 25 % of its Sinhala turns romanised (`script_check`), or an
    unclear voice note (its reason) → an `action_items` card with `status: "unclear"` and no
    items, and no model call. A clear source with no items posts nothing.
  * **Cards** (§38.3–§38.4): `action_items` in the Official conversation (silent, all items,
    each with its line's `source_ref`); `action_confirm` per owner with a `risi_ledger` device in
    their Risi chat (held 24 h like a §27.3 copy when the Risi chat isn't active), 48 h. Owners
    without one keep their items on the Official card only; they expire.
  * **Storage** (§38.6): `promise`/`task`/`meeting` with an owner are ledger items
    (`risi_commitments`, sealed, `kind`, `source_ref`, `lang`); the others are
    `risi_action_extras` (sealed, 30 days, `prune/0`). The raw transcript lives only in the
    sealed 24-h buffer and is deleted after the extraction by the caller.
  * **Learning log** (§38.7): the extraction's own row (`task: "extract_actions"`) carries the
    source ids, language and input sizes in `meta` and **counts** per kind and owner as output,
    never text; `feedback/3` writes one `action_feedback` row per owner action.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{
    Clock,
    Commitment,
    Commitments,
    Discussion,
    LearningLog,
    Ledger,
    LedgerOut,
    LLM,
    MadeBy,
    Out,
    Prompts,
    Seal,
    Secretary,
    Transcript
  }

  alias RisiMe.Agent.CallTranscribe.{Session, Summary}
  alias RisiMe.{Devices, Repo, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @kinds ~w(promise task meeting decision question number)
  @ledger_kinds ~w(promise task meeting)
  @min_p 0.6
  @max_romanised 0.25
  @voice_min_ms 5_000
  @max_items 15
  @extra_s 30 * 86_400
  @extras "risi_action_extras"

  ## Switch

  # PART 9: `RISI_ACTION_ITEMS` = off | canary | on, read at runtime (`RisiMe.Agents.Flags`).
  @flag "RISI_ACTION_ITEMS"

  @doc "`:off | :canary | :on` (`canary` = on only for `RISI_DEV_USERS`)."
  def mode, do: RisiMe.Agents.Flags.feature(@flag)

  @doc "True while `RISI_ACTION_ITEMS=on` (for everyone; `canary` is not)."
  def enabled?, do: mode() == :on

  @doc "On: the switch and the Commitment Ledger (§38.1)."
  def on?, do: enabled?() and RisiMe.Risi.ledger_on?()

  @doc """
  On for a source whose speakers are `users`: the switch on (or `canary` with a dev user among
  them) and the Commitment Ledger on.
  """
  def on_for?(users),
    do: RisiMe.Agents.Flags.feature_for(@flag, users) and RisiMe.Risi.ledger_on?()

  @doc """
  PART 9: true when §38 makes the action items of this Official voice note (an `ok` transcript of
  `sender`, `duration_ms` long), so the secretary leaves its promises to §38 (no double offer).
  """
  def handles_voice?(conv, sender, duration_ms) do
    on_for?([sender]) and is_integer(duration_ms) and duration_ms >= @voice_min_ms and
      not RisiMe.Groups.Tabs.risi_chat?(conv) and speaker_ok?(sender, conv)
  rescue
    _ -> false
  end

  @doc """
  `/auth/config` `risi_action_items` (§38.1): on, and Risi can post. Under `canary` the
  (unauthenticated) reply says off.
  """
  def config_on? do
    on?() and Out.ready?()
  rescue
    _ -> false
  end

  @doc "The kinds of §38.3."
  def kinds, do: @kinds

  ## Who may be extracted from (§38.6)

  @doc "True unless `user` turned `action_items` off or opted out of memory in `conv`."
  def speaker_ok?(user, conv) do
    RisiMe.Agent.Voice.setting(user).action_items != false and
      RisiMe.Agent.Memory.learning_enabled?(user, conv)
  end

  ## Triggers

  @doc """
  §38.2: a voice note's transcript was posted in Official. Enqueues the extraction (ids only)
  when the switch is on, the transcript is `ok` or `unclear`, the note is ≥ 5 s and its sender
  allows it.
  """
  def voice_transcribed(conv, note_id, sender, status, duration_ms) do
    if on_for?([sender]) and status in ["ok", "unclear"] and is_integer(duration_ms) and
         duration_ms >= @voice_min_ms and not RisiMe.Groups.Tabs.risi_chat?(conv) and
         speaker_ok?(sender, conv) do
      %{"kind" => "voice_actions", "conv" => conv, "message_id" => note_id}
      |> Job.new(queue: :risi, unique: [period: 3600, keys: [:kind, :message_id]])
      |> Oban.insert()
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi action items: voice note not queued: #{inspect(e.__struct__)}")
      :ok
  end

  @doc "The voice-note extraction (a job). Oban result."
  def from_voice(conv, note_id) do
    with true <- in_scope?(conv),
         %{"type" => "voice_transcript", "__sender" => sender} = env <-
           Secretary.envelope(conv, note_id),
         true <- speaker_ok?(sender, conv) do
      src = voice_source(conv, note_id, env)

      result =
        case env["status"] do
          "unclear" -> post_unclear(src, env["reason"] || "no_speech")
          "ok" -> run(src, voice_lines(src, env))
          _ -> :ok
        end

      if result == :ok, do: Transcript.delete(conv, [note_id])
      result
    else
      _ -> :ok
    end
  end

  @doc false
  # The source of a voice note (its buffered working copy `env`, §37.6).
  def voice_source(conv, note_id, env) do
    at = TimeUUID.to_datetime(note_id)
    sender = env["__sender"]

    %{
      conv: conv,
      source: "voice_note",
      ref: ref("voice_note", nil, nil, note_id, nil, nil, at),
      participants: [sender],
      speaker: sender,
      note_id: note_id,
      call_id: nil,
      session_id: nil,
      started_at: at,
      ended_at: DateTime.add(at, env["duration_ms"] || 0, :millisecond),
      duration_s: div(env["duration_ms"] || 0, 1000),
      also: Summary.also([%{model: env["model"]}]),
      lang: env["language"] || "en",
      audio_s: Float.round((env["duration_ms"] || 0) / 1000, 1)
    }
  end

  @doc false
  def voice_lines(src, env) do
    for p <- parts(env["parts"], env["text"]),
        do: line(src, src.speaker, p["t"], env["language"], p["o"], nil, src.started_at)
  end

  @doc """
  The call extraction (§36.7 trigger, from `CallTranscribe.Summary`): `rows` are the session's
  buffered turns (`Summary.segments/2`). Oban result.
  """
  def from_call(conv, %Session{} = s, rows) do
    if in_scope?(conv) and rows != [] do
      src = call_source(conv, s, rows)
      consenting = Enum.filter(rows, &speaker_ok?(&1.speaker, conv))

      cond do
        consenting == [] -> :ok
        reason = gate(rows) -> post_unclear(src, reason)
        true -> run(src, call_lines(src, consenting))
      end
    else
      :ok
    end
  end

  @doc false
  def call_source(conv, s, rows) do
    start = s.started_at || hd(rows).start

    %{
      conv: conv,
      source: "call",
      ref: ref("call", s.call_id, s.session_id, nil, nil, nil, start),
      participants: rows |> Enum.map(& &1.speaker) |> Enum.uniq(),
      speaker: nil,
      note_id: nil,
      call_id: s.call_id,
      session_id: s.session_id,
      started_at: start,
      ended_at: s.ended_at || List.last(rows).start,
      duration_s: Summary.duration_s(s),
      also: Summary.also(rows),
      lang: Summary.majority(rows),
      audio_s: Float.round(Enum.sum(Enum.map(rows, & &1.ms)) / 1000, 1),
      turns: length(rows)
    }
  end

  @doc false
  # The prompt lines of the turns of speakers who allow extraction (§38.6).
  def call_lines(src, rows) do
    for r <- rows,
        speaker_ok?(r.speaker, src.conv),
        p <- parts(r.parts, r.text),
        do: line(src, r.speaker, p["t"], r.language, p["o"], r.turn, r.start)
  end

  defp in_scope?(conv),
    do: RisiMe.Agent.official?(conv) and not RisiMe.Groups.Tabs.risi_chat?(conv)

  defp parts([_ | _] = parts, _text), do: Enum.filter(parts, &is_binary(&1["t"]))
  defp parts(_, text) when is_binary(text), do: [%{"o" => 0, "t" => text}]
  defp parts(_, _), do: []

  # One prompt line: a synthetic id (its time is the line's) mapped back to its SourceRef.
  defp line(src, speaker, text, language, offset, turn, base) do
    at = DateTime.add(base, offset || 0, :millisecond)

    %{
      message_id: TimeUUID.at(at),
      sender_id: speaker,
      text: text,
      language: language,
      ref: ref(src.source, src.call_id, src.session_id, src.note_id, turn, offset || 0, at)
    }
  end

  defp ref(type, call_id, session_id, message_id, turn, offset, at),
    do: %{
      "type" => type,
      "call_id" => call_id,
      "session_id" => session_id,
      "message_id" => message_id,
      "turn" => turn,
      "offset_ms" => offset,
      "at" => Clock.ts(at)
    }

  ## The quality gate (§38.2)

  @doc """
  The call's quality gate over its turns (`%{language, ms, p, romanised}`): nil (clear),
  `"low_confidence"` or `"script_check"`.
  """
  def gate(rows) do
    major = Summary.majority(rows)
    scored = Enum.filter(rows, &(&1.language == major and is_number(&1[:p])))
    ms = scored |> Enum.map(& &1.ms) |> Enum.sum()
    p = if ms > 0, do: Enum.sum(Enum.map(scored, &(&1.p * &1.ms))) / ms
    si = Enum.filter(rows, &(&1.language == "si"))
    romanised = Enum.count(si, &(&1[:romanised] == true))

    cond do
      is_number(p) and p < @min_p -> "low_confidence"
      si != [] and romanised / length(si) > @max_romanised -> "script_check"
      true -> nil
    end
  end

  ## The extraction

  defp run(_src, []), do: :ok

  defp run(src, lines) do
    case extract(src, lines) do
      {:ok, %{items: []}} -> :ok
      {:ok, found} -> store_and_publish(src, found)
      {:error, :rate_limited} -> {:snooze, 30}
      {:error, reason} -> {:error, reason}
    end
  end

  @doc """
  The §27.2 extraction with kinds and line references over `lines` (`%{message_id, sender_id,
  text, language, ref}`): `{:ok, %{items, summary, lang, call_ref}}` (items checked against the
  conversation's members; opted-out speakers are never owners) or `{:error, reason}`. Stores and
  posts nothing (the dev preview uses it as it is).
  """
  def extract(src, lines) do
    conv = src.conv
    members = Secretary.members(conv)
    tzs = Clock.user_tzs(Enum.map(members, & &1.user_id))
    tz = Secretary.chat_tz(conv)
    {text, refs} = Prompts.render(members, lines, tz, tzs: tzs, now: Clock.now())
    by_id = Map.new(lines, &{&1.message_id, &1})
    allowed = members |> Enum.map(& &1.user_id) |> Enum.filter(&speaker_ok?(&1, conv))

    req = %{
      task: "extract_actions",
      conversation_id: conv,
      chat_id: Secretary.chat_id(conv),
      system: Prompts.action_system(src.source),
      user: text,
      schema_name: "action_items",
      schema: Prompts.action_schema(),
      source_message_ids: if(src.note_id, do: [src.note_id], else: []),
      max_tokens: 2_000,
      confidence: &confidence/1,
      meta: %{
        "source" => src.source,
        "call_id" => src.call_id,
        "session_id" => src.session_id,
        "message_id" => src.note_id,
        "lang" => src.lang,
        "turns" => src[:turns],
        "lines" => length(lines),
        "audio_s" => src.audio_s
      },
      # §38.7: counts per kind and per owner, never text.
      log_output: &counts(&1, refs)
    }

    case LLM.complete(req) do
      {:ok, %{call_ref: call_ref, output: out}} ->
        items =
          (out["items"] || [])
          |> Enum.map(
            &item(
              &1,
              refs,
              by_id,
              MapSet.new(allowed),
              MapSet.new(members, fn m -> m.user_id end),
              tzs,
              tz
            )
          )
          |> Enum.reject(&is_nil/1)
          |> Enum.uniq_by(&{&1.kind, &1.owner, String.downcase(&1.text)})
          |> Enum.take(@max_items)

        summary =
          if src.source == "call",
            do:
              (out["summary"] || "")
              |> to_string()
              |> String.trim()
              |> String.slice(0, 280)
              |> nonempty()

        {:ok, %{items: items, summary: summary, lang: src.lang, call_ref: call_ref}}

      {:error, reason} ->
        {:error, reason}
    end
  end

  defp nonempty(""), do: nil
  defp nonempty(s), do: s

  defp confidence(%{"items" => [_ | _] = is}),
    do: is |> Enum.map(&(&1["confidence"] || 0)) |> Enum.min() |> Kernel./(1)

  defp confidence(_), do: 1.0

  defp counts(out, refs) do
    items = out["items"] || []

    Jason.encode!(%{
      "items" => length(items),
      "by_kind" => Enum.frequencies_by(items, & &1["kind"]),
      "by_owner" => Enum.frequencies_by(items, &(refs.users[&1["owner"]] || "none"))
    })
  end

  # One model item checked: a known kind, 1–200 characters, a line it came from, an owner only for
  # promise/task/meeting among the members who allow extraction.
  defp item(i, refs, by_id, allowed, members, tzs, tz) do
    text = i["text"] |> to_string() |> String.trim()
    kind = i["kind"]

    line =
      (i["source"] || [])
      |> Enum.map(&by_id[refs.messages[&1]])
      |> Enum.find(& &1)

    cond do
      kind not in @kinds or text == "" or line == nil ->
        nil

      (i["confidence"] || 0) < Application.get_env(:risime, :risi_min_confidence, 0.6) ->
        nil

      true ->
        owner =
          case refs.users[i["owner"]] do
            o when kind in @ledger_kinds and is_binary(o) ->
              if MapSet.member?(allowed, o), do: o

            _ ->
              nil
          end

        dtz = if owner, do: Map.get(tzs, owner, tz), else: tz
        {due, all_day} = Ledger.due(i["due_local"], dtz)

        %{
          item_id: Ecto.UUID.generate(),
          kind: kind,
          text: String.slice(text, 0, 200),
          owner: owner,
          counterpart:
            (i["counterparts"] || [])
            |> Enum.map(&refs.users[&1])
            |> Enum.reject(&(is_nil(&1) or &1 == owner))
            |> Enum.filter(&MapSet.member?(members, &1))
            |> Enum.uniq(),
          due: due,
          all_day: all_day,
          due_text: i["due_text"] && String.slice(i["due_text"], 0, 100),
          lang: if(i["lang"] in ~w(en si ta), do: i["lang"], else: line.language || "en"),
          source_ref: line.ref,
          confidence: (i["confidence"] || 0) / 1
        }
    end
  end

  @doc "True for an item that is a ledger item (§38.3): promise/task/meeting with an owner."
  def ledger_item?(%{kind: k, owner: o}), do: k in @ledger_kinds and is_binary(o)

  ## Storing and publishing

  defp store_and_publish(src, found) do
    conv = src.conv
    now = Clock.usec(Clock.now())
    summary_id = Ecto.UUID.generate()
    {ledger, extras} = Enum.split_with(found.items, &ledger_item?/1)
    owners = ledger |> Enum.map(& &1.owner) |> Enum.uniq()

    active = conv |> Secretary.members() |> MapSet.new(& &1.user_id)

    recipients =
      (src.participants ++ owners) |> Enum.uniq() |> Enum.filter(&MapSet.member?(active, &1))

    d = %Discussion{
      summary_id: summary_id,
      conversation_id: conv,
      chat_id: Secretary.chat_id(conv),
      source: src.source,
      call_id: src.call_id,
      media: if(src.source == "call", do: "audio"),
      duration_s: src.duration_s,
      started_at: Clock.usec(src.started_at),
      ended_at: Clock.usec(src.ended_at || src.started_at),
      participants: src.participants,
      recipients: recipients,
      key_points: [],
      summary: found.summary || "",
      items_count: length(found.items),
      call_ref: found.call_ref,
      made_by: MadeBy.build(found.call_ref, [], src.also),
      created_at: now,
      source_ref: src.ref,
      note_message_id: src.note_id,
      lang: found.lang
    }

    with {:ok, d} <- Discussion.seal(d),
         {:ok, rows} <- ledger_rows(d, ledger, now),
         {:ok, extra_rows} <- extra_rows(d, extras, now) do
      Repo.transaction(fn ->
        Repo.insert!(d)
        Enum.each(rows, &Repo.insert!/1)
        if extra_rows != [], do: Repo.insert_all(@extras, extra_rows)
      end)

      for r <- rows do
        %{"kind" => "item_expire", "conv" => conv, "item_id" => r.id}
        |> Job.new(queue: :risi_timers, schedule_in: Ledger.expire_s())
        |> Oban.insert!()
      end

      publish(d, found, src)
    else
      :error ->
        # No data key: nothing derived can be kept sealed, so nothing is kept or posted.
        Logger.warning("Risi action items not kept in #{conv}: missing_key RISI_DATA_KEY")
        :ok
    end
  end

  defp ledger_rows(d, items, now) do
    Seal.open_each(items, fn i ->
      %Commitment{
        id: i.item_id,
        conversation_id: d.conversation_id,
        chat_id: d.chat_id,
        state: "proposed",
        item_state: "proposed",
        summary_id: d.summary_id,
        source: d.source,
        all_day: i.all_day,
        text: i.text,
        owner_id: i.owner,
        counterpart_ids: i.counterpart,
        due: i.due && Clock.usec(i.due),
        due_kind: if(i.due, do: if(i.all_day, do: "date", else: "datetime")),
        due_text: i.due_text,
        source_message_ids: [],
        confidence: i.confidence,
        call_ref: d.call_ref,
        proposed_at: now,
        needs_clarification: RisiMe.Agent.Offers.vague?(i.due, i.due_text),
        kind: i.kind,
        source_ref: i.source_ref,
        lang: i.lang
      }
      |> Commitment.seal()
    end)
  end

  defp extra_rows(d, items, now) do
    Seal.open_each(items, fn i ->
      with {:ok, sealed} <- Seal.seal_text(@extras, i.item_id, "text", i.text),
           {:ok, claim} <- claim_fields(i) do
        {:ok,
         Map.merge(
           %{
             id: Ecto.UUID.dump!(i.item_id),
             summary_id: Ecto.UUID.dump!(d.summary_id),
             conversation_id: d.conversation_id,
             kind: i.kind,
             text_sealed: sealed,
             lang: i.lang,
             source_ref: i.source_ref,
             expires_at: DateTime.add(now, @extra_s, :second),
             inserted_at: now
           },
           claim
         )}
      else
        _ -> :error
      end
    end)
  end

  # v1.43 §38.9: an unowned promise/task/meeting keeps what its claimer's ledger item needs.
  defp claim_fields(%{kind: k} = i) when k in @ledger_kinds do
    case Seal.seal_text(@extras, i.item_id, "due_text", i.due_text) do
      {:ok, sealed} ->
        {:ok,
         %{
           due: i.due && Clock.usec(i.due),
           all_day: i.all_day == true,
           due_text_sealed: sealed,
           counterpart_ids: Enum.map(i.counterpart, &Ecto.UUID.dump!/1),
           confidence: i.confidence
         }}

      _ ->
        :error
    end
  end

  defp claim_fields(_i), do: {:ok, %{}}

  @doc "True for an item any member may claim (v1.43 §38.9): promise/task/meeting, no owner."
  def claimable?(%{kind: k, owner: o}), do: k in @ledger_kinds and o == nil

  @doc "The wire `ActionItem` of a found item (§38.3; v1.43 `claim` on unowned ones)."
  def item_json(i) do
    base = %{
      "item_id" => i.item_id,
      "kind" => i.kind,
      "text" => i.text,
      "owner" => i.owner,
      "counterpart" => i.counterpart,
      "due" => Clock.ts(i.due),
      "all_day" => i.all_day == true,
      "due_text" => i.due_text,
      "lang" => i.lang,
      "state" => if(ledger_item?(i), do: "proposed"),
      "source_ref" => i.source_ref
    }

    if claimable?(i), do: Map.put(base, "claim", true), else: base
  end

  defp publish(d, found, src) do
    names = Map.new(Secretary.members(d.conversation_id), &{&1.user_id, &1.name})
    {body, risi} = card(d, found.items, src, names)

    case Out.post(d.conversation_id, body, risi) do
      {:ok, _} -> :ok
      {:error, reason} -> Logger.warning("Risi action items card not posted: #{inspect(reason)}")
    end

    ledger = MapSet.new(Devices.risi_ledger_users(d.recipients))

    for {owner, items} <- found.items |> Enum.filter(&ledger_item?/1) |> Enum.group_by(& &1.owner),
        MapSet.member?(ledger, owner) do
      {body, risi} = confirm_card(d, owner, items, src, names)
      LedgerOut.deliver_or_hold(d, owner, body, risi)
    end

    :ok
  end

  @doc "The Official `action_items` card (§38.3): `{body, risi}`."
  def card(d, items, src, names) do
    risi = %{
      "kind" => "action_items",
      "summary_id" => d.summary_id,
      "conversation_id" => d.conversation_id,
      "source" => d.source,
      "source_ref" => d.source_ref,
      "status" => "ok",
      "reason" => nil,
      "summary" => if(d.source == "call", do: nonempty(d.summary || "")),
      "lang" => d.lang || "en",
      "items" => Enum.map(items, &item_json/1),
      "call_ref" => d.call_ref,
      "made_by" => d.made_by,
      "notify" => []
    }

    n = length(items)
    noun = if n == 1, do: "item", else: "items"
    place = place(src, names)

    tail =
      if d.source == "call", do: " Owners confirm theirs in their Risi chat.", else: ""

    {"Risi found #{n} #{noun} in #{place}: #{tally(items)}.#{tail}#{ask_owner(items)}", risi}
  end

  # v1.43 §38.9 point 1: Risi asks who takes an unowned item (never guesses a name).
  defp ask_owner(items) do
    case Enum.filter(items, &claimable?/1) do
      [] -> ""
      [i] -> " Who's taking \"#{i.text}\"? Tap \"It's mine\" on the card."
      l -> " #{length(l)} items have no owner yet: tap \"It's mine\" on yours."
    end
  end

  defp place(%{source: "call"}, _names), do: "this call"
  defp place(src, names), do: "#{names[src.speaker] || "a member"}'s voice note"

  defp tally(items) do
    freq = Enum.frequencies_by(items, & &1.kind)

    @kinds
    |> Enum.filter(&Map.has_key?(freq, &1))
    |> Enum.map_join(", ", fn k -> "#{freq[k]} #{plural(k, freq[k])}" end)
  end

  defp plural(k, 1), do: k
  defp plural(k, _), do: k <> "s"

  defp post_unclear(src, reason) do
    %{body: body, risi: risi} = unclear_card(src, reason)

    case Out.post(src.conv, body, risi) do
      {:ok, _} -> :ok
      {:error, :rate_limited} -> {:snooze, 10}
      {:error, _} -> :ok
    end
  end

  @doc "The `status: \"unclear\"` card (§38.2): `%{body, risi}`."
  def unclear_card(src, reason) do
    body = unclear_text(src.lang)

    risi = %{
      "kind" => "action_items",
      "summary_id" => Ecto.UUID.generate(),
      "conversation_id" => src.conv,
      "source" => src.source,
      "source_ref" => src.ref,
      "status" => "unclear",
      "reason" => reason,
      "summary" => nil,
      "lang" => src.lang || "en",
      "items" => [],
      "call_ref" => nil,
      "made_by" => Map.put(MadeBy.rule(), "also", src.also),
      "notify" => []
    }

    %{body: body, risi: risi}
  end

  @doc """
  v1.43 §38.9 point 6: the unclear card's text, for every reason, in the source's language
  (English for any other).
  """
  def unclear_text("si"), do: "මට මෙය පැහැදිලිව ඇසුණේ නැහැ."
  def unclear_text("ta"), do: "இதை என்னால் தெளிவாகக் கேட்க முடியவில்லை."
  def unclear_text(_), do: "I couldn't hear this clearly."

  @doc "The card an extraction would post (the dev preview: nothing stored): `%{body, risi}`."
  def preview_card(src, found) do
    names = Map.new(Secretary.members(src.conv), &{&1.user_id, &1.name})

    d = %Discussion{
      summary_id: Ecto.UUID.generate(),
      conversation_id: src.conv,
      source: src.source,
      source_ref: src.ref,
      summary: found.summary || "",
      call_ref: found.call_ref,
      made_by: MadeBy.build(found.call_ref, [], src.also),
      lang: found.lang
    }

    case found.items do
      [] -> %{body: "(no items: nothing would be posted)", risi: elem(card(d, [], src, names), 1)}
      items -> card(d, items, src, names) |> then(fn {b, r} -> %{body: b, risi: r} end)
    end
  end

  @doc "The owner's `action_confirm` card (§38.4): `{body, risi}`."
  def confirm_card(d, owner, items, src, names) do
    tz = Clock.user_tz(owner)
    expires = DateTime.add(d.created_at, Ledger.expire_s(), :second)

    risi = %{
      "kind" => "action_confirm",
      "summary_id" => d.summary_id,
      "chat_id" => d.chat_id,
      "conversation_id" => d.conversation_id,
      "source" => d.source,
      "source_ref" => d.source_ref,
      "for" => [owner],
      "lang" => d.lang || "en",
      "items" => Enum.map(items, &Map.put(item_json(&1), "buttons", buttons(&1))),
      "expires_at" => Clock.ts(expires),
      # v1.43 §38.9 point 5: informative; [Remind me] never turns the skill on.
      "reminders_skill" => reminders_skill(owner),
      "call_ref" => d.call_ref,
      "made_by" => d.made_by,
      "notify" => [owner]
    }

    hm = d.started_at |> Clock.local(tz) |> Calendar.strftime("%H:%M")

    from =
      case src.source do
        "call" ->
          case src.participants -- [owner] do
            # The owner was the only speaker (name_list/2 of [] raised).
            [] -> "From your call (#{hm})"
            others -> "From your call with #{LedgerOut.name_list(others, names)} (#{hm})"
          end

        _ ->
          if src.speaker == owner,
            do: "From your voice note (#{hm})",
            else: "From #{names[src.speaker] || "a member"}'s voice note (#{hm})"
      end

    n = length(items)

    lines =
      Enum.map_join(items, "\n", fn i ->
        "• #{i.text}#{LedgerOut.due_suffix(item_json(i), tz)}"
      end)

    {"#{from}: #{n} #{if n == 1, do: "item", else: "items"} for you.\n#{lines}\nConfirm in RisiMe.",
     risi}
  end

  # v1.43 §38.9: `add_calendar` for a meeting, an undated item (the phone asks with its picker)
  # and a due not in the past, timed or all-day; `remind` always (the §27.6 reminder in the Risi
  # chat needs no skill).
  defp buttons(i) do
    calendar? =
      i.kind == "meeting" or i.due == nil or
        DateTime.compare(i.due, Clock.now()) == :gt

    ["add_promise"] ++
      if(calendar?, do: ["add_calendar"], else: []) ++ ["remind", "not_mine", "edit"]
  end

  # The owner's §26.2 Reminders skill state ("off" | "ask" | "allowed"); nil when unknown.
  defp reminders_skill(owner) do
    if RisiMe.Agent.Skills.gated?(owner) do
      case RisiMe.Agent.Skills.state(owner, "reminders") do
        s when s in ~w(ask allowed) -> s
        _ -> "off"
      end
    else
      "off"
    end
  rescue
    _ -> nil
  end

  ## Owner actions (§38.4): `item_calendar`, `item_remind`, `item_not_mine`

  @doc """
  `item_calendar`: confirms the item (if proposed) and creates exactly one Risi Calendar event
  as §29.8 [Add] (`edit` the §28.4 shape, else 60 min from `due`), with an `event_card`
  `mode: "added"` in the owner's Risi chat. Without a usable time nothing happens (the phone
  sends `edit` from its picker).
  """
  def calendar(rc, %Commitment{} = c, d, user, edit) do
    case event_wire(c, edit, user) do
      {:ok, wire} ->
        add_event(rc, c, d, user, wire)

      :error ->
        # v1.43 §38.9 point 2: no usable time and no picker answer: never guessed, Risi asks.
        RisiMe.Agent.Offers.ask_date(c)
        :ok
    end
  end

  defp add_event(rc, c, d, user, wire) do
    with :ok <- confirm(c, d, user, ask_date: false) do
      unless event_exists?(c.id, user) do
        with true <- RisiMe.Agent.Calendar.calendar_user?(user) || {:error, :off},
             {:ok, attrs} <- RisiMe.Agent.Calendar.parse_new(user, wire),
             {ok_ids, dropped} = RisiMe.Agent.Calendar.invitable(user, c.counterpart_ids),
             {:ok, view} <- RisiMe.Agent.Calendar.create(user, %{attrs | with: ok_ids}) do
          {e, parts} = RisiMe.Agent.Calendar.load(view["event_id"])
          RisiMe.Agent.RisiItems.record_risi_event(user, e)
          RisiMe.Agent.CalendarCards.added(e, parts, rc, user, dropped)
        else
          other -> Logger.warning("Risi action item calendar add failed: #{inspect(other)}")
        end
      end

      feedback(c, user, "calendar")
    else
      _ -> :ok
    end
  end

  defp event_wire(c, edit, user) do
    edit = if is_map(edit), do: edit, else: %{}
    tz = Clock.user_tz(user)

    {start, stop, all_day} =
      case {edit["start"], c.due} do
        {s, _} when is_binary(s) ->
          {s, edit["end"], edit["all_day"] == true}

        {nil, %DateTime{} = due} when not c.all_day ->
          {Clock.ts(due), Clock.ts(DateTime.add(due, 3600, :second)), false}

        # v1.43 §38.9 point 3: an all-day due → an all-day event on that date (owner's zone).
        {nil, %DateTime{} = due} ->
          day = due |> Clock.local(tz) |> NaiveDateTime.to_date()
          at = fn d -> d |> NaiveDateTime.new!(~T[00:00:00]) |> Clock.to_utc(tz) |> Clock.ts() end
          {at.(day), at.(Date.add(day, 1)), true}

        _ ->
          {nil, nil, false}
      end

    stop =
      stop ||
        case start && DateTime.from_iso8601(start) do
          {:ok, s, _} -> Clock.ts(DateTime.add(s, 3600, :second))
          _ -> nil
        end

    if start do
      {:ok,
       %{
         "title" => (edit["title"] || c.text) |> to_string() |> String.slice(0, 200),
         "start" => start,
         "end" => stop,
         "all_day" => all_day,
         "tz" => tz,
         "source" => %{"conversation_id" => c.conversation_id, "item_id" => c.id}
       }}
    else
      :error
    end
  end

  defp event_exists?(item_id, user) do
    Repo.exists?(
      from e in "risi_events",
        where:
          e.source_item_id == type(^item_id, :binary_id) and e.owner == type(^user, :binary_id) and
            e.state == "active"
    )
  end

  @doc """
  `item_remind`: confirms the item (if proposed) and sets a §27.6 reminder at `edit.remind_at`
  (future) or `due − 1 h`.
  """
  def remind(%Commitment{} = c, d, user, edit) do
    at =
      case is_map(edit) && edit["remind_at"] do
        s when is_binary(s) ->
          case DateTime.from_iso8601(s) do
            {:ok, t, _} -> if DateTime.compare(t, Clock.now()) == :gt, do: {:ok, Clock.usec(t)}
            _ -> :error
          end

        _ ->
          if c.due, do: {:ok, DateTime.add(c.due, -3600, :second)}, else: {:ok, nil}
      end

    # v1.43 §38.9 point 2: no date and no time chosen → confirmed, no reminder, and (when the
    # item is still undated after a confirm that didn't ask already) Risi asks for the date.
    with {:ok, at} <- at || :error,
         :ok <- confirm(c, d, user, []) do
      c = Repo.get(Commitment, c.id)

      if at && DateTime.compare(at, Clock.now()) == :gt,
        do: RisiMe.Agent.LedgerReminders.remind_at(c, at)

      if (at == nil and c) && c.due == nil, do: ask_date(c)

      feedback(c, user, "remind")
    else
      _ -> :ok
    end
  end

  defp ask_date(%Commitment{} = c) do
    case Commitment.open(c) do
      {:ok, c} -> RisiMe.Agent.Offers.ask_date(c)
      _ -> 0
    end
  end

  # Confirms a proposed item (as `item_confirm`); a tracked one stays as it is.
  defp confirm(%Commitment{item_state: "proposed"} = c, d, user, opts) do
    RisiMe.Agent.LedgerActions.confirm(c, d, user, opts)
  end

  defp confirm(%Commitment{item_state: s}, _d, _user, _opts) when s in ~w(confirmed edited),
    do: :ok

  defp confirm(_c, _d, _user, _opts), do: :error

  ## Claiming (v1.43 §38.9 point 1)

  @doc """
  `item_claim` from `user` (in their own Risi chat): an unowned promise/task/meeting of an
  extraction becomes `user`'s **proposed** ledger item. Any active human member of the Official
  conversation with a `risi_ledger` device, before the extraction + 48 h; the first claim wins
  (the extras row is taken once), later ones are ignored. Then: the claimer's `action_confirm`
  for that item, an `item_update` (`owner`) to every member, `action_feedback` `claim`. Nothing is
  tracked until the claimer confirms.
  """
  def claim(_rc, user, item_id) do
    now = Clock.usec(Clock.now())

    with {:ok, _} <- Ecto.UUID.cast(item_id),
         %{} = x <- claim_row(item_id),
         true <- x.kind in @ledger_kinds,
         %Discussion{} = d <- Repo.get(Discussion, x.summary_id),
         false <- claim_closed?(d),
         true <- Secretary.active_human?(d.conversation_id, user),
         [^user] <- Devices.risi_ledger_users([user]),
         {:ok, text} <- Seal.open_text(@extras, item_id, "text", x.text_sealed),
         {:ok, due_text} <- Seal.open_text(@extras, item_id, "due_text", x.due_text_sealed),
         item = claimed_item(x, item_id, text, due_text, user),
         {:ok, [row]} <- ledger_rows(d, [item], now),
         {:ok, d} <- take(d, item_id, row, user) do
      %{"kind" => "item_expire", "conv" => d.conversation_id, "item_id" => item_id}
      |> Job.new(
        queue: :risi_timers,
        scheduled_at: DateTime.add(d.created_at, Ledger.expire_s(), :second)
      )
      |> Oban.insert!()

      names = Map.new(Secretary.members(d.conversation_id), &{&1.user_id, &1.name})

      src = %{
        source: d.source,
        participants: d.participants,
        speaker: if(d.source == "voice_note", do: List.first(d.participants))
      }

      {body, risi} = confirm_card(d, user, [item], src, names)
      LedgerOut.deliver_or_hold(d, user, body, risi)

      {:ok, c} = Commitment.open(Repo.get!(Commitment, item_id))
      feedback(c, user, "claim")
      LedgerOut.item_update(c, d, "claimed", user)
    else
      _ -> :ok
    end
  end

  defp claim_row(item_id) do
    Repo.one(
      from x in @extras,
        where: x.id == type(^item_id, :binary_id),
        select: %{
          kind: x.kind,
          summary_id: type(x.summary_id, :binary_id),
          text_sealed: x.text_sealed,
          due_text_sealed: x.due_text_sealed,
          due: type(x.due, :utc_datetime_usec),
          all_day: x.all_day,
          counterpart_ids: type(x.counterpart_ids, {:array, :binary_id}),
          confidence: x.confidence,
          lang: x.lang,
          source_ref: x.source_ref
        }
    )
  end

  defp claim_closed?(%Discussion{created_at: at}),
    do: DateTime.compare(Clock.now(), DateTime.add(at, Ledger.expire_s(), :second)) != :lt

  defp claimed_item(x, item_id, text, due_text, user) do
    %{
      item_id: item_id,
      kind: x.kind,
      text: text,
      owner: user,
      counterpart: (x.counterpart_ids || []) -- [user],
      due: x.due,
      all_day: x.all_day == true,
      due_text: due_text,
      lang: x.lang,
      source_ref: x.source_ref,
      confidence: x.confidence || 0.0
    }
  end

  # The first claim wins: the extras row is deleted once, in the same transaction as the insert.
  defp take(d, item_id, row, user) do
    Repo.transaction(fn ->
      case Repo.delete_all(from x in @extras, where: x.id == type(^item_id, :binary_id)) do
        {1, _} ->
          Repo.insert!(row)

          d
          |> Ecto.Changeset.change(recipients: Enum.uniq(d.recipients ++ [user]))
          |> Repo.update!()

        _ ->
          Repo.rollback(:taken)
      end
    end)
  end

  ## Cancelling (§38.6)

  @doc """
  The voice notes `note_ids` were deleted for everyone, or their transcript by its sender: the
  items extracted from them are `cancelled` (`item_update`) and removed; extras deleted.
  """
  def cancel_notes(conv, note_ids) when is_list(note_ids) do
    ds =
      Repo.all(
        from d in Discussion,
          where: d.conversation_id == ^conv and d.note_message_id in ^note_ids
      )

    for d <- ds do
      by = List.first(d.participants)

      for c <- Repo.all(from c in Commitment, where: c.summary_id == ^d.summary_id),
          {:ok, c} <- [Commitment.open(c)] do
        Commitments.cancel_timers(c.id)
        Repo.delete!(c)
        LedgerOut.item_update(c, d, "cancelled", by)
      end

      Repo.delete_all(from x in @extras, where: x.summary_id == type(^d.summary_id, :binary_id))
    end

    Transcript.delete(conv, note_ids)
    :ok
  rescue
    e ->
      Logger.warning("Risi action items not cancelled: #{inspect(e.__struct__)}")
      :ok
  end

  ## Learning log (§38.7)

  @doc """
  One `action_feedback` row: the item, its kind, the owner's `action` (`confirm` | `edit` |
  `calendar` | `remind` | `not_mine` | `expired`), which fields an edit changed, seconds from
  posting. Never text.
  """
  def feedback(c, user, action, changed \\ [])

  def feedback(%Commitment{kind: kind} = c, _user, action, changed) when is_binary(kind) do
    LearningLog.record(%{
      call_id: TimeUUID.generate(),
      conversation_id: c.conversation_id,
      chat_id: c.chat_id,
      task: "action_feedback",
      model_alias: "none",
      model: "none",
      provider: "risime",
      status: action,
      latency_ms: 0,
      source_message_ids: [],
      meta: %{
        "item_id" => c.id,
        "kind" => kind,
        "action" => action,
        "changed" => changed,
        "seconds" => c.proposed_at && DateTime.diff(Clock.now(), c.proposed_at)
      },
      output: nil
    })

    :ok
  rescue
    e ->
      Logger.warning("Risi action feedback not logged: #{inspect(e.__struct__)}")
      :ok
  end

  def feedback(_c, _user, _action, _changed), do: :ok

  ## Retention

  @doc "The extras after 30 days (§38.6); hourly."
  def prune(now \\ DateTime.utc_now()) do
    Repo.delete_all(from x in @extras, where: x.expires_at <= ^now)
    :ok
  end

  @doc "Official off / Risi removed: the chat's extras (§24.4)."
  def forget(conv) do
    Repo.delete_all(from x in @extras, where: x.conversation_id == ^conv)
    :ok
  end

  @doc "The extras of a summary, opened: `[%{item_id, kind, text, lang, source_ref}]`."
  def extras(summary_id) do
    Repo.all(
      from x in @extras,
        where: x.summary_id == type(^summary_id, :binary_id),
        select: %{
          id: type(x.id, :binary_id),
          kind: x.kind,
          text_sealed: x.text_sealed,
          lang: x.lang,
          source_ref: x.source_ref
        }
    )
    |> Enum.flat_map(fn x ->
      case Seal.open_text(@extras, x.id, "text", x.text_sealed) do
        {:ok, t} when is_binary(t) ->
          [%{item_id: x.id, kind: x.kind, text: t, lang: x.lang, source_ref: x.source_ref}]

        _ ->
          []
      end
    end)
  end
end
