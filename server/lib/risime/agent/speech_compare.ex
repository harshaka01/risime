defmodule RisiMe.Agent.SpeechCompare do
  @moduledoc """
  The speech compare mode on real voice notes (Harsha 2026-10-14 08:35 and 09:54 SLT;
  docs/status/asr-harsha-gold.md). Owned by the Voice agent: its transcription Index learns from
  the stored CER, its route decision follows the promotion rule.

  * **Whose notes:** a `RISI_DEV_USERS` user's own notes, and the notes of a sender with **compare
    consent** (`consent?/1`: a per-user admin setting, default none, audit-logged; Harsha grants it
    once that person agrees: nobody is enabled automatically). Flag `RISI_SPEECH_COMPARE_DEV`
    (`off` switches it off for everyone).
  * **Language first:** the candidates follow the note's language (the normal path's decision):
    Sinhala (Qwen3-ASR Sinhala, Whisper small Sinhala, omniASR-CTC-1B, omniASR-LLM-7B), Tamil
    (tamil-asr-qwen3, Whisper Tamil medium, omniASR-CTC-1B, omniASR-LLM-7B) or English
    (large-v3, omniASR-CTC-1B). Plus the phone transcript ("Google" for his opt-in online recogniser).
  * **Option 0** is always what the chat actually got, so "0" confirms it.
  * **Who sees it:** for a dev user's own note, that user; for a consenting sender's note, every dev
    user who is a member of the same conversation. Each gets one numbered message in **their own
    Risi chat** (never the Official chat), unless they said "compare off".
  * **Skipped for memory:** never dropped. The models are queued on the sealed audio for the night
    run (`RisiMe.Agent.SpeechCompareNight`, after 16:30 UTC, holding the `scripts/heavy` lock and
    honouring the eval guard). Results reach the recipients in a follow-up right after the 07:00
    brief.
  * **Labels:** a recipient replies `0`…`n` or `fix: <the right words>`. The sender may correct
    their own note with `fix: …` in their Risi chat; **the sender's correction beats a dev user's**.
    Without a label the reference is the Google transcript, in shadow.
  * **Gold:** the audio and the candidates sealed (`SpeechGold`), only for a dev user's own notes or
    a consenting sender; eval only, `NO_TRAINING`.
  * **Route decision:** per sender and language over that sender's scored notes. It is applied as
    the per-user override for dev users, and recorded as a recommendation for consenting senders.
  * Logs: ids, codes and numbers only.
  """
  require Logger

  alias RisiMe.Agent.{Out, SpeechGold, SpeechQuality, SpeechRoutes}
  alias RisiMe.Agents.Flags
  alias RisiMe.Admin.Settings

  @flag "RISI_SPEECH_COMPARE_DEV"
  @sets %{
    "si" => [
      {"Qwen3-ASR Sinhala", :service, "qwen3-asr-0.6b-sinhala"},
      {"Whisper small Sinhala", :service, "whisper-small-sinhala"},
      {"omniASR-CTC-1B", :service, "omnilingual-ctc-1b"},
      {"omniASR-LLM-7B", :extra, "omniasr-llm-7b"}
    ],
    "ta" => [
      {"Tamil ASR (Qwen3)", :service, "tamil-asr-qwen3"},
      {"Whisper Tamil medium", :service, "whisper-tamil-medium"},
      {"omniASR-CTC-1B", :service, "omnilingual-ctc-1b"},
      {"omniASR-LLM-7B", :extra, "omniasr-llm-7b"}
    ],
    "en" => [
      {"Whisper large-v3", :service, "faster-whisper-large-v3"},
      {"omniASR-CTC-1B", :service, "omnilingual-ctc-1b"}
    ]
  }
  @label_window_s 7_200

  @doc "The candidate models of a language (`si` | `ta` | `en`)."
  def models(lang), do: Map.get(@sets, lang, [])

  ## Who

  @doc "True when the compare mode runs for `user`'s notes (a dev user, or compare consent)."
  def enabled_for?(user) do
    is_binary(user) and Flags.feature(@flag) != :off and (dev_user?(user) or consent?(user))
  rescue
    _ -> false
  end

  @doc false
  def dev_user?(user) when is_binary(user),
    do:
      String.downcase(user) in Enum.map(
        Application.get_env(:risime, :risi_dev_users, []),
        &String.downcase/1
      )

  def dev_user?(_), do: false

  @doc "A sender's compare consent (admin setting `speech_compare_consent_user_<uuid>`, default none)."
  def consent?(user) when is_binary(user), do: Settings.get(consent_key(user)) == "on"
  def consent?(_), do: false

  @doc "Grants or revokes `user`'s compare consent (an admin's action, audit-logged as `by`)."
  def put_consent(user, true, by), do: Settings.put(consent_key(user), "on", by)
  def put_consent(user, false, by), do: Settings.clear(consent_key(user), by)

  @doc "The users with compare consent (ids)."
  def consented do
    for {"speech_compare_consent_user_" <> u, "on"} <- Settings.all(), do: u
  rescue
    _ -> []
  end

  defp consent_key(user), do: "speech_compare_consent_user_" <> String.downcase(user)

  @doc """
  Who receives the compare of `sender`'s note in `conv`: the dev user themselves, or the dev users
  who are members of the conversation (for a consenting sender).
  """
  def recipients(conv, sender) do
    if dev_user?(sender) do
      [sender]
    else
      for u <- Application.get_env(:risime, :risi_dev_users, []),
          String.downcase(u) != String.downcase(sender),
          RisiMe.Agent.Secretary.active_human?(conv, u),
          do: u
    end
  rescue
    _ -> []
  end

  ## Start

  @doc """
  After the normal transcription (`Voice`): starts the compare task. `primary` is
  `Speech.transcribe_note/2`'s result (its language decides the candidates). Never blocks.
  """
  def maybe_start(conv, note_id, sender, audio, note, primary, hint) do
    lang = language(primary, hint)

    if enabled_for?(sender) and lang != nil and is_binary(note_id) do
      Task.Supervisor.start_child(RisiMe.Agent.Voice.TaskSup, fn ->
        run(conv, note_id, sender, audio, note, primary, lang)
      end)
    end

    :ok
  rescue
    _ -> :ok
  end

  defp language({:ok, %{language: l}}, _hint) when l in ["si", "ta", "en"], do: l

  defp language(_, hint) do
    Enum.find(List.wrap(hint), &(&1 in ["si", "ta"]))
  end

  @doc false
  def run(conv, note_id, sender, audio, note, primary, lang \\ "si") do
    dur = note.duration_ms
    phone = phone_text(note)

    cands =
      [chat_option(primary)] ++
        Enum.map(models(lang), fn {label, where, model} ->
          candidate(label, where, model, audio, dur, phone, lang)
        end) ++ phone_option(note, phone)

    pending = for c <- cands, c.status == "skipped: memory", do: c.model
    to = recipients(conv, sender)

    SpeechGold.put(note_id, audio, %{
      "at" => DateTime.to_iso8601(DateTime.utc_now()),
      "conversation_id" => conv,
      "sender" => String.downcase(sender),
      "recipients" => Enum.map(to, &String.downcase/1),
      "lang" => lang,
      "duration_ms" => dur,
      "posted" => posted(primary),
      "pending_models" => pending,
      "vs_google" => vs_google(note, cands, phone),
      "candidates" => Enum.map(cands, &stringify/1)
    })

    for r <- to, post_on?(r), do: post_compare(r, sender, dur, lang, cands, pending)
    decide_and_tell(sender, lang)

    Logger.info(
      "speech compare: note #{note_id} lang=#{lang} to=#{length(to)} " <>
        Enum.map_join(cands, " ", &"#{&1.model}=#{&1.status}#{if &1.guard, do: "/" <> &1.guard}")
    )
  rescue
    e -> Logger.warning("speech compare failed: #{inspect(e.__struct__)}")
  end

  defp stringify(c), do: Map.new(c, fn {k, v} -> {to_string(k), v} end)

  # Option 0: what the chat actually got.
  defp chat_option(primary) do
    base = %{label: "What the chat got", model: "chat", ms: 0, guard: nil}

    case primary do
      {:ok, %{rejected: code} = r} when is_binary(code) ->
        Map.merge(base, %{status: "nothing (#{r.model} rejected: #{code})", text: nil})

      {:ok, %{text: t} = r} when is_binary(t) and t != "" ->
        Map.merge(base, %{
          status: "ok",
          text: String.trim(t),
          label: "What the chat got (#{r.model})"
        })

      _ ->
        Map.merge(base, %{status: "nothing (failed)", text: nil})
    end
  end

  defp posted({:ok, %{rejected: code} = r}) when is_binary(code),
    do: "#{r.model} (rejected: #{code})"

  defp posted({:ok, r}),
    do: "#{r.model}#{if r[:fallback], do: " (fallback #{r.fallback})", else: ""}"

  defp posted(_), do: "nothing (failed)"

  defp phone_option(_note, nil), do: []

  defp phone_option(note, phone),
    do: [
      %{
        label: phone_label(note),
        model: RisiMe.Agent.Speech.phone_model_of(Map.get(note, :phone_transcript)),
        status: "ok",
        text: phone,
        ms: 0,
        guard: nil
      }
    ]

  # Our models against the Google transcript (his correction, when given, wins).
  defp vs_google(note, cands, phone) do
    if RisiMe.Agent.Speech.phone_model_of(Map.get(note, :phone_transcript)) == "phone-google",
      do:
        cands
        |> Enum.reject(&(&1.model in ["phone-google", "chat"]))
        |> Enum.map(&stringify/1)
        |> scores(phone)
  end

  defp phone_label(note) do
    if RisiMe.Agent.Speech.phone_model_of(Map.get(note, :phone_transcript)) == "phone-google",
      do: "Google",
      else: "Phone (on-device)"
  end

  defp phone_text(note) do
    case RisiMe.Agent.Speech.phone_transcript(Map.get(note, :phone_transcript)) do
      {:ok, t, _} -> t
      _ -> nil
    end
  end

  @doc false
  def candidate(label, where, model, audio, dur, phone, lang, opts \\ []) do
    t0 = System.monotonic_time(:millisecond)
    night? = Keyword.get(opts, :night, false)

    r =
      cond do
        where == :extra and not night? and mem_available_mb() < min_7b_mb() ->
          {:error, :unavailable}

        where == :extra ->
          impl().extra_transcribe(audio, model, language: lang, timeout: 600_000)

        true ->
          impl().transcribe_model(audio, model, language: lang, timeout: 120_000)
      end

    ms = System.monotonic_time(:millisecond) - t0
    base = %{label: label, model: model, ms: ms, guard: nil, text: nil}

    case r do
      {:ok, res} ->
        guard =
          case SpeechQuality.check(res, dur, phone) do
            :ok -> nil
            {:reject, code, _} -> code
          end

        %{base | guard: guard, text: String.trim(res.text || "")} |> Map.put(:status, "ok")

      {:error, :unavailable} ->
        Map.put(base, :status, "skipped: memory")

      {:error, :off} ->
        Map.merge(base, %{status: "skipped: not configured", ms: 0})

      {:error, e} ->
        Map.put(base, :status, to_string(e))
    end
  end

  @doc "The candidate spec of `model` in `lang` (label, where), or nil."
  def spec(lang, model), do: Enum.find(models(lang), fn {_, _, m} -> m == model end)

  defp impl, do: Application.get_env(:risime, :risi_speech_compare_impl, RisiMe.Agent.Speech.Http)

  defp min_7b_mb do
    case Integer.parse(System.get_env("RISI_COMPARE_7B_MIN_MB") || "") do
      {n, ""} -> n
      _ -> 12_000
    end
  end

  @doc false
  def mem_available_mb do
    case File.read("/proc/meminfo") do
      {:ok, s} ->
        case Regex.run(~r/MemAvailable:\s+(\d+)/, s) do
          [_, kb] -> div(String.to_integer(kb), 1024)
          _ -> 0
        end

      _ ->
        0
    end
  end

  @lang_names %{"si" => "Sinhala", "ta" => "Tamil", "en" => "English"}

  defp post_compare(recipient, sender, dur, lang, cands, pending) do
    with rc when is_binary(rc) <- RisiMe.RisiChat.active_id(recipient) do
      lines =
        cands
        |> Enum.with_index(0)
        |> Enum.map(fn {c, i} ->
          head = "#{i}. #{c.label}"

          cond do
            c.status != "ok" ->
              "#{head}: #{c.status}"

            c.text == "" ->
              "#{head} (#{secs(c.ms)}): (nothing)"

            true ->
              "#{head} (#{secs(c.ms)}#{if c.guard, do: ", guard: " <> c.guard, else: ""}): #{c.text}"
          end
        end)

      whose =
        if String.downcase(recipient) == String.downcase(sender),
          do: "your voice note",
          else: "a consenting member's voice note"

      night =
        if pending != [],
          do: [
            "Skipped for memory, queued for tonight's run: #{Enum.join(pending, ", ")}. Results come after your morning brief."
          ],
          else: []

      body =
        Enum.join(
          [
            "#{@lang_names[lang]} compare for #{whose} (#{secs(dur)}).",
            lines,
            night,
            "Reply 0 if the chat got it right, the number of the right one, or \"fix: <the right words>\". Only you see this; it's kept sealed for evaluation only. \"compare off\" stops these messages."
          ]
          |> List.flatten(),
          "\n"
        )

      post(rc, recipient, body)
    end
  end

  @doc false
  def post(rc, user, body) do
    risi = %{
      "kind" => "answer",
      "request_id" => nil,
      "answer" => body,
      "refs" => [],
      "confidence" => 1.0,
      "sources" => [],
      "next_steps" => [],
      "call_ref" => nil,
      "made_by" => RisiMe.Agent.MadeBy.rule(),
      "notify" => [user]
    }

    Out.post(rc, body, risi, memory: false)
  end

  defp secs(ms) when is_integer(ms), do: "#{Float.round(ms / 1000, 1)} s"
  defp secs(_), do: "-"

  ## Labels

  @doc """
  A Risi-chat request (`Workers.Risi`): `:handled` when it is "compare off/on" or labels a compared
  note, else `:pass` (the normal request path).
  """
  def label_reply(conv, user, text) when is_binary(text) do
    case String.downcase(String.trim(text)) do
      c when c in ["compare off", "compare on"] -> switch_posts(conv, user, c)
      _ -> label_reply_text(conv, user, text)
    end
  end

  def label_reply(_, _, _), do: :pass

  defp switch_posts(conv, user, c) do
    if (dev_user?(user) or consent?(user)) and conv == RisiMe.RisiChat.active_id(user) do
      v = if c == "compare off", do: "off", else: "on"
      Settings.put(post_key(user), v, RisiMe.Risi.user_id())

      post(
        conv,
        user,
        if(v == "off",
          do:
            "Compare messages are off. Risi still scores the models quietly. Say \"compare on\" to see them again.",
          else: "Compare messages are on."
        )
      )

      :handled
    else
      :pass
    end
  end

  defp label_reply_text(conv, user, text) do
    with true <- enabled_for?(user) or dev_user?(user),
         true <- conv == RisiMe.RisiChat.active_id(user),
         {:ok, kind, value} <- parse(text),
         %{} = meta <- pending(user, kind) do
      label_text =
        case kind do
          :pick ->
            meta |> Map.get("candidates", []) |> Enum.at(value) |> then(&(&1 && &1["text"]))

          :typed ->
            value
        end

      if is_binary(label_text) and label_text != "" do
        by = if String.downcase(user) == meta["sender"], do: "sender", else: "dev"
        source = if kind == :pick, do: "pick:#{value}", else: "typed"
        scores = scores(meta["candidates"], label_text)
        SpeechGold.label(meta["note_id"], label_text, source, by)
        SpeechGold.put_scores(meta["note_id"], scores)
        decision = decide_and_tell(meta["sender"], meta["lang"] || "si", false)
        post(conv, user, saved_text(scores, decision))
        :handled
      else
        post(
          conv,
          user,
          "That number isn't one of the transcripts. Reply with a number from the list, or \"fix: <the right words>\"."
        )

        :handled
      end
    else
      _ -> :pass
    end
  rescue
    e ->
      Logger.warning("speech compare: label not stored (#{inspect(e.__struct__)})")
      :pass
  end

  @doc false
  def parse(text) do
    cond do
      m = Regex.run(~r/^\s*(\d{1,2})\s*$/u, text) ->
        {:ok, :pick, String.to_integer(Enum.at(m, 1))}

      m = Regex.run(~r/^\s*(?:fix|correct|නිවැරදි|திருத்தம்)\s*[:：]\s*(.+)$/isu, text) ->
        {:ok, :typed, String.trim(Enum.at(m, 1))}

      true ->
        :no
    end
  end

  # The note `user` may label now: a recipient picks or corrects an unlabelled note they were sent;
  # the sender corrects (typed only: they never saw the list) their own note, also over a dev
  # user's label (the sender's correction wins); nobody overwrites the sender's label.
  defp pending(user, kind) do
    u = String.downcase(user)
    cutoff = DateTime.add(DateTime.utc_now(), -@label_window_s, :second)

    SpeechGold.all_meta()
    |> Enum.filter(fn m ->
      fresh? =
        case DateTime.from_iso8601(m["at"] || "") do
          {:ok, at, _} -> DateTime.compare(at, cutoff) == :gt
          _ -> false
        end

      recipient? = u in (m["recipients"] || []) and not is_binary(m["label"])
      sender? = kind == :typed and m["sender"] == u and m["label_by"] != "sender"
      fresh? and (recipient? or sender?)
    end)
    |> Enum.max_by(& &1["at"], fn -> nil end)
  end

  @doc "Every candidate's CER / WER against the reference (numbers only)."
  def scores(cands, ref) do
    for c <- cands || [], is_binary(c["text"]), c["status"] == "ok", into: %{} do
      {c["model"], %{"cer" => cer(c["text"], ref), "wer" => wer(c["text"], ref)}}
    end
  end

  @doc "Character error rate of `hyp` against `ref` (normalised: lower case, no punctuation or spaces)."
  def cer(hyp, ref) do
    r = ref |> SpeechQuality.normalise() |> String.graphemes()
    h = hyp |> SpeechQuality.normalise() |> String.graphemes()
    if r == [], do: 0.0, else: Float.round(SpeechQuality.levenshtein(h, r) / length(r), 3)
  end

  @doc "Word error rate of `hyp` against `ref`."
  def wer(hyp, ref) do
    w = fn s ->
      s |> String.downcase() |> String.replace(~r/[\p{P}\x{200C}\x{200D}]/u, "") |> String.split()
    end

    r = w.(ref)
    if r == [], do: 0.0, else: Float.round(SpeechQuality.levenshtein(w.(hyp), r) / length(r), 3)
  end

  defp saved_text(scores, decision) do
    s =
      scores
      |> Enum.reject(fn {m, _} -> m == "chat" end)
      |> Enum.sort_by(fn {_, v} -> v["cer"] end)
      |> Enum.map_join(", ", fn {m, v} -> "#{m} CER #{pct(v["cer"])}" end)

    "Saved as the right transcript (sealed, evaluation only). #{s}." <>
      case decision do
        {:changed, choice, why} ->
          " The route for these notes now uses #{label(choice)} (#{why})."

        {:recommended, choice, why} ->
          " Recommended route: #{label(choice)} (#{why})."

        _ ->
          ""
      end
  end

  defp label("phone"), do: "the Google transcript (until one of our models beats it)"

  defp label("none"),
    do: "nothing (Risi shows \"couldn't hear clearly\" until a model is good enough)"

  defp label("auto"), do: "Auto"
  defp label(m), do: m

  defp pct(x) when is_number(x), do: "#{round(x * 100)}%"
  defp pct(_), do: "-"

  ## The route decision (promotion rule, per sender and language)

  defp decide_and_tell(sender, lang, tell? \\ true) do
    decision = route_decision(sender, lang)

    with true <- tell?,
         {kind, choice, why} when kind in [:changed, :recommended] <- decision,
         true <- dev_user?(sender) and post_on?(sender),
         rc when is_binary(rc) <- RisiMe.RisiChat.active_id(sender) do
      post(rc, sender, "Your #{@lang_names[lang]} notes now use #{label(choice)} (#{why}).")
    end

    decision
  end

  @doc """
  Recomputes `user`'s route for `lang` from their scored notes: `{:changed, choice, why}` (a dev
  user's per-user override), `{:recommended, choice, why}` (a consenting sender: recorded only), or
  `{:same, choice}`. Recorded in `risi_agent_runs` (agent voice, mode route).
  """
  def route_decision(user, lang \\ "si") do
    {n, means} = means(user, lang)
    {choice, why} = decide(n, means)
    current = SpeechRoutes.user_choice(user, lang)

    cond do
      choice == current ->
        {:same, choice}

      dev_user?(user) ->
        SpeechRoutes.put_user(user, lang, choice, RisiMe.Risi.user_id())
        record_route(user, lang, choice, "ok", why)
        {:changed, choice, why}

      true ->
        record_route(user, lang, choice, "recommended", why)
        {:recommended, choice, why}
    end
  end

  defp record_route(user, lang, choice, status, why) do
    RisiMe.Agents.Runs.record(%{
      agent: "voice",
      mode: "route",
      decision: choice,
      status: status,
      reason: String.slice(why, 0, 120),
      lang: lang,
      user: user
    })

    Logger.info("speech compare: route #{lang} -> #{choice} (#{status}: #{why})")
  end

  @doc """
  `{n, %{model => {mean_cer, notes}}}` over `user`'s scored notes in `lang` (all users and
  languages without arguments): each note's reference is the label when given, else the Google
  transcript (`vs_google`).
  """
  def means(user \\ nil, lang \\ nil) do
    notes =
      for m <- SpeechGold.all_meta(),
          user == nil or m["sender"] == String.downcase(user),
          lang == nil or (m["lang"] || "si") == lang,
          (sc = ref_scores(m)) != %{},
          do: sc

    models =
      Enum.uniq(for {_, _, m} <- Enum.flat_map(Map.values(@sets), & &1), do: m) ++
        ["phone-google"]

    means =
      for m <- models,
          cers = for(sc <- notes, c = get_in(sc, [m, "cer"]), is_number(c), do: c),
          cers != [],
          into: %{},
          do: {m, {Enum.sum(cers) / length(cers), length(cers)}}

    {length(notes), means}
  end

  defp ref_scores(%{"label" => l, "scores" => %{} = sc}) when is_binary(l),
    do: Map.delete(sc, "chat")

  defp ref_scores(%{"vs_google" => %{} = sc}), do: sc
  defp ref_scores(_), do: %{}

  @doc "The Speech page's scores (numbers only)."
  def scoreboard do
    {n, means} = means()

    %{
      n: n,
      labelled: length(SpeechGold.labelled()),
      models:
        means
        |> Enum.map(fn {m, {c, k}} -> %{model: m, mean_cer: Float.round(c, 3), notes: k} end)
        |> Enum.sort_by(& &1.mean_cer),
      routes:
        for(
          u <- Application.get_env(:risime, :risi_dev_users, []),
          do: %{
            user: String.slice(u, 0, 8),
            si: SpeechRoutes.user_choice(u, "si"),
            posts: post_on?(u)
          }
        ),
      consented: Enum.map(consented(), &String.slice(&1, 0, 8))
    }
  rescue
    _ -> %{n: 0, labelled: 0, models: [], routes: [], consented: []}
  end

  @doc "True while compare messages are posted in `user`'s Risi chat (default on)."
  def post_on?(user), do: Settings.get(post_key(user), "on") != "off"

  defp post_key(user), do: "speech_compare_post_user_" <> String.downcase(user)

  @doc false
  def decide(n, means) when n >= 5 do
    eligible = for {m, {c, k}} <- means, k >= 3, do: {m, c}

    case Enum.min_by(eligible, &elem(&1, 1), fn -> nil end) do
      nil ->
        {"auto", "n=#{n}, no model on 3+ notes yet"}

      {"phone-google", c} when c <= 0.6 ->
        {"phone", "Google leads: mean CER #{pct(c)} over #{n} notes"}

      {m, c} when c <= 0.6 ->
        {m, "lowest mean CER #{pct(c)} over #{n} notes"}

      {_, c} ->
        {"none", "best mean CER #{pct(c)} over #{n} notes is over 60%"}
    end
  end

  def decide(n, means) when n >= 1 do
    ctc = means["omnilingual-ctc-1b"]
    others = for {m, {c, _}} <- means, m != "omnilingual-ctc-1b", do: c

    cond do
      is_tuple(ctc) and elem(ctc, 0) <= 0.6 and Enum.all?(others, &(elem(ctc, 0) + 0.15 <= &1)) ->
        {"omnilingual-ctc-1b",
         "first #{n} notes: CTC clearly less wrong (CER #{pct(elem(ctc, 0))})"}

      is_tuple(g = means["phone-google"]) and elem(g, 0) <= 0.6 and
          Enum.all?(means, fn {m, {c, _}} -> m == "phone-google" or c > 0.6 end) ->
        {"phone", "first #{n} notes: only Google under 60% CER (#{pct(elem(g, 0))})"}

      means != %{} and Enum.all?(means, fn {_, {c, _}} -> c > 0.6 end) ->
        {"none", "first #{n} notes: every model over 60% CER"}

      true ->
        {"auto", "first #{n} notes: no clear winner yet"}
    end
  end

  def decide(_n, _means), do: {"auto", "no labelled notes"}

  @doc """
  The table of the labelled notes (newest `limit`): per note and model, CER / WER against the
  label, right (CER ≤ 20 %) or wrong. Numbers, model names and ids only, never text.
  """
  def report(limit \\ 5) do
    for m <- SpeechGold.labelled() |> Enum.take(limit) do
      %{
        note_id: m["note_id"],
        lang: m["lang"],
        at: m["labelled_at"],
        duration_ms: m["duration_ms"],
        posted: m["posted"],
        label_source: m["label_source"],
        label_by: m["label_by"],
        models:
          for c <- m["candidates"] || [] do
            s = get_in(m, ["scores", c["model"]])

            %{
              model: c["model"],
              status: c["status"],
              guard: c["guard"],
              cer: s && s["cer"],
              wer: s && s["wer"],
              right: is_map(s) and s["cer"] <= 0.2
            }
          end
      }
    end
  end
end
