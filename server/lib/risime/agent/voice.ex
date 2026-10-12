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
    envelope (never a `text`, so nothing else counts it), and `risi_voice_transcripts` maps the
    note to the post (ids only) for the §37.6 delete rules.
  * **Risi chat** (§37.8): the note is a request: after the transcript the text is handled as a
    §24.11 `ask` with the note's `request_id` (buffered as a `risi_request` with
    `"via": "voice"` and `lang`, so the answer is in the transcript's language); `unclear` or
    `failed` get the fixed "couldn't hear" answer and no tools run. PART 4d (Harsha
    2026-10-12), never silent: `risi_progress` `working` ("Risi is typing…") goes out as soon
    as the note arrives; the switch off or a note over 5 min get a fixed answer instead of
    silence; a speech service that looks unhealthy is still tried. The model being down is
    the typed-message path's (`RisiMe.Agent.Offline`: the request job's offline notice).
  * **Learning log:** one `transcribe` row per note (model, language, audio seconds, latency,
    status), `output: null`.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, Lang, LearningLog, MadeBy, Media, Out, Secretary, Speech, Transcript}
  alias RisiMe.{Blobs, Repo, Risi, TimeUUID}

  @max_transcribe_ms 300_000
  @max_text 4_000
  @min_probability 0.6
  @mimes ["audio/ogg; codecs=opus", "audio/mp4"]
  @unclear_answer "I couldn't hear that clearly. Please try again or type it."
  @off_answer "I can't listen to voice notes right now. Please type it."
  @too_long_answer "That voice note is over 5 minutes, too long for me. Please send a shorter one or type it."

  ## Switches

  @doc "True while `RISI_VOICE_TRANSCRIBE=on`."
  def enabled?, do: Application.get_env(:risime, :risi_voice_transcribe, false) == true

  @doc "`/auth/config` `voice_transcribe` (§37.1): the switch, Risi up, the speech service healthy."
  def config_on? do
    enabled?() and Risi.enabled?() and Out.ready?() and Speech.healthy?()
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
         duration_ms: d
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
  def received(conv, %{message_id: id, sender_id: sender}, env) do
    case validate(env) do
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

  @doc false
  # §37.6 "When": `{:ok, :official | :risi_chat}`, `{:reply, :off | :too_long}` (Risi chat only)
  # or `:skip`. Private never gets past the first line (no blob, no key, no speech call).
  def decide(conv, sender, env, note) do
    cond do
      not RisiMe.Agent.official?(conv) -> :skip
      RisiMe.Groups.Tabs.risi_chat?(conv) -> decide_risi_chat(conv, sender, env, note)
      not config_on?() -> :skip
      Map.has_key?(env, "forwarded") -> :skip
      note.duration_ms > @max_transcribe_ms -> :skip
      not Secretary.active_human?(conv, sender) -> :skip
      env["transcribe"] == false -> :skip
      not setting(sender).transcribe -> :skip
      true -> {:ok, :official}
    end
  end

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
      not enabled?() -> {:reply, :off}
      note.duration_ms > @max_transcribe_ms -> {:reply, :too_long}
      true -> {:ok, :risi_chat}
    end
  end

  defp uuid?(s) when is_binary(s), do: match?({:ok, _}, Ecto.UUID.cast(s))
  defp uuid?(_), do: false

  defp start(conv, id, sender, env, note, mode) do
    rid = env["request_id"]

    case Task.Supervisor.start_child(__MODULE__.TaskSup, fn ->
           transcribe(conv, id, sender, note, mode, rid)
         end) do
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
    t0 = System.monotonic_time(:millisecond)
    result = run_speech(conv, note, hint(conv, sender))
    latency = System.monotonic_time(:millisecond) - t0
    # The audio, the ciphertext and the key lived only in `run_speech/2`'s frames.
    :erlang.garbage_collect()

    t = classify(result, note)
    log(conv, t, note, latency)
    posted = post(conv, note_id, sender, note, t)
    buffer(conv, note_id, sender, t, mode, request_id, note.duration_ms)
    if mode == :risi_chat, do: risi_chat(conv, note_id, sender, t, request_id)
    posted
  rescue
    e ->
      Logger.warning("Risi voice transcription failed in #{conv}: #{inspect(e.__struct__)}")

      # The Risi chat is never silent (PART 4d).
      if mode == :risi_chat do
        try do
          fixed_answer(conv, sender, request_id, @unclear_answer)
        rescue
          _ -> :ok
        end
      end

      {:error, :exception}
  end

  # The speech service's `language_hint` (decision 071 amendment d): the sender's language and
  # the chat's. Only the codes are logged.
  defp hint(conv, sender) do
    hint = Lang.speech_hint([sender], conv)
    Logger.info("Risi voice: language hint #{Enum.join(hint, ",")}")
    hint
  rescue
    _ -> []
  end

  # Fetch (as a member), verify, decrypt in memory, transcribe; retried once on a speech error.
  defp run_speech(conv, note, hint) do
    opts = Speech.hint_opts(hint)

    case audio(conv, note) do
      {:ok, audio} ->
        case Speech.transcribe(audio, opts) do
          {:ok, r} ->
            {:ok, r}

          {:error, _} ->
            case Speech.transcribe(audio, opts) do
              {:ok, r} -> {:ok, r}
              {:error, _} -> {:error, :failed}
            end
        end

      {:error, _} = e ->
        e
    end
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
      parts: if(status == "ok", do: parts(r), else: [])
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
      parts: []
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
    {body, risi}
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
    memory? = RisiMe.Agent.Memory.learning_enabled?(sender, conv)

    case send_post(conv, body, risi, memory: memory?) do
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

  defp note_gone?(note_id) do
    case RisiMe.Messaging.Store.impl().get_message(note_id) do
      {:ok, %{deleted_at: %DateTime{}}} -> true
      {:ok, _} -> false
      _ -> false
    end
  rescue
    _ -> false
  end

  # The 24-h working copy (sealed): a `voice_transcript` in Official, the request in a Risi chat.
  defp buffer(conv, note_id, sender, t, :official, _rid, duration_ms) do
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

    put_buffer(conv, note_id, sender, env)
    # v1.42 §38.2: the action items of the note (a job; ids only).
    RisiMe.Agent.ActionItems.voice_transcribed(conv, note_id, sender, t.status, duration_ms)
  end

  defp buffer(conv, note_id, sender, %{status: "ok"} = t, :risi_chat, rid, _duration_ms) do
    env = %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => rid,
      "action" => "ask",
      "text" => String.slice(t.text, 0, 1000),
      "lang" => t.language,
      "via" => "voice"
    }

    put_buffer(conv, note_id, sender, env)
  end

  defp buffer(_conv, _note_id, _sender, _t, :risi_chat, _rid, _duration_ms), do: :ok

  defp put_buffer(conv, note_id, sender, env) do
    Transcript.put(conv, %{
      message_id: note_id,
      sender_id: sender,
      sender_device: nil,
      plaintext: Jason.encode!(env)
    })
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

  defp risi_chat(conv, _note_id, sender, _t, rid),
    do: fixed_answer(conv, sender, rid, @unclear_answer)

  # A rule-made `answer` to the note's request (notifies the asker only); it ends the typing
  # bubble (the answer and a `done` signal).
  defp fixed_answer(conv, sender, rid, text) do
    risi = %{
      "kind" => "answer",
      "request_id" => rid,
      "answer" => text,
      "refs" => [],
      "confidence" => 1.0,
      "sources" => [],
      "next_steps" => [],
      "call_ref" => nil,
      "made_by" => MadeBy.rule(),
      "notify" => [sender]
    }

    send_post(conv, text, risi, [])
    RisiMe.Agent.Progress.send(sender, rid, conv, "done")
    :ok
  end

  # The Risi chat while Risi can't listen (the switch off) or the note is over 5 min: an answer,
  # never silence. In a task: never posts from the conversation's process.
  defp cant_listen(conv, sender, rid, why) do
    text = if why == :off, do: @off_answer, else: @too_long_answer

    case Task.Supervisor.start_child(__MODULE__.TaskSup, fn ->
           fixed_answer(conv, sender, rid, text)
         end) do
      {:ok, pid} -> {:replied, pid}
      {:error, _} -> :skip
    end
  end

  defp typing(sender, rid, conv) do
    RisiMe.Agent.Progress.send(sender, rid, conv, "working")
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

    if owner?, do: delete_transcripts(conv, [String.downcase(note_id)]), else: :ok
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
