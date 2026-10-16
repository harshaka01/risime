# Queue

Harsha's requests, picked up after each release in the order he sets; never in parallel with a release.
Working rule from nightly.8 on: one release a day around 07:00 SLT; each item is ON for everyone or not shipped.

## 15 Oct 12:45 + 14:00 P0 Risi intelligence (Harsha), order: brain fix 1–7 + A + B, then C, D, E
- **Brain fix (in progress):** spoken answers from tool results, dates and follow-ups, phone titles (§41.8, v1.52;
  app half in the next APK), Risi's own items in every calendar answer, a true "what can you do", no stuck turns,
  merged fragments; A) "One moment…" then hold, speech merges, interrupt only by button/"Risi, stop", ignore
  thinking aloud ("Was that for me?"), "Still checking…" after 4 s; B) promises/Ledger/notes/Risi Calendar always
  available. Server hotfix; "Risi brain: try now" when his conversation works end to end.
- **Call Risi double-talk (device test 15 Oct):** caller speech that starts right as Risi starts speaking can be lost
  (no barge-in, no utterance): the worker needs 300 ms of strictly consecutive voiced windows above the echo level and
  keeps only `run + PREROLL_WINS` of `pre` (infra/voice/risi_voice_worker.py). The session hold covers caller-first.
- **C) Voice quality (frozen 15 Oct 15:00, Harsha):** gapless streamed TTS; 3 candidate voices (Kokoro vs Orpheus/Chatterbox)
  to rate on the Voices page; use his pick. Built (8fb149ad worker, 805f589b Voices page), reverted on main by 40ab4a93 /
  e4957651 under the freeze; resume = revert the reverts + a real harness gap run. Orpheus weights missing (night download).
- **C) Voice, Harsha 20:26 (starts right after the brain device test passes):**
  1. TTS lab candidates: Chatterbox-Turbo (MIT, 350M) and Dia2 (Apache) next to Kokoro and Orpheus. Night
     download allowed even when > 2 GB. One stock voice each (warm, friendly, professional, adult, clear). Streaming
     with continuous playback.
  2. Rating: 3 samples per engine in his Risi chat (a greeting, a calendar answer, a "let me check" line). He replies
     1/2/3/4, and his pick becomes the call voice. Kokoro stays the fallback when GPU memory is short.
  3. Fillers upgrade:
     - lines fit the context and vary;
     - timing: at once, then ~4 s "still on it…", then ~10 s "this is taking a bit longer";
     - no line repeated within a call;
     - "anything else while I'm at it?" (what he says merges into the same request);
     - rare natural touches or tags;
     - never hint at a result, never claim to be human.
  4. Measure start-of-speech p50/p95 per engine on calls, plus his rating. Ship his pick as a server hotfix and send
     him one line with the numbers.
- **D) (frozen):** wip 1e084c1c on branch worktree-agent-aac73afcdb58cac1f (weather + SearXNG, untested; no decision doc yet).
- **D) Open-domain answers:** model knowledge + weather (Open-Meteo, no key) + web search (self-hosted SearXNG,
  outbound only the query); a decision; "what can you do" lists them.
- **E) Voice notes in any language** (Whisper large-v3): original transcript + Translate to en/si/ta for everyone,
  plus a "Translate to…" picker for any language.
- Each ships as a server hotfix when green; one line to Harsha per item.

## 15 Oct 04:43 order (Harsha): nothing else until these are done
- **P0-1 Official calls:** a) long call got items but no summary → map-reduce, a 30-min gate case (in progress);
  b) live line ≤ 2 s (VAD 2–4 s chunks, 0.5 s overlap, fastest en model, GPU priority, p50/p95 in ci.md);
  c) two-pass: draft live lines, after End re-transcribe per speaker with the best model per language + context,
  risi-l1 correction ([unclear], no invention), summary from the corrected transcript; sealed audio ≤ 30 min,
  Official only (decision + contract note); **04:51 addition:** multi-hypothesis correction for si/ta voice notes and
  pass 2 (CTC-1B + whisper-small + Qwen in parallel → risi-l1 merges; English words stay English; [unclear]; never
  unsupported content); compare option "R"; win rate in docs/RISI-MODELS.md after 20 labelled notes;
  d) one-tap right/wrong per line, WER before/after.
- **P0-2 Call Risi:** land 7ce5b871 + acb68a4e, device test on Harsha's phone, ON for him, pilot after he confirms;
  English first, si/ta → one line + answer in English.
- **P1-3 Video + YouTube in chat:** inline YouTube (official IFrame player, "Open in YouTube"), inline mp4, own
  videos (720p, thumbnail, stream, E2EE blobs); first say what exists.
- **Next APK (app items found 15 Oct):** the Official summary card renders `key_points` (then `card_line` goes back to
  the plain summary); its count line no longer says "0 items" above an items card; Call Risi failure says "Risi
  couldn't join the call. Try again." with a Try again button; when Call Risi is on for others, the button follows
  the server switch only (no developer switch); RISI_LIVE_FAST app chunker (branch worktree-agent-a5446a9fcfee99bb3).
- **Call Risi blocker:** the pilot has no `/livekit` Caddy route and no LIVEKIT_URL, so rooms answer 503 (group calls
  too). Harsha runs the Caddy block (docs/status/admin-console.md A); then root sets LIVEKIT_URL, restarts LiveKit
  (loopback candidate), one headless call, "try now".
- **Word-by-word live lines** (Harsha 07:48): partial lines that fill in as words arrive, < 2 s, English first
  (a wire change: contract first). After the summary/correction items (P0-1c).
- The release-speed item (0 below) follows these.

0. **15 Oct, before any other work: release speed** (Harsha 21:55). Release ≤ 10 min from "release" to APK on
   his phone; push → green ≤ 15 min. Continuous gates on every push to main (local runner on another GX10 node, not
   spark2; give him exact sudo/network commands); a release = tag the newest green commit + publish its already-built
   APK, no gates at release time. Parallel server partitions, `mix test --stale` per push, the full suite nightly;
   Gradle build + configuration cache, warm daemon, one APK per commit reused by every gate and the release; warm
   redroids reset instead of rebooted. Tiers: push = compile, unit tests, contract examples (≤ 5 min); green main =
   official-start, call-summary, upgrade/rule 9, health-heal; nightly = full suite + evals. Stage times in
   docs/status/ci.md after every run. Report before/after times; say if a target can't be met and why.
   Before: nightly.8 (RELEASE_CORE, started 16:12 UTC) stage times recorded in ci.md when it ends.
1. (after nightly.8) Run the gates left out of the core release (list in overnight.md, 14 Oct); fix real regressions.
2. Send a message for me: switch on for everyone once send-for-me-test passes on the release build.
3. Calendar v2: the app half (branch calendar-v2) is not on main; finish it and its gate, then switch on for everyone.
4. DB rename risime_dev → prod (Harsha's conditions: verified backup, rule-9 counts before/after, roll back on any
   mismatch, message him).

## Harsha 2026-10-16 04:49 / 04:52 SLT
- **Voice pick:** A, Kokoro (for now). Live already: the call voice is `kokoro:af_heart`, the sampled voice. No change needed.
- **URGENT hearing hotfix (in progress):** server srvN (VAD gate, no-speech/low-logprob drops, hallucination
  phrases, no STT during Risi's speech, "Sorry, I didn't catch that") and APK `0.3.0-nightly.11-hotfix`
  (contract v1.53 §41.10, on-device English STT), with only this in it. Gates: compile, unit tests,
  upgrade/rule 9, one real Call Risi test with his 04:4x call script. Other gates skipped by Harsha; say so in
  the notes.
- **NEXT, after the hotfix: ADMIN SWITCHBOARD** (/admin, admins only, per user, live within 5 s, audited):
  - call voice: Kokoro / Chatterbox-Turbo / Dia2 / Orpheus, with stock voice, measured latency and a "too slow
    for calls" warning;
  - call STT: phone on-device / server Whisper large-v3 / turbo;
  - brain model: risi-l1 / candidates once evaluated;
  - "Apply to: only me / all users", default only me.
  - Admin sign-in: Keycloak step B (redirect URI) is still missing. Checked 23:4xZ: "Invalid parameter:
    redirect_uri".
- Chatterbox-Turbo samples resent to his Risi chat (23:4xZ). All 4 engines had posted at 17:34Z, so the cause
  of the missing message is unknown: check the app's handling of 4 Risi posts in the same second.
- Deferred from the 23:14Z call (not hearing): the empty "Fri 16 Oct:" card during a call, the brief
  BadBooleanError, one 4.8-s chat turn, side-talk replies, the "for two" → 2 pm write.
- **Harsha 2026-10-16 ~05:00 SLT, if he switches to voice 2 (Chatterbox-Turbo):**
  - pre-render the greeting and every filler line in Chatterbox-Turbo, cached so they play at once;
  - stream answers sentence by sentence;
  - Kokoro only as the fallback when GPU memory is short;
  - report call reply-start p50/p95 after the switch.
  - Build it ready (behind the pick settings) right after the hearing hotfix, so a "Root: switch to 2" ships in minutes.
- **Harsha 2026-10-16 05:19 SLT · translate picker (in the next app build after the hearing hotfix):**
  - default buttons Sinhala / English / Tamil, with the source language hidden;
  - "More…" opens a searchable list of all languages;
  - each user's recent picks at the top;
  - the same picker for voice-note transcripts, typed messages and the always-translate setting.
- **From the call-5 replay on srv15 (not in the hotfix):**
  - "Can I have a meeting set up today for two?" is still routed as a calendar read. It should be an add at 2 pm, or
    "2 pm?".
  - On "Yeah.", the reply started with "I can't book it for you right now,". The claim guard caught the rest, but
    this refusal-style line got through.
  - `brief.ex` `due_today`: a promise with no due date gives `nil and …`, which raises BadBooleanError. Fix:
    `&1.due != nil and …`.
  - Empty "Fri 16 Oct:" card during a call: in `turn.ex` `finish`, skip `Audience.deliver` while a call runs and the
    body is only the head line and the footer.
- **Release tooling (root):** `HOTFIX=` releases must never switch the pilot from a newer main `-srvN` build. On
  16 Oct this was blocked by hand, with an uncommitted guard in `~/risime-hotfix-wt/scripts/run-server`. Put the
  guard in main's run-server. The hotfix → main merge also conflicts every time: merge with `-s ours` plus
  VERSION and the notes.

- 2026-10-16 10:15 SLT (root): **next APK** = phone STT fixes on main (1c8c0111: mode packet no longer dropped before publishMic, non-blocking pipe, phone end-of-speech, `off` instead of silence, conf 0 → null; 2081 tests green) + the translate picker. Released after Call Risi brain v3 ships (the only server work until then, architect 09:40), so Harsha can test both in one call. Then accept or reject contract/proposals/2026-10-16-stt-fallback.md (`alive` → fall back on the first clear miss). If his next call still falls back: the `risi.stt:` lines in server.log (worker counters, deployed 10:10) and `adb logcat -s RisiMe` from his phone.

- 2026-10-16 (root, from brain v3): contract §34.2 `confirm_update` state `cancelled` (a voice "cancel that" posts it; the app shows an unknown state as "Closed", which is true but vague). Next contract bump + app string "Cancelled". Also v3 leftovers: every v3 turn opens with "One moment…" (risi-l1 decides in 1.2–2 s; a faster model or pre-rendered audio fixes it); call 5 "a meeting today for two" goes to the §25 turn's own model loop (calendar text, no card); the message card path is tested only in golden/unit tests (the test account has no friends and Whisper drops synthetic names).

- 2026-10-16 14:05 SLT (architect, Harsha's photos of Regina's phone):
  - **APK (next build, with the 11:50 call fixes; released as soon as those are green):** voice note → its transcript (with Translate) → the Risi action-items card; the card collapsed to one line ("Risi · 2 action items ›"), tap to expand, never over message content. The translate picker (en/si/ta first, "More languages…" with search, recent picks on top) on voice-note transcripts and typed messages.
  - **Server (risi):** "today" with no time → due 18:00 local, shown "today" (not the second it was said); momentary requests ("turn on the camera", "can you hear me") are no commitments, with negatives in the extraction eval; confirm Tamil voice notes reach extraction (Regina's status-update note: none is correct, but check it ran).
