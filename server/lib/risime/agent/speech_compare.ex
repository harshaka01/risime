defmodule RisiMe.Agent.SpeechCompare do
  @moduledoc """
  Harsha's Sinhala compare mode (Harsha 2026-10-14 08:35 SLT; docs/status/asr-harsha-gold.md).
  Owned by the Voice agent (its transcription Index learns from his labels).

  * **Who:** only `RISI_DEV_USERS`' **own** voice notes, while `RISI_SPEECH_COMPARE_DEV` is not
    `off` (default `canary` = the dev users), and only Sinhala / Singlish notes (the note's
    language, or a `si` hint).
  * **What:** after the normal path (the Official chat gets only the guarded best transcript), a
    task runs every candidate on the same in-memory audio: `qwen3-asr-0.6b-sinhala` and
    `omnilingual-ctc-1b` on the speech service, `omniasr-llm-7b` (streamed) on the omniASR compare
    service only while MemAvailable ≥ `RISI_COMPARE_7B_MIN_MB` (default 12 000; else "skipped:
    memory"), and the note's phone transcript when present. Each is run through the hallucination
    guard (`SpeechQuality`) and labelled with its code.
  * **Where:** **his own Risi chat only** (never the Official chat: other members never see a
    compare dump), one numbered message. He replies `1`…`n` for the right one or
    `fix: <the right words>`; the next such reply within 2 h labels the newest unlabelled note.
  * **Gold:** the audio and the candidates are stored sealed (`SpeechGold`, eval only, a
    `NO_TRAINING` marker); his label is added, and every candidate's CER / WER against it.
  * **Default route for him** (`route_decision/1`, the promotion rule, recorded as a
    `risi_agent_runs` row, agent `voice`, mode `route`, and a line in his Risi chat): from 5
    labelled notes, the server model with the lowest mean CER (≤ 60 %, else "none" = show nothing);
    before that, `omnilingual-ctc-1b` when it is clearly (15 points) less wrong than every other,
    "none" when every model is over 60 % CER, else Auto. It is his per-user override
    (`SpeechRoutes.user_choice/2`), never anyone else's route.
  * Logs: ids, codes and numbers only.
  """
  require Logger

  alias RisiMe.Agent.{Lang, Out, SpeechGold, SpeechQuality, SpeechRoutes}
  alias RisiMe.Agents.Flags

  @flag "RISI_SPEECH_COMPARE_DEV"
  @models [
    {"Qwen3-ASR Sinhala", :service, "qwen3-asr-0.6b-sinhala"},
    {"Whisper small Sinhala", :service, "whisper-small-sinhala"},
    {"omniASR-CTC-1B", :service, "omnilingual-ctc-1b"},
    {"omniASR-LLM-7B", :extra, "omniasr-llm-7b"}
  ]
  @label_window_s 7_200

  @doc "True when the compare mode runs for `user`'s notes."
  def enabled_for?(user) do
    is_binary(user) and dev_user?(user) and Flags.feature(@flag) != :off
  rescue
    _ -> false
  end

  defp dev_user?(user),
    do:
      String.downcase(user) in Enum.map(
        Application.get_env(:risime, :risi_dev_users, []),
        &String.downcase/1
      )

  @doc """
  After the normal transcription (`Voice`): starts the compare task for a dev user's Sinhala note.
  `primary` is `Speech.transcribe_note/2`'s result. Never blocks the caller.
  """
  def maybe_start(conv, note_id, sender, audio, note, primary, hint) do
    if enabled_for?(sender) and sinhala?(primary, hint) and is_binary(note_id) do
      Task.Supervisor.start_child(RisiMe.Agent.Voice.TaskSup, fn ->
        run(conv, note_id, sender, audio, note, primary)
      end)
    end

    :ok
  rescue
    _ -> :ok
  end

  defp sinhala?({:ok, %{language: "si"}}, _hint), do: true
  defp sinhala?(_, hint), do: "si" in List.wrap(hint)

  @doc false
  def run(conv, note_id, sender, audio, note, primary) do
    dur = note.duration_ms
    phone = phone_text(note)

    cands =
      Enum.map(@models, fn {label, where, model} ->
        candidate(label, where, model, audio, dur, phone)
      end) ++
        if(phone,
          do: [
            %{
              label: phone_label(note),
              model: RisiMe.Agent.Speech.phone_model_of(Map.get(note, :phone_transcript)),
              status: "ok",
              text: phone,
              ms: 0,
              guard: nil
            }
          ],
          else: []
        )

    posted =
      case primary do
        {:ok, %{rejected: code} = r} when is_binary(code) -> "#{r.model} (rejected: #{code})"
        {:ok, r} -> "#{r.model}#{if r[:fallback], do: " (fallback #{r.fallback})", else: ""}"
        _ -> "nothing (failed)"
      end

    SpeechGold.put(note_id, audio, %{
      "at" => DateTime.to_iso8601(DateTime.utc_now()),
      "conversation_id" => conv,
      "duration_ms" => dur,
      "posted" => posted,
      # Our models against his opt-in Google transcript (his own correction, when he gives one,
      # is the label and wins; these are the interim scores).
      "vs_google" =>
        if(RisiMe.Agent.Speech.phone_model_of(Map.get(note, :phone_transcript)) == "phone-google",
          do:
            cands
            |> Enum.reject(&(&1.model == "phone-google"))
            |> Enum.map(&Map.new(&1, fn {k, v} -> {to_string(k), v} end))
            |> scores(phone)
        ),
      "candidates" => Enum.map(cands, &Map.new(&1, fn {k, v} -> {to_string(k), v} end))
    })

    if post_on?(sender), do: post_compare(sender, dur, posted, cands)
    # Shadow (Harsha 2026-10-14): the Voice agent re-scores on every note (Google as the
    # reference until he corrects one) and applies the promotion rule; no output but the line
    # in his Risi chat when the route changes (and only while his compare posts are on).
    decision = route_decision(sender)

    case decision do
      {:changed, choice, why} ->
        if post_on?(sender) do
          with rc when is_binary(rc) <- RisiMe.RisiChat.active_id(sender),
               do: post(rc, sender, "Your Sinhala notes now use #{label(choice)} (#{why}).")
        end

      _ ->
        :ok
    end

    Logger.info(
      "speech compare: note #{note_id} " <>
        Enum.map_join(
          cands,
          " ",
          &"#{&1.model}=#{&1.status}#{if &1.guard, do: "/" <> &1.guard, else: ""}"
        )
    )
  rescue
    e -> Logger.warning("speech compare failed: #{inspect(e.__struct__)}")
  end

  # Harsha's opt-in Google online recogniser is "Google"; the on-device one "Phone (on-device)".
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

  defp candidate(label, where, model, audio, dur, phone) do
    t0 = System.monotonic_time(:millisecond)

    r =
      cond do
        where == :extra and mem_available_mb() < min_7b_mb() -> {:skip, "skipped: memory"}
        where == :extra -> impl().extra_transcribe(audio, model, language: "si", timeout: 180_000)
        true -> impl().transcribe_model(audio, model, language: "si", timeout: 60_000)
      end

    ms = System.monotonic_time(:millisecond) - t0

    case r do
      {:ok, res} ->
        guard =
          case SpeechQuality.check(res, dur, phone) do
            :ok -> nil
            {:reject, code, _} -> code
          end

        %{
          label: label,
          model: model,
          status: "ok",
          text: String.trim(res.text || ""),
          ms: ms,
          guard: guard
        }

      {:skip, why} ->
        %{label: label, model: model, status: why, text: nil, ms: 0, guard: nil}

      {:error, :unavailable} ->
        %{label: label, model: model, status: "skipped: memory", text: nil, ms: ms, guard: nil}

      {:error, :off} ->
        %{
          label: label,
          model: model,
          status: "skipped: not configured",
          text: nil,
          ms: 0,
          guard: nil
        }

      {:error, e} ->
        %{label: label, model: model, status: to_string(e), text: nil, ms: ms, guard: nil}
    end
  end

  defp impl, do: Application.get_env(:risime, :risi_speech_compare_impl, RisiMe.Agent.Speech.Http)

  defp min_7b_mb do
    case Integer.parse(System.get_env("RISI_COMPARE_7B_MIN_MB") || "") do
      {n, ""} -> n
      _ -> 12_000
    end
  end

  defp mem_available_mb do
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

  defp post_compare(sender, dur, posted, cands) do
    with rc when is_binary(rc) <- RisiMe.RisiChat.active_id(sender) do
      lines =
        cands
        |> Enum.with_index(1)
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

      body =
        Enum.join(
          [
            "Sinhala compare for your voice note (#{secs(dur)}). The chat got: #{posted}.",
            lines,
            "Reply with the number of the right one, or \"fix: <the right words>\". Only you see this; it's kept sealed for evaluation only."
          ]
          |> List.flatten(),
          "\n"
        )

      post(rc, sender, body)
    end
  end

  defp post(rc, sender, body) do
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
      "notify" => [sender]
    }

    Out.post(rc, body, risi, memory: false)
  end

  defp secs(ms) when is_integer(ms), do: "#{Float.round(ms / 1000, 1)} s"
  defp secs(_), do: "-"

  ## Labels

  @doc """
  A Risi-chat request of a dev user (`Workers.Risi`): `:handled` when it labels the newest
  unlabelled compared note (`1`…`n` or `fix: …`), else `:pass` (the normal request path).
  """
  def label_reply(conv, user, text) when is_binary(text) do
    case String.downcase(String.trim(text)) do
      c when c in ["compare off", "compare on"] -> switch_posts(conv, user, c)
      _ -> label_reply_text(conv, user, text)
    end
  end

  def label_reply(_, _, _), do: :pass

  defp switch_posts(conv, user, c) do
    if enabled_for?(user) and conv == RisiMe.RisiChat.active_id(user) do
      v = if c == "compare off", do: "off", else: "on"
      RisiMe.Admin.Settings.put(post_key(user), v, RisiMe.Risi.user_id())

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
    with true <- enabled_for?(user),
         true <- conv == RisiMe.RisiChat.active_id(user),
         {:ok, kind, value} <- parse(text),
         %{} = meta <- pending() do
      label_text =
        case kind do
          :pick ->
            meta |> Map.get("candidates", []) |> Enum.at(value - 1) |> then(&(&1 && &1["text"]))

          :typed ->
            value
        end

      if is_binary(label_text) and label_text != "" do
        source = if kind == :pick, do: "pick:#{value}", else: "typed"
        scores = scores(meta["candidates"], label_text)
        SpeechGold.label(meta["note_id"], label_text, source)
        SpeechGold.put_scores(meta["note_id"], scores)
        decision = route_decision(user)
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
      other ->
        Logger.debug("speech compare: no label (#{inspect(other)})")
        :pass
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

      m = Regex.run(~r/^\s*(?:fix|correct|නිවැරදි)\s*[:：]\s*(.+)$/isu, text) ->
        {:ok, :typed, String.trim(Enum.at(m, 1))}

      true ->
        :no
    end
  end

  defp pending do
    cutoff = DateTime.add(DateTime.utc_now(), -@label_window_s, :second)

    SpeechGold.unlabelled()
    |> Enum.filter(fn m ->
      case DateTime.from_iso8601(m["at"] || "") do
        {:ok, at, _} -> DateTime.compare(at, cutoff) == :gt
        _ -> false
      end
    end)
    |> Enum.max_by(& &1["at"], fn -> nil end)
  end

  @doc "Every candidate's CER / WER against the label (numbers only)."
  def scores(cands, label) do
    for c <- cands || [], is_binary(c["text"]), c["status"] == "ok", into: %{} do
      {c["model"], %{"cer" => cer(c["text"], label), "wer" => wer(c["text"], label)}}
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
      |> Enum.sort_by(fn {_, v} -> v["cer"] end)
      |> Enum.map_join(", ", fn {m, v} -> "#{m} CER #{pct(v["cer"])}" end)

    "Saved as the right transcript (sealed, evaluation only). #{s}." <>
      case decision do
        {:changed, choice, why} -> " Your Sinhala notes now use #{label(choice)} (#{why})."
        _ -> ""
      end
  end

  defp label("phone"), do: "your Google transcript (until one of our models beats it)"

  defp label("none"),
    do: "nothing (Risi shows \"couldn't hear clearly\" until a model is good enough)"

  defp label("auto"), do: "Auto"
  defp label(m), do: m

  defp pct(x) when is_number(x), do: "#{round(x * 100)}%"
  defp pct(_), do: "-"

  ## The route decision (promotion rule, his notes only)

  # The route candidates: our server models and his opt-in Google transcript (route "phone":
  # the phone's transcript is shown; ours takes over once it beats Google on his notes).
  @server_models [
    "qwen3-asr-0.6b-sinhala",
    "omnilingual-ctc-1b",
    "omniasr-llm-7b",
    "phone-google"
  ]

  @doc """
  Recomputes `user`'s Sinhala route from the labelled notes; `{:changed, choice, why}` or
  `{:same, choice}`. Recorded in `risi_agent_runs` (agent voice, mode route) and the settings audit.
  """
  def route_decision(user) do
    {n, means} = means()

    {choice, why} = decide(n, means)
    current = SpeechRoutes.user_choice(user, "si")

    if choice != current do
      SpeechRoutes.put_user(user, "si", choice, RisiMe.Risi.user_id())

      RisiMe.Agents.Runs.record(%{
        agent: "voice",
        mode: "route",
        decision: choice,
        status: "ok",
        reason: String.slice(why, 0, 120),
        lang: "si",
        user: user
      })

      Logger.info("speech compare: route for a dev user si -> #{choice} (#{why})")
      {:changed, choice, why}
    else
      {:same, choice}
    end
  end

  @doc """
  `{n, %{model => {mean_cer, notes}}}` over the scored notes: each note's reference is his label
  when he gave one (a correction beats Google), else his Google transcript (`vs_google`).
  """
  def means do
    notes = for m <- SpeechGold.all_meta(), (sc = ref_scores(m)) != %{}, do: sc
    n = length(notes)

    means =
      for m <- @server_models,
          cers = for(sc <- notes, c = get_in(sc, [m, "cer"]), is_number(c), do: c),
          cers != [],
          into: %{},
          do: {m, {Enum.sum(cers) / length(cers), length(cers)}}

    {n, means}
  end

  defp ref_scores(%{"label" => l, "scores" => %{} = sc}) when is_binary(l), do: sc
  defp ref_scores(%{"vs_google" => %{} = sc}), do: sc
  defp ref_scores(_), do: %{}

  @doc "The Speech page's scores: `%{n, models: [%{model, mean_cer, notes}], route}` per dev user."
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
        )
    }
  rescue
    _ -> %{n: 0, labelled: 0, models: [], routes: []}
  end

  ## His off switch for the compare posts ("compare off" / "compare on" in his Risi chat)

  @doc "True while the numbered compare message is posted in `user`'s Risi chat (default on)."
  def post_on?(user), do: RisiMe.Admin.Settings.get(post_key(user), "on") != "off"

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
  The table of the labelled notes (newest `limit`): per note and model, CER / WER against his
  label and right (CER ≤ 20 %) or wrong. Numbers, model names and ids only, never text.
  """
  def report(limit \\ 5) do
    for m <- SpeechGold.labelled() |> Enum.take(limit) do
      %{
        note_id: m["note_id"],
        at: m["labelled_at"],
        duration_ms: m["duration_ms"],
        posted: m["posted"],
        label_source: m["label_source"],
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

  @doc "The language hint helper for callers without one (the sender's spoken language)."
  def hint(sender, conv), do: Lang.speech_hint([sender], conv)
end
