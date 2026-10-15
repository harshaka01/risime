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
