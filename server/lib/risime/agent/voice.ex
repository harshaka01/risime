defmodule RisiMe.Agent.Voice do
  @moduledoc """
  Voice notes for Risi (contract v1.41 §37.6–§37.8, decision 081).

  * **Switch** (§37.1): `RISI_VOICE_TRANSCRIBE=on` (default off). `/auth/config`
    `voice_transcribe` is `on` only while the switch is on, Risi can post and the speech service
    is healthy (`config_on?/0`). It is the kill switch of everything below; voice notes
    themselves work whatever it says.
  * **Where** (§37.0, §37.6): only an Official `grp:` with Official on (where Risi is a visible
    MLS member, `RisiMe.Agent.Conversation` hands the decrypted envelope here), or the user's
    Risi chat (§37.8). **Never Private:** `received/3` refuses any non-Official conversation
    before touching a blob, a key or the speech service (Risi isn't a member there anyway).
  * **When:** a valid §37.2 envelope (else dropped and logged, never stored) of an active human
    member; no `forwarded`; in Official no `transcribe: false` on the note and the sender's
    `/me/risi/voice` `transcribe` not false (either opt-out wins); `duration_ms` ≤ 5 min.
  * **The audio path** (§37.6, §36.4 no-storage rules): in a task, never in the conversation's MLS
    lane. The blob (ciphertext, already in the blob store) is read into memory and checked
    against the envelope (same conversation, size, SHA-256), decrypted **in memory**
    (`RisiMe.Agent.Media`), sent to the speech service on loopback (`RisiMe.Agent.Speech`:
    §36.5 routing, the §36.11 Sinhala prompt, retry and script check), and dropped: the audio,
    the ciphertext and the key are never written to disk, logs or the learning log. A speech
    error is retried once (then `failed`).
  * **The post:** `voice_transcript` from Risi's leaf in the same conversation (silent,
    `notify: []`); a speaker who opted out of memory (§35) is transcribed but not remembered.
    The working copy is buffered sealed (24 h) under the note's id as a `voice_transcript`
    envelope, and `risi_voice_transcripts` maps the note to the post (ids only) for the §37.6
    delete rules.
  * **Understanding** (PART 9): an `ok` Official transcript then goes through the typed-message
    entry points (`Memory.put_message/2`, `Secretary.on_message/2`) with the note's id as the
    source, so memory, the Ledger, the calendar offers, summaries and answers see it like a typed
    message; §38 makes its action items. Deleting the transcript deletes what was learned from it.
  * **Switch modes:** `RISI_VOICE_TRANSCRIBE` = `off` | `canary` | `on` at runtime
    (`RisiMe.Agents.Flags.feature/1`); `canary` transcribes only `RISI_DEV_USERS`' notes.
  * **Risi chat** (§37.8): the note is a request: after the transcript the text is handled as a
    §24.11 `ask` with the note's `request_id` (buffered as a `risi_request` with
    `"via": "voice"` and `lang`, so the answer is in the transcript's language); `unclear` or
    `failed` get the fixed "couldn't hear" answer and no tools run. PART 4d (Harsha
    2026-10-12), never silent: `risi_progress` `working` ("Risi is typing…") goes out as soon
    as the note arrives; the switch off or a note over 5 min get a fixed answer instead of
    silence; a speech service that looks unhealthy is still tried. The model being down is
    the typed-message path's (`RisiMe.Agent.Offline`: the request job's offline notice).
    **The speech service down, busy or out of memory** (proposal 2026-10-14-voice-queue, P1
    hotfix): never "please type it". Risi answers "Got your voice note. I'll reply in a
    moment." and the note waits in `RisiMe.Agent.VoiceQueue` (sealed blob reference + key,
    retried with backoff); it is answered normally once the service is back, or after 30 min
    gets the `failed` card and an honest "I couldn't listen to your voice note…". A user
    outside the switch is told voice notes to Risi aren't on for them yet.
  * **Learning log:** one `transcribe` row per note (model, language, audio seconds, latency,
    status), `output: null`.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{
    Clock,
    Lang,
    LearningLog,
    MadeBy,
    Media,
    Out,
    Secretary,
    Speech,
    SpokenMention,
    Transcript,
    VoiceJobs
  }

  alias RisiMe.{Blobs, Repo, Risi, TimeUUID}

  @max_transcribe_ms 300_000
  @max_text 4_000
  @min_probability 0.6
  @mimes ["audio/ogg; codecs=opus", "audio/mp4"]
  # Proposal 2026-10-14-voice-queue (P1 hotfix, Harsha 2026-10-14): Risi never says "please
  # type it", anywhere. A speech service that is down, busy or out of memory: the note waits in
  # the retry queue (`RisiMe.Agent.VoiceQueue`) behind an immediate acknowledgement; only a
  # bounded give-up says it couldn't listen. A note under 1 s, or one whose transcript is empty
  # or noise only, gets the "very short" line. A user outside `RISI_VOICE_TRANSCRIBE` is told
  # it's coming soon.
  @unclear_answer "I couldn't hear that clearly. Could you say it again?"
  @short_answer "That was very short. Could you say it again?"
  @blob_answer "I couldn't open that voice note. Could you send it again?"
  @queued_answer "Got your voice note. I'll reply in a moment."
  @failed_answer "I couldn't listen to your voice note: my speech service isn't answering. Please send it again a bit later."
  @off_answer "Voice notes to Risi are coming soon for your account."
  @too_long_answer "That voice note is over 5 minutes, too long for me. Could you send a shorter one?"
  @min_ms 1_000

  ## Switches

  # PART 9: `RISI_VOICE_TRANSCRIBE` = off | canary | on, read at runtime (`RisiMe.Agents.Flags`).
  @flag "RISI_VOICE_TRANSCRIBE"

  @doc "`:off | :canary | :on` (`canary` = on only for `RISI_DEV_USERS`)."
  def mode, do: RisiMe.Agents.Flags.feature(@flag)

  @doc "True while `RISI_VOICE_TRANSCRIBE=on` (for everyone; `canary` is not)."
  def enabled?, do: mode() == :on

  @doc "True when the switch is on for `user` (on, or canary and `user` is a dev user)."
  def enabled_for?(user), do: RisiMe.Agents.Flags.feature_for(@flag, user)

  @doc """
  `/auth/config` `voice_transcribe` (§37.1): the switch on, Risi up, the speech service healthy.
  Under `canary` the (unauthenticated) reply says off: Risi doesn't transcribe everyone's notes,
  so no phone is told it does (decision 048 honesty).
  """
  def config_on? do
    enabled?() and Risi.enabled?() and Out.ready?() and Speech.healthy?()
  rescue
    _ -> false
  end

  # §37.6 "voice_transcribe is on" for this sender (the canary-aware `config_on?/0`).
  defp listening_for?(user) do
    enabled_for?(user) and Risi.enabled?() and Out.ready?() and Speech.healthy?()
  rescue
    _ -> false
  end

  ## The sender's setting (§37.7)

  @doc "`%{transcribe: bool, action_items: bool}` of a user (both default true)."
  def setting(user_id) do
    case Repo.one(
           from s in "risi_voice_settings",
             where: s.user_id == type(^user_id, Ecto.UUID),
             select: %{transcribe: s.transcribe, action_items: s.action_items}
         ) do
      nil -> %{transcribe: true, action_items: true}
      s -> s
    end
  end

  @doc "Stores the setting; `{:ok, setting}` or `{:error, :bad_request}`."
  def put_setting(user_id, %{"transcribe" => t, "action_items" => a})
      when is_boolean(t) and is_boolean(a) do
    Repo.insert_all(
      "risi_voice_settings",
      [
        %{
          user_id: Ecto.UUID.dump!(user_id),
          transcribe: t,
          action_items: a,
          updated_at: DateTime.utc_now()
        }
      ],
      on_conflict: {:replace, [:transcribe, :action_items, :updated_at]},
      conflict_target: [:user_id]
    )

    {:ok, %{transcribe: t, action_items: a}}
  end

  def put_setting(_user_id, _params), do: {:error, :bad_request}

  ## Validation (§37.2)

  @doc """
  The §37.2 checks (the §14.4 `blob`/`enc` checks, `mime`, `duration_ms`, a 64-byte `waveform`):
  `{:ok, note}` with the decoded fields, or `:error`.
  """
  def validate(%{"type" => "voice"} = env) do
    with %{"blob_id" => blob_id, "size" => size, "sha256" => sha_b64} <- env["blob"],
         {:ok, _} <- Ecto.UUID.cast(blob_id),
         true <- is_integer(size) and size >= 17 and size <= Blobs.max_bytes("media"),
         {:ok, <<_::binary-size(32)>> = sha} <- b64(sha_b64),
         %{"alg" => "A256GCM-S64K", "key" => key_b64, "plain_size" => plain} = enc <- env["enc"],
         false <- Map.has_key?(enc, "nonce"),
         {:ok, <<_::binary-size(32)>> = key} <- b64(key_b64),
         true <- is_integer(plain) and plain >= 1 and plain < size,
         true <- env["mime"] in @mimes,
         d when is_integer(d) and d >= 500 and d <= 1_800_000 <- env["duration_ms"],
         {:ok, <<_::binary-size(64)>>} <- b64(env["waveform"]) do
      {:ok,
       %{
         blob_id: String.downcase(blob_id),
         size: size,
         sha256: sha,
         key: key,
         plain_size: plain,
         mime: env["mime"],
         duration_ms: d,
         # §37.10 (lenient: malformed = nil, the note is kept); used only as the speech
         # fallback while RISI_VOICE_PHONE_FALLBACK is on (incident 2026-10-14).
         phone_transcript:
           if(match?({:ok, _, _}, Speech.phone_transcript(env["phone_transcript"])),
             do: env["phone_transcript"]
           )
       }}
    else
      _ -> :error
    end
  end

  def validate(_env), do: :error

  defp b64(s) when is_binary(s), do: Base.decode64(s)
  defp b64(_), do: :error

  ## Entry point (from Agent.Conversation)

  @doc """
  A decrypted `voice` envelope of an active human member (`m` = `%{message_id, sender_id}`).
  Called in the conversation's process: it only decides and starts the transcription task.
  Returns `:skip`, `:dropped` (malformed), `{:started, pid}` or (the Risi chat while Risi can't
  listen) `{:replied, pid}`.
  """
  def received(conv, %{message_id: id, sender_id: sender} = m, env) do
    case validate(env) |> with_device(m) do
      :error ->
        # Never the content: the conversation and the message id only.
        Logger.info("Risi dropped a malformed voice note #{id} in #{conv}")
        :dropped

      {:ok, note} ->
        case decide(conv, sender, env, note) do
          {:ok, :risi_chat} ->
            # PART 4d: "Risi is typing…" right away, while the note is being transcribed.
            typing(sender, env["request_id"], conv)
            start(conv, id, sender, env, note, :risi_chat)

          {:ok, :official} ->
            # §37.11: the @Risi chip was on: a request, so "Risi is typing…" right away.
            if official_chip?(sender, env), do: typing(sender, env["request_id"], conv)
            start(conv, id, sender, env, note, :official)

          {:ok, mode} ->
            start(conv, id, sender, env, note, mode)

          {:reply, why} ->
            cant_listen(conv, sender, env["request_id"], why)

          :skip ->
            :skip
        end
    end
  rescue
    e ->
      Logger.warning("Risi voice note skipped in #{conv}: #{inspect(e.__struct__)}")
      :skip
  end

  # P1 2026-10-14 (proposal send-for-me §42.5): the note's sending device (nil when unknown).
  defp with_device({:ok, note}, m), do: {:ok, Map.put(note, :sender_device, m[:sender_device])}
  defp with_device(other, _m), do: other

  @doc false
  # §37.6 "When": `{:ok, :official | :risi_chat}`, `{:reply, :off | :too_long}` (Risi chat only)
  # or `:skip`. Private never gets past the first line (no blob, no key, no speech call).
  def decide(conv, sender, env, note) do
    cond do
      not RisiMe.Agent.official?(conv) -> :skip
      RisiMe.Groups.Tabs.risi_chat?(conv) -> decide_risi_chat(conv, sender, env, note)
      not listening_for?(sender) -> :skip
      Map.has_key?(env, "forwarded") -> :skip
      not Secretary.active_human?(conv, sender) -> :skip
      # §37.11: a note recorded with the @Risi chip is the sender's request: never silent, and
      # its own consent to be transcribed (as the Risi chat, §37.8).
      official_chip?(sender, env) and note.duration_ms > @max_transcribe_ms -> {:reply, :too_long}
      note.duration_ms > @max_transcribe_ms -> :skip
      official_chip?(sender, env) -> {:ok, :official}
      env["transcribe"] == false -> :skip
      not setting(sender).transcribe -> :skip
      true -> {:ok, :official}
    end
  end

  # §37.11: an Official note with a `request_id` (the @Risi chip) from a sender the
  # `RISI_SPOKEN_MENTION` switch acts for.
  defp official_chip?(sender, env),
    do: SpokenMention.chip?(env["request_id"]) and SpokenMention.acts_for?(sender)

  # §37.8 + PART 4d "never silent": in the user's Risi chat a note is a request, so it is always
  # answered. The switch off or a note over 5 min get a fixed answer (no transcript); a speech
  # service that looks unhealthy is still tried (if it really is down, the §37.8 `failed` card
  # and answer follow). `transcribe: false` and the §37.7 setting don't apply here.
  defp decide_risi_chat(conv, sender, env, note) do
    cond do
      not uuid?(env["request_id"]) -> :skip
      Map.has_key?(env, "forwarded") -> :skip
      not Secretary.active_human?(conv, sender) -> :skip
      not (Risi.enabled?() and Out.ready?()) -> :skip
      not enabled_for?(sender) -> {:reply, :off}
      note.duration_ms > @max_transcribe_ms -> {:reply, :too_long}
      note.duration_ms < @min_ms -> {:reply, :too_short}
      true -> {:ok, :risi_chat}
    end
  end

  defp uuid?(s) when is_binary(s), do: match?({:ok, _}, Ecto.UUID.cast(s))
  defp uuid?(_), do: false

  defp start(conv, id, sender, env, note, mode) do
    rid = env["request_id"]

    # Incident 2026-10-14: registered for Stop / Try again (`RisiMe.Agent.VoiceJobs`).
    meta = %{conv: conv, note_id: id, sender: sender, note: note, mode: mode, rid: rid}

    case VoiceJobs.start(meta, fn -> transcribe(conv, id, sender, note, mode, rid) end) do
      {:ok, pid} ->
        {:started, pid}

      {:error, reason} ->
        Logger.warning("Risi voice task not started in #{conv}: #{inspect(reason)}")
        :skip
    end
  end

  ## The audio path (in the task)

  @doc false
  def transcribe(conv, note_id, sender, note, mode, request_id \\ nil) do
    # Proposal 2026-10-14-voice-queue: in the Risi chat a speech service that already looks
    # down gets the acknowledgement at once (it is still tried, PART 4d).
    acked = mode == :risi_chat and not speech_healthy?() and ack(conv, sender, request_id)

    t0 = System.monotonic_time(:millisecond)
    hint = hint(conv, sender)
    VoiceJobs.working(conv, note_id, sender, hint)
    result = run_speech(conv, note, hint, sender, note_id)
    latency = System.monotonic_time(:millisecond) - t0
    # The audio, the ciphertext and the key lived only in `run_speech/2`'s frames.
    :erlang.garbage_collect()

    with {:error, :failed} <- result,
         :risi_chat <- mode,
         {:ok, _} <- RisiMe.Agent.VoiceQueue.enqueue(conv, note_id, sender, note, request_id) do
      unless acked, do: ack(conv, sender, request_id)
      # The queue's retry job takes over: the sender's "Transcribing…" line ends here.
      VoiceJobs.finished(conv, note_id, sender, "queued")
      {:queued, note_id}
    else
      _ -> finish(conv, note_id, sender, note, mode, request_id, result, latency)
    end
  rescue
    e ->
      Logger.warning("Risi voice transcription failed in #{conv}: #{inspect(e.__struct__)}")
      VoiceJobs.finished(conv, note_id, sender, "failed")

      # The Risi chat is never silent (PART 4d), and never "type it" for a service problem.
      if mode == :risi_chat do
        try do
          fixed_answer(conv, sender, request_id, @failed_answer)
        rescue
          _ -> :ok
        end
      end

      {:error, :exception}
  end

  @doc """
  One attempt of the retry job (`RisiMe.Agent.VoiceQueue`) for a queued Risi-chat note: `:retry`
  while the speech service still fails, else the normal post and answer, `:done`.
  """
  def retry(conv, note_id, sender, note, request_id) do
    t0 = System.monotonic_time(:millisecond)
    result = run_speech(conv, note, hint(conv, sender), sender, note_id)
    latency = System.monotonic_time(:millisecond) - t0
    :erlang.garbage_collect()

    case result do
      {:error, :failed} ->
        :retry

      _ ->
        finish(conv, note_id, sender, note, :risi_chat, request_id, result, latency)
        :done
    end
  end

  @doc "The bounded give-up of a queued note: the `failed` card and the honest answer."
  def give_up(conv, note_id, sender, note, request_id),
    do: finish(conv, note_id, sender, note, :risi_chat, request_id, {:error, :failed}, 0)

  @doc "The give-up when the queued note itself can't be read any more (the answer only)."
  def give_up_answer(conv, sender, request_id),
    do: fixed_answer(conv, sender, request_id, @failed_answer)

  defp speech_healthy? do
    Speech.healthy?()
  rescue
    _ -> false
  end

  # The acknowledgement: an ordinary rule-made `answer` with the note's `request_id`, like the
  # §25 offline notice; the real answer follows as a second message for the same request.
  defp ack(conv, sender, rid) do
    fixed_answer(conv, sender, rid, @queued_answer)
    true
  rescue
    _ -> false
  end

  defp finish(conv, note_id, sender, note, mode, request_id, result, latency) do
    # A blob that can't be read (not the speech service) keeps the §37.8 "try again" answer.
    t = classify(result, note) |> Map.put(:blob_error, result == {:error, :blob})
    t = if mode == :risi_chat, do: fix_name(t), else: t
    log(conv, t, note, latency)
    note_speech(conv, sender, t, note)
    posted = post(conv, note_id, sender, note, t)
    VoiceJobs.finished(conv, note_id, sender, t.status)
    buffer(conv, note_id, sender, t, mode, request_id, note)
    if mode == :risi_chat, do: risi_chat(conv, note_id, sender, t, request_id)
    posted
  end

  # Proposal 2026-10-14-send-for-me §42.6: in the user's own Risi chat, "Rishi, …" is "Risi, …".
  defp fix_name(%{text: text} = t) when is_binary(text),
    do: %{t | text: RisiMe.Agent.Hotwords.fix_risi(text)}

  defp fix_name(t), do: t

  # The speech service's `language_hint` (decision 071 amendment d): the sender's language and
  # the chat's. Only the codes are logged.
  defp hint(conv, sender) do
    hint = Lang.speech_hint([sender], conv)
    Logger.info("Risi voice: language hint #{Enum.join(hint, ",")}")
    hint
  rescue
    _ -> []
  end

  # Fetch (as a member), verify, decrypt in memory, transcribe. Incident 2026-10-14:
  # `Speech.transcribe_note/2` holds the live budget (3 × duration, ≥ 15 s), retries a fast error
  # once, then falls back (the phone's transcript while RISI_VOICE_PHONE_FALLBACK is on, then the
  # fast CPU model).
  defp run_speech(conv, note, hint, sender, note_id) do
    opts =
      Speech.hint_opts(hint) ++
        [
          duration_ms: note.duration_ms,
          phone_transcript: Map.get(note, :phone_transcript),
          phone_fallback: phone_fallback?(sender, note),
          sender: sender,
          # Proposal 2026-10-14-send-for-me §42.6: "Risi" spelt right; the sender's own contacts
          # in their own Risi chat. Per request, never logged or stored.
          hotwords: RisiMe.Agent.Hotwords.for_note(conv, sender)
        ]

    case audio(conv, note) do
      {:ok, audio} ->
        result = Speech.transcribe_note(audio, opts)
        # Harsha's Sinhala compare mode (dev users' own notes only; his Risi chat only).
        RisiMe.Agent.SpeechCompare.maybe_start(conv, note_id, sender, audio, note, result, hint)

        case result do
          {:ok, r} -> {:ok, r}
          {:error, _} -> {:error, :failed}
        end

      {:error, _} = e ->
        e
    end
  end

  # The phone transcript may stand in for ours: while RISI_VOICE_PHONE_FALLBACK is on, or for a dev
  # user's own opt-in Google transcript (Harsha 2026-10-14, "Transcript (Google)").
  defp phone_fallback?(sender, note) do
    RisiMe.Agents.Flags.feature_for("RISI_VOICE_PHONE_FALLBACK", sender) or
      (match?(%{"engine" => "android-online-google"}, Map.get(note, :phone_transcript)) and
         RisiMe.Agent.SpeechCompare.enabled_for?(sender))
  end

  defp audio(conv, note) do
    with {:ok, %{path: path, conversation_id: ^conv, purpose: "media"} = b} <-
           Blobs.fetch(Risi.user_id(), note.blob_id),
         true <- b.size == note.size and b.sha256 == note.sha256,
         {:ok, cipher} <- File.read(path),
         true <- byte_size(cipher) == note.size,
         true <- :crypto.hash(:sha256, cipher) == note.sha256 do
      Media.decrypt(cipher, note.key, note.plain_size)
    else
      _ -> {:error, :blob}
    end
  end

  # `%{status, text, language, model, script, romanised, elapsed_ms}`.
  defp classify({:ok, r}, _note) do
    language = if r.speech, do: Speech.lang(r.language)
    p = Map.get(r, :language_probability)
    romanised = Map.get(r, :romanised) == true
    script = if language == "si", do: Map.get(r, :script)

    # v1.42 §38.2: why it was unclear (the action-items card says so).
    reason =
      cond do
        not r.speech -> "no_speech"
        String.trim(r.text || "") == "" -> "no_speech"
        is_number(p) and p < @min_probability -> "low_confidence"
        # The hallucination guard (`RisiMe.Agent.SpeechQuality`, Harsha 2026-10-14): a
        # repeated or implausible text is not posted and nothing acts on it.
        is_binary(Map.get(r, :rejected)) -> "low_confidence"
        romanised -> "script_check"
        true -> nil
      end

    status = if reason, do: "unclear", else: "ok"

    %{
      status: status,
      text: if(status == "ok", do: r.text |> String.trim() |> String.slice(0, @max_text)),
      language: language,
      model: r.model,
      script: script,
      romanised: romanised and language == "si",
      elapsed_ms: r[:elapsed_ms],
      reason: reason,
      p: p,
      parts: if(status == "ok", do: parts(r), else: []),
      source: Map.get(r, :source),
      engine: Map.get(r, :engine)
    }
  end

  defp classify({:error, _}, _note),
    do: %{
      status: "failed",
      text: nil,
      language: nil,
      model: nil,
      script: nil,
      romanised: false,
      elapsed_ms: nil,
      reason: nil,
      p: nil,
      parts: [],
      source: nil,
      engine: nil
    }

  defp parts(r) do
    case Map.get(r, :segments) do
      [_ | _] = segs -> for s <- segs, do: %{"o" => s.offset_ms, "t" => s.text}
      _ -> [%{"o" => 0, "t" => r.text |> String.trim() |> String.slice(0, @max_text)}]
    end
  end

  @doc false
  # The post (§37.6): body and `risi` object.
  def envelope(conv, note_id, sender, note, t) do
    name = name(conv, sender)
    dur = duration(note.duration_ms)

    body =
      if t.status == "ok",
        do: "Voice note from #{name} (#{dur}): #{t.text}",
        else: "Risi couldn't hear #{name}'s voice note (#{dur}) clearly."

    risi = %{
      "kind" => "voice_transcript",
      "conversation_id" => conv,
      "message_id" => note_id,
      "speaker" => sender,
      "duration_ms" => note.duration_ms,
      "status" => t.status,
      "text" => t.text,
      "language" => t.language,
      "model" => t.model,
      "call_ref" => nil,
      "made_by" =>
        if(t.model,
          do: %{
            "model" => t.model,
            "provider" => "risime",
            "at" => Clock.ts(Clock.now()),
            "also" => []
          },
          else: MadeBy.rule()
        ),
      "notify" => []
    }

    risi = if t.script, do: Map.put(risi, "script", script_json(t.script)), else: risi
    risi = if t.romanised, do: Map.put(risi, "romanised", true), else: risi
    {body, phone_source(risi, t)}
  end

  defp script_json(s),
    do: %{
      "expected" => s.expected,
      "sinhala_chars" => s.sinhala_chars,
      "latin_chars" => s.latin_chars,
      "ok" => s.ok
    }

  defp post(conv, note_id, sender, note, t) do
    {body, risi} = envelope(conv, note_id, sender, note, t)

    # PART 9: the note joins memory as the speaker's own message under the note's id
    # (`buffer/7`, the typed-message path), so Risi's transcript post is not remembered twice.
    case send_post(conv, body, risi, memory: false) do
      {:ok, %{message_id: mid}} ->
        Repo.insert_all(
          "risi_voice_transcripts",
          [
            %{
              conversation_id: conv,
              note_message_id: note_id,
              transcript_message_id: mid,
              speaker: Ecto.UUID.dump!(sender),
              inserted_at: DateTime.utc_now()
            }
          ],
          on_conflict: {:replace, [:transcript_message_id, :inserted_at]},
          conflict_target: [:conversation_id, :note_message_id]
        )

        # The note may have been deleted for everyone while it was being transcribed.
        if note_gone?(note_id), do: delete_transcripts(conv, [note_id])
        {:ok, mid}

      {:error, reason} = e ->
        Logger.warning("Risi voice transcript not posted in #{conv}: #{inspect(reason)}")
        e
    end
  end

  defp send_post(conv, body, risi, opts, tries \\ 5) do
    case Out.post(conv, body, risi, opts) do
      {:error, :rate_limited} when tries > 0 ->
        Process.sleep(1_000)
        send_post(conv, body, risi, opts, tries - 1)

      other ->
        other
    end
  end

  @doc false
  def note_gone?(note_id) do
    case RisiMe.Messaging.Store.impl().get_message(note_id) do
      {:ok, %{deleted_at: %DateTime{}}} -> true
      {:ok, _} -> false
      _ -> false
    end
  rescue
    _ -> false
  end

  # The 24-h working copy (sealed): a `voice_transcript` in Official, the request in a Risi chat.
  defp buffer(conv, note_id, sender, t, :official, rid, %{duration_ms: duration_ms}) do
    env = %{
      "v" => 1,
      "type" => "voice_transcript",
      "status" => t.status,
      "text" => t.text,
      "language" => t.language,
      # v1.42 §38: what the action-items extraction needs (no audio, no key).
      "reason" => t.reason,
      "p" => t.p,
      "romanised" => t.romanised,
      "model" => t.model,
      "parts" => t.parts,
      "duration_ms" => duration_ms
    }

    # The note may have been deleted for everyone while it was being transcribed: then nothing
    # of it is kept or learned.
    unless note_gone?(note_id) do
      # §37.11 (proposal 2026-10-14-spoken-risi): a note that addresses Risi ("Risi, …") is a
      # request; its working copy carries the request so the request job can read it back.
      # A `request_id` on the note = the @Risi chip was on while recording (no name needed).
      chip = if SpokenMention.chip?(rid), do: rid
      spoken = SpokenMention.check(conv, note_id, sender, t, chip)

      env =
        if match?(%{action: :request}, spoken),
          do:
            Map.put(env, "spoken_request", %{
              "request_id" => spoken.request_id,
              "text" => spoken.text
            }),
          else: env

      m = put_buffer(conv, note_id, sender, env)

      case spoken do
        %{action: :request} ->
          # A request (as §37.8): no §38 extraction and no quiet-rule count, never twice.
          Secretary.spoken_request(conv, note_id, sender, spoken.request_id)

        _ ->
          understood(conv, m)
          # v1.42 §38.2: the action items of the note (a job; ids only).
          RisiMe.Agent.ActionItems.voice_transcribed(conv, note_id, sender, t.status, duration_ms)
          never_silent(conv, note_id, sender, t, chip, spoken)
      end
    end

    :ok
  end

  defp buffer(conv, note_id, sender, %{status: "ok"} = t, :risi_chat, rid, note) do
    env = %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => rid,
      "action" => "ask",
      "text" => String.slice(t.text, 0, 1000),
      "lang" => t.language,
      "via" => "voice"
    }

    # P1 2026-10-14: the request is the sending device's (nil after a voice-queue retry: the
    # turn then uses the asker's most recent Risi device, `Skills.request_device/3`).
    put_buffer(conv, note_id, sender, env, Map.get(note, :sender_device))
  end

  defp buffer(_conv, _note_id, _sender, _t, :risi_chat, _rid, _note), do: :ok

  # §37.11 never silent: addressed to Risi but unclear (or borderline) → Risi asks back; the chip
  # on a note Risi couldn't hear → the "couldn't hear" answer.
  defp never_silent(conv, note_id, sender, t, _chip, %{action: :ask, request_id: rid}),
    do: fixed_answer(conv, sender, rid, ask_back(conv, sender, t), voice_ref(note_id, sender))

  defp never_silent(conv, note_id, sender, %{status: s}, chip, nil)
       when is_binary(chip) and s != "ok" do
    if SpokenMention.acts_for?(sender),
      do: fixed_answer(conv, sender, chip, @unclear_answer, voice_ref(note_id, sender)),
      else: :ok
  end

  defp never_silent(_conv, _note_id, _sender, _t, _chip, _spoken), do: :ok

  defp voice_ref(note_id, sender), do: %{"message_id" => note_id, "speaker" => sender}

  @ask_back %{
    "en" =>
      "Did you want me to do something with this? Tell me what you need: start a voice note with \"Risi, …\" or use @Risi.",
    "si" =>
      "මම මේ ගැන මොකක් හරි කරන්න ඕනද? ඕන දේ කියන්න: \"රිසි, …\" කියලා voice note එකක් පටන් ගන්න, නැත්නම් @Risi පාවිච්චි කරන්න.",
    "ta" =>
      "இதைப் பற்றி நான் ஏதாவது செய்ய வேண்டுமா? என்ன வேண்டும் என்று சொல்லுங்கள்: \"ரிசி, …\" என்று voice note ஐத் தொடங்குங்கள், அல்லது @Risi ஐப் பயன்படுத்துங்கள்."
  }

  # The ask-back in the note's language (else the sender's, else English).
  defp ask_back(conv, sender, t) do
    lang =
      t[:language] ||
        try do
          Lang.speech_hint([sender], conv) |> List.first()
        rescue
          _ -> nil
        end

    Map.get(@ask_back, lang, @ask_back["en"])
  end

  defp put_buffer(conv, note_id, sender, env, device \\ nil) do
    m = %{
      message_id: note_id,
      sender_id: sender,
      sender_device: device,
      plaintext: Jason.encode!(env)
    }

    Transcript.put(conv, m)
    m
  end

  # PART 9 (Harsha 2026-10-13): an Official voice note, once transcribed, is handled exactly
  # like a typed Official message, through the same entry points `Agent.Conversation` uses for
  # a `text` (the note's id is the source): long-term memory (§35.1, opt-outs respected) and the
  # secretary (the Ledger's quiet rule / the v1.24 extraction, and through them the calendar
  # offers; a future Policy agent hooks in there too). A note that §38 extracts (its sender's
  # action items on) is left to §38 by the secretary, so no promise is offered twice. Only an
  # `ok` transcript counts; never Private (Official only, checked again downstream) and never
  # the Risi chat (there the note is a request, §37.8).
  defp understood(conv, m) do
    RisiMe.Agent.Memory.put_message(conv, m)
    Secretary.on_message(conv, m)
  rescue
    e -> Logger.warning("Risi voice: note not understood in #{conv}: #{inspect(e.__struct__)}")
  end

  # §37.8: the request goes through the §24.11 path (limits, queue, the §25 loop).
  defp risi_chat(conv, note_id, sender, %{status: "ok"}, rid) do
    env = Secretary.envelope(conv, note_id)

    if env do
      Secretary.on_message(conv, %{
        message_id: note_id,
        sender_id: sender,
        plaintext: env |> Map.drop(~w(__sender __device __ts)) |> Jason.encode!()
      })
    else
      # The working copy didn't land (never silent): the "couldn't hear" answer.
      fixed_answer(conv, sender, rid, @unclear_answer)
    end

    :ok
  end

  # `failed` (the speech service, once the retry queue gave up or couldn't take the note): an
  # honest line, never "type it".
  defp risi_chat(conv, _note_id, sender, %{status: "failed", blob_error: false}, rid),
    do: fixed_answer(conv, sender, rid, @failed_answer)

  defp risi_chat(conv, _note_id, sender, %{status: "failed"}, rid),
    do: fixed_answer(conv, sender, rid, @blob_answer)

  # An empty or noise-only transcript (no speech): the "very short" line, not a failure.
  defp risi_chat(conv, _note_id, sender, %{reason: "no_speech"}, rid),
    do: fixed_answer(conv, sender, rid, @short_answer)

  defp risi_chat(conv, _note_id, sender, _t, rid),
    do: fixed_answer(conv, sender, rid, @unclear_answer)

  # A rule-made `answer` to the note's request (notifies the asker only); it ends the typing
  # bubble (the answer and a `done` signal).
  defp fixed_answer(conv, sender, rid, text, voice_ref \\ nil) do
    risi = %{
      "kind" => "answer",
      "request_id" => rid,
      "answer" => text,
      "refs" => if(voice_ref, do: [voice_ref["message_id"]], else: []),
      "confidence" => 1.0,
      "sources" => [],
      "next_steps" => [],
      "call_ref" => nil,
      "made_by" => MadeBy.rule(),
      "notify" => [sender]
    }

    risi = if voice_ref, do: Map.put(risi, "voice_ref", voice_ref), else: risi
    send_post(conv, text, risi, [])
    RisiMe.Agent.Progress.send(sender, rid, conv, "done")
    :ok
  end

  # The Risi chat while Risi can't listen (the switch off) or the note is over 5 min: an answer,
  # never silence. In a task: never posts from the conversation's process.
  defp cant_listen(conv, sender, rid, why) do
    text =
      case why do
        :off -> @off_answer
        :too_short -> @short_answer
        _ -> @too_long_answer
      end

    case Task.Supervisor.start_child(__MODULE__.TaskSup, fn ->
           fixed_answer(conv, sender, rid, text)
         end) do
      {:ok, pid} -> {:replied, pid}
      {:error, _} -> :skip
    end
  end

  # Proposal 2026-10-14-phone-transcript-fallback: a transcript taken from the sender phone says
  # so (`source: "phone"`, provider `phone`, and its `engine`: "android-online-google" is
  # rendered "Transcript (Google)"); only while RISI_VOICE_PHONE_FALLBACK is on, or for a dev
  # user's own Google transcript.
  defp phone_source(risi, %{source: "phone"} = t) do
    risi
    |> Map.put("source", "phone")
    |> then(&if(is_binary(t[:engine]), do: Map.put(&1, "engine", t[:engine]), else: &1))
    |> update_in(["made_by"], &Map.put(&1, "provider", "phone"))
  end

  defp phone_source(risi, _t), do: risi

  defp typing(sender, rid, conv) do
    RisiMe.Agent.Progress.send(sender, rid, conv, "working")
  rescue
    _ -> :ok
  end

  # Decision 071 amendment d: the note's language (a code and its ms) feeds the sender's next
  # hint. A romanised Sinhala note still counts; silence or a low-confidence guess doesn't.
  defp note_speech(conv, sender, t, note) do
    if t.language in ~w(si ta en) and t.reason not in ["no_speech", "low_confidence"] and
         is_integer(note.duration_ms) and RisiMe.Agent.Memory.learning_enabled?(sender, conv),
       do: RisiMe.Agent.SpeechLang.learn(sender, t.language, note.duration_ms)

    :ok
  rescue
    _ -> :ok
  end

  defp log(conv, t, note, latency) do
    LearningLog.record(%{
      call_id: TimeUUID.generate(),
      conversation_id: conv,
      chat_id: Secretary.chat_id(conv),
      task: "transcribe",
      model_alias: t.model || Speech.route("en"),
      model: t.model || "none",
      provider: "risime",
      status: t.status,
      latency_ms: t.elapsed_ms || latency,
      source_message_ids: [],
      meta: %{
        "source" => "voice_note",
        "language" => t.language,
        "audio_s" => Float.round(note.duration_ms / 1000, 1)
      },
      output: nil
    })
  rescue
    e -> Logger.warning("Risi voice: learning-log row not written: #{inspect(e.__struct__)}")
  end

  ## Deletes (§37.6)

  @doc """
  Notes deleted for everyone (§15): Risi deletes their transcripts (and the mapping rows). The
  buffered working copies go with the note ids (`Transcript.delete/2`, by the caller).
  """
  def notes_deleted(conv, ids) when is_list(ids) do
    # Harsha's gold set (SpeechGold): a deleted note's sealed audio and labels go too.
    for id <- ids, is_binary(id), do: RisiMe.Agent.SpeechGold.delete(id)

    if Repo.exists?(
         from v in "risi_voice_transcripts",
           where: v.conversation_id == ^conv and v.note_message_id in ^ids
       ) do
      %{"kind" => "voice_delete", "conv" => conv, "message_ids" => ids}
      |> RisiMe.Workers.Risi.new(queue: :risi_timers)
      |> Oban.insert()
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi voice: transcript delete not queued: #{inspect(e.__struct__)}")
      :ok
  end

  @doc "Deletes the transcripts of `note_ids` (a job, so it never runs inside a delete)."
  def delete_transcripts(conv, note_ids) do
    rows =
      Repo.all(
        from v in "risi_voice_transcripts",
          where: v.conversation_id == ^conv and v.note_message_id in ^note_ids,
          select: {v.note_message_id, v.transcript_message_id}
      )

    case Out.delete(conv, Enum.map(rows, &elem(&1, 1))) do
      :ok ->
        forget_rows(conv, Enum.map(rows, &elem(&1, 0)))
        # v1.42 §38.6: the note's action items are cancelled (and its working copy goes).
        RisiMe.Agent.ActionItems.cancel_notes(conv, note_ids)
        :ok

      {:error, reason} when reason in [:not_member, :private_tab] ->
        forget_rows(conv, Enum.map(rows, &elem(&1, 0)))
        :ok

      {:error, _} = e ->
        e
    end
  end

  @doc """
  `risi_action` `transcript_delete` (§37.6): only the note's sender may delete its transcript;
  anyone else's is ignored.
  """
  def transcript_delete(conv, user, note_id) when is_binary(note_id) do
    owner? =
      Repo.exists?(
        from v in "risi_voice_transcripts",
          where:
            v.conversation_id == ^conv and v.note_message_id == ^String.downcase(note_id) and
              v.speaker == type(^user, Ecto.UUID)
      )

    if owner? do
      note = String.downcase(note_id)

      with :ok <- delete_transcripts(conv, [note]) do
        # PART 9: what Risi learned from the note goes with its transcript (memory, facts and
        # promises derived only from it), and its working copy.
        Secretary.message_deleted(conv, [note])
        Transcript.delete(conv, [note])
        :ok
      end
    else
      :ok
    end
  end

  def transcript_delete(_conv, _user, _note_id), do: :ok

  @doc "Official off / Risi removed: the mapping rows go (the posts stay on phones, rule 9)."
  def forget(conv) do
    Repo.delete_all(from v in "risi_voice_transcripts", where: v.conversation_id == ^conv)
    :ok
  end

  defp forget_rows(_conv, []), do: :ok

  defp forget_rows(conv, ids) do
    Repo.delete_all(
      from v in "risi_voice_transcripts",
        where: v.conversation_id == ^conv and v.note_message_id in ^ids
    )

    :ok
  end

  ## Helpers

  defp name(conv, user) do
    Enum.find_value(Secretary.members(conv), "A member", fn m ->
      if m.user_id == user, do: m.name
    end)
  rescue
    _ -> "A member"
  end

  @doc "`m:ss`, or `h:mm:ss` from one hour (§37.3)."
  def duration(ms) do
    s = div(ms + 500, 1000)
    {h, m, s} = {div(s, 3600), div(rem(s, 3600), 60), rem(s, 60)}
    pad = &String.pad_leading(Integer.to_string(&1), 2, "0")
    if h > 0, do: "#{h}:#{pad.(m)}:#{pad.(s)}", else: "#{m}:#{pad.(s)}"
  end
end
