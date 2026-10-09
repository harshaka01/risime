# Proposal: §27 (v1.27) model transparency, Ledger follow-ups after discussions and calls, call transcription

**Status:** requested by Harsha (2026-10-09). Root folds it into PROTOCOL as **§27 (v1.27)**; the
details are root decisions (rule 8), recorded in `docs/decisions/070-ledger-and-transcription.md`.
The normative text is PROTOCOL §27; this file states the requirements, the choices and the work.

## Harsha's requirements (binding)
- **A. Model transparency.** On every Risi message, a tap on the Risi name or ⓘ shows "Made by:
  <model> · <time>". `risi-l1` is shown as "RisiMe model (risi-l1)"; a commercial model by its
  provider's name.
- **B. Follow-ups after a discussion or call (the Commitment Ledger end to end).**
  - **Trigger:** an Official discussion goes quiet (about 10 min), or a Risi-transcribed call ends.
  - **What each participant gets, in their own Risi chat:** "Summary of your discussion with
    Shenika (09:12, video call 32 min)", the key points, then "You agreed:" and "<Name> agreed:"
    items, each with an owner and a due date.
  - **Confirming:** each person confirms only their own items (✓ / ✗ / edit). Confirmed items go to
    My promises.
  - **Reminders:**
    - the owner gets one before the due time and one at it;
    - the other party gets "…is due today", only if the item was confirmed;
    - overdue items get a gentle follow-up to the owner, then a nudge to the other party.
  - **In the Official chat:** a short card, "Details in your Risi chat".
- **C. Call transcription (stage 3, decision 066).**
  - **Where:** only calls started from the Official tab. Private calls are never transcribed.
  - **Visibility:** "Risi is listening" is on everyone's call screen from the start.
  - **E2EE stays intact:** Risi is a visible LiveKit participant with frame keys from its MLS
    membership. Opus is decoded server-side, and faster-whisper transcribes on spark2 (GPU).
  - **Data:** raw audio is never stored. The transcript goes only into the sealed 24 h buffer and
    is deleted after extraction.
  - **To decide:**
    - old apps;
    - mid-call joins;
    - a per-call "Stop Risi listening".

## Choices (root, decision 070)

### A. `made_by`
- `risi.made_by = {model, provider, at, also[]}` comes from the learning-log row of `call_ref`.
- **`model`:**
  - local models use their alias (`risi-l1`);
  - commercial models use the provider's model id;
  - `null` means rules, not a model (reminders, item updates, `call_listen`).
- **`provider`:** `risime`, or the commercial provider's display name.
- **`also`:** the other models the content rests on, e.g. `faster-whisper-large-v3` with task
  `transcribe` for a call summary.
- **Labels the phone shows:**
  - "RisiMe model (risi-l1)";
  - "RisiMe (no AI model)";
  - "<Provider> (<model>)".
- Every v1.27 server sends it on every Risi envelope. No capability is needed (old apps ignore the
  field).

### B. The Ledger
**The quiet rule.** All of these must hold, per Official conversation and never in a Risi chat:
- the newest human `text` is at least 600 s old;
- since the last summary, at least 6 human messages from at least 2 humans;
- the last summary was at least 30 min ago, and there are at most 8 a day;
- after 4 h of non-stop discussion, the next 3-min gap triggers.

A listened call triggers at its end if it has at least 60 s of speech from at least 2 humans. A
call trigger also covers the chat typed during the call.

**A trigger with no extracted item posts nothing.** This keeps noise out of Risi chats.

**`discussion_summary`** goes to each recipient's own Risi chat.
- **Recipients:** the participants plus the item owners, all active members.
- **Fields:** `summary_id`, `chat_id`, `conversation_id`, `for`, `with[]`, `started_at`,
  `ended_at`, `source` (`chat` | `call`), `call_id?`, `media?`, `duration_s?`, `key_points[]` and
  `items[]`. Each item is `{item_id, owner, counterpart[], text, due, all_day, due_text, state}`.
  The copy also carries `expires_at` (48 h).
- **`body`:** rendered per recipient in their zone, so older apps can read it.

**Actions** are sent in the actor's own Risi chat:
- `risi_action` `item_confirm`, `item_decline` and `item_edit` (`{text, due, all_day}`), owner only;
- the v1.24 `done`, owner or counterpart.
- `item_update` reaches every recipient's copy and is applied without a separate bubble.
- An item nobody confirms expires after 48 h.

**Reconciling with §24.11 (recommendation adopted).**
- The in-group `proposed` commitment card is **replaced** by the per-person flow, and the group
  gets only the short **`discussion_card`**. The card has a one-line summary, the item count and
  "Details in your Risi chat". It shows no items, owners or dues: a promise isn't shown to the
  whole group before its owner agreed.
- **Fallback:** an owner without a `risi_ledger` device gets the v1.24 in-group card and v1.24
  timing. A recipient whose Risi chat isn't created yet has their copy held for 24 h.

**Reminders** go to each person's own Risi chat:

| kind | to | when |
|---|---|---|
| `item_due` `before` | owner | due − 1 h; date-only: 09:00 the day before |
| `item_due` `at` | owner | due; date-only: 09:00 on the day |
| `item_due` `today` | counterparts, confirmed items only | 09:00 on the due date, or due − 1 h if earlier |
| `item_overdue` | owner | due + 2 h; date-only: 10:00 the next day |
| `item_nudge` | counterparts | 24 h after `item_overdue` |

- The overdue follow-up and the nudge happen once per item. The owner's buttons are [Done] and
  [New date].
- A personal 09:00 digest replaces the group digest when every member has the ledger.

### C. Transcription
**Starting.**
- The starter's start sheet in Official has "Risi listens for follow-ups", on by default.
- `POST /calls/rooms` `start` takes `risi_listen: true`. The server then sets room metadata
  `r: 1`, adds a +1 seat and counts only human identities against the caps.
- The reply carries `risi.state` (`requested`, `unavailable` or `off`).
- The MLS `call_offer` and `group_call started` carry `"risi": "listen"`.
- Risi joins only after decrypting that `group_call started` from a human leaf. It doesn't join
  later than 30 s.

**Risi in LiveKit.**
- Its identity is `<risi_user_id>/<risi_device_id>`, its leaf identity, so phones name it from MLS.
- Its token is minted only inside the server (REST still refuses agent devices). The grants are
  subscribe only: no publish, `hidden: false`, `recorder: false`.
- It subscribes to microphone tracks only, and transcribes only while ≥ 2 humans are connected.
- Its frame keys come from its own MLS state (`call_frame_keys`), with the §20.6 rekey rules.

**The call-screen state.** "Risi is listening" shows when:
- Risi's identity is in the room (authoritative);
- or the MLS intent says `listen` (the first 30 s);
- or the `call_risi` signal says so.

It also shows on the incoming screen and the Join line (`status` carries `risi`). A one-time
explainer appears the first time a user is in a listened call. Joining is consent. The durable
`call_listen` lines (`listening` / `stopped` with a reason) keep a record in the chat.

**Stop.** Any participant can tap [Stop Risi listening], which sends `POST /calls/rooms`
`action: "risi_stop"`.
- Risi leaves and **deletes the call's transcript**, and no summary is made.
- The `call_risi` `stopped` signal goes out, then the `call_listen` `stopped` line.
- It is idempotent, and there is no restart in that call.

**Mid-call joins and old apps.**
- A `risi_ledger` device joins normally and sees the banner first.
- A device without `risi_ledger` makes the server stop Risi **before** its token is minted
  (`old_app`). **The call is never blocked; the transcription is refused.**

**The audio path.**
- `risi-listen` is Rust (the LiveKit Rust SDK), run as an Erlang Port of `RisiMe.Agent`. It
  receives keys in memory.
- libwebrtc decrypts and decodes Opus. PCM is resampled to 16 kHz and cut into per-speaker
  segments of at most 30 s, held only in memory and zeroed after use.
- **faster-whisper** runs in `risime-whisper` on `127.0.0.1:8200`, OpenAI-compatible
  `/v1/audio/transcriptions`, read-only with tmpfs, offline.
- The transcript goes to the sealed `risi_buffer` only and is deleted after extraction, on a stop
  or on Official off.
- The learning log gets a `transcribe` row with metrics and `output: null`.

**Never.**
- Private calls (§16/§19 on `dm:`, §20 on a Private `grp:`): no option, no token, no key.
- Risi chats.
- Video or screen tracks.

## Wire summary
- **`/auth/config`:** `risi_ledger`, `risi_transcribe`.
- **Capability:** `risi_ledger` (with `risi_tools`).
- **New `risi.kind`s:** `discussion_summary`, `discussion_card`, `item_update`, `item_due`,
  `item_overdue`, `item_nudge`, `call_listen`. The `digest` gains `scope: "personal"`.
- **New `risi_action` values:** `item_confirm`, `item_decline`, `item_edit`.
- **Call envelopes:** `call_offer`/`group_call` `risi`.
- **Calls REST:** `/calls/rooms` `risi_listen`, `risi`, `risi_stop`.
- **The signal:** `call_risi`.
- **`GET /risi/commitments`** gains `summary_id`, `source`, `all_day` and `role`.
- No new error codes.
- **Examples:** the 27 files listed in §27.12.

## Work breakdown
- **Root**
  - **R1:** this section, the examples and decision 070. Done on branch `contract/v127`.
  - **R2:** `scripts/fake-llm` `discussion_summarise`; the canary interop with spoken TTS
    (Private call, stopped call, listened call); the no-audio-on-disk inotify check.
  - **R3 (infra):** `infra/whisper/Dockerfile` (an NGC arm64 CUDA base, CTranslate2 built from
    source for sm_121, faster-whisper, an OpenAI-compatible server) and
    `infra/docker-compose.whisper.yml`.
    - `127.0.0.1:8200`, `gpus: all`, `read_only`, tmpfs, `HF_HUB_OFFLINE`.
    - The model is fetched once into `~/risime-models/`.
    - Also `scripts/whisper-smoke`, and a memory re-measurement next to vLLM.
    - **No sudo.**
- **Server**
  - **S20:** `made_by` on every Risi send.
  - **S21:** the quiet job and the extraction task `discussion_summarise`.
  - **S22:** the per-person fan-out, pending copies, the owner fallback and the `discussion_card`.
  - **S23:** item actions in Risi chats, `item_update` and expiry.
  - **S24:** reminder jobs, the personal digest, and retiring the group digest.
  - **S25:** the rooms changes (`risi_listen`, `r`, +1 seat, human-only caps, `risi` in the
    replies and `status`, `risi_stop`, the old-app stop ordering) and the `call_risi` signal.
  - **S26:** Risi's internal LiveKit token, the agent call flow (join on the MLS `group_call`
    intent, keys from the NIF, rekey), the `risi-listen` Port (Rust, in `server/native/`), and
    the `call_listen` lines.
  - **S27:** transcript rows in `risi_buffer`, deletion rules, and the `transcribe` learning-log
    row.
  - **S28:** `GET /risi/commitments` fields, switches and every example.
- **Android**
  - **A13:** the made-by sheet.
  - **A14:** the `discussion_summary` card, item actions and `item_update` folding.
  - **A15:** reminder cards and buttons, the personal digest, and My promises "Owed to me".
  - **A16:** the `discussion_card` and `call_listen` lines.
  - **A17:** the start-sheet toggle, `risi_listen`, and the MLS `risi` on `call_offer` /
    `group_call`.
  - **A18:** the banner rules (roster, intent, signal), incoming and Join notices, the one-time
    explainer, [Stop Risi listening], the human-only end rule, and Risi named from MLS.
  - **A19:** creating the Risi chat at start, the `risi_ledger` capability, and examples.
- **Crypto**
  - **C10:** `call_frame_keys` exported through the Risi NIF for the agent leaf (decision 067),
    against the §20.6 vectors.

## Needs Harsha (not blocking; defaults chosen)
1. **The default of "Risi listens for follow-ups":** on in Official. Say if it should be off by
   default.
2. **Consent wording** of the one-time explainer and the banner. Check whether call-recording
   notice rules for your customers need more, such as a spoken notice.
3. **The quiet thresholds** (600 s, 6 messages, 2 people): tune them after the pilot.
4. **Sinhala/Tamil speech:** Whisper's quality there is limited, and English is the expected case.
