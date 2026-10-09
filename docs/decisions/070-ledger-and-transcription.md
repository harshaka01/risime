# 070 — Model transparency, per-person Ledger follow-ups, and Risi listening to Official calls

**Status:** accepted 2026-10-09 (requirements: Harsha; details: root). Proposal
`contract/proposals/2026-10-09-ledger-followups-transcription.md`, which becomes PROTOCOL §27
(v1.27). Implements decision 066's stage 3 (transcription) for calls.

## Decision
- **`made_by` on every Risi envelope** (`{model, provider, at, also}`), taken from the learning-log
  row of the message's `call_ref`:
  - local models are named by alias (`risi-l1`) with provider `risime`;
  - commercial ones by provider display name and model id;
  - `model: null` means RisiMe's rules made it (reminders, updates, `call_listen`);
  - `also` names other models the content rests on (the speech model of a call summary).
  - The phone shows "Made by: RisiMe model (risi-l1) · 09:14" on a tap of the Risi name or ⓘ.
  - **Why:** Harsha's transparency rule, and the cascade (decision 066) moves tasks between models,
    so users must always see which one answered.
- **The quiet rule** (normative numbers, server config with these defaults):
  - the newest human message is ≥ 600 s old;
  - ≥ 6 human messages from ≥ 2 humans since the last summary;
  - summaries ≥ 30 min apart and ≤ 8 per conversation per day;
  - a 4-h long-discussion rule (trigger at the next 3-min gap);
  - a listened call triggers at its end (≥ 60 s of speech from ≥ 2 humans).
  - **A discussion with no extracted item posts nothing.** Key points alone don't justify a message
    in everyone's Risi chat; @Risi summarise stays available.
- **Per-person follow-ups replace the in-group proposed commitment card.**
  - Each participant (and each item owner) gets `discussion_summary` in **their own Risi chat**,
    with one shared `summary_id`.
  - **Only the owner confirms, edits or declines their item.** A counterpart can only mark it done.
  - The Official conversation gets one short `discussion_card`, with no items, owners or dues and
    the line "Details in your Risi chat".
  - **Why:** a proposed card in the group puts a person's promise in front of everyone before they
    agreed to it. The Risi chat is the one place that is the person's own, and Harsha's flow is per
    person.
  - **Fallback:** an owner with no `risi_ledger` device still gets the v1.24 in-group card, so
    mixed groups keep working. A recipient whose Risi chat isn't created yet gets their copy held
    for up to 24 h.
- **Reminders go to the person concerned**, in their Risi chat:
  - the owner: before (due − 1 h; for a date-only due, 09:00 the day before) and at the due;
  - counterparts: "…is due today", only for items the owner confirmed;
  - overdue: one gentle follow-up to the owner (due + 2 h), then one nudge to the counterparts
    24 h later. Each happens at most once per item in its life.
  - A personal 09:00 digest replaces the group digest where everyone has the ledger.
- **Transcription: Official calls only, the starter's choice, everyone sees it, anyone stops it.**
  - **Starting:** the start sheet's "Risi listens for follow-ups" is on by default in Official. The
    phone remembers the last choice per chat. The choice can't be turned on mid-call.
  - **Risi joins as a visible LiveKit participant** under its MLS leaf identity
    (`<risi_user>/<risi_device>`):
    - its token is minted only inside the server, with subscribe-only grants (no publish, not
      hidden, not a recorder);
    - it subscribes only to microphone tracks;
    - it derives frame keys from its own MLS membership (§20.6; crypto C10 through the Risi NIF).

    E2EE between members is intact, because Risi is a member. LiveKit and coturn still see
    ciphertext only.
  - **The "Risi is listening" banner shows** if Risi's identity is in the room (authoritative), or
    the MLS `call_offer`/`group_call` intent says `listen` (for the first 30 s), or the
    `call_risi` signal says so.

    It shows on the incoming-call and Join screens before joining, and from the first frame of
    the call screen. The banner fails toward showing.
  - **Old apps: refuse transcription, never block the call.**
    - A device without `risi_ledger` asking to `join` makes the server stop Risi first (reason
      `old_app`), then get its token.
    - Transcription doesn't resume in that call.
    - **Why:** blocking a work call because of an app version is worse than losing a summary. The
      server ordering guarantees an old app never shares a room with a listening Risi.
  - **Stop Risi listening:** any participant can stop it, with one tap, at any time
    (`risi_stop`). Risi leaves and **deletes everything it heard in that call** (no summary of the
    call). There is no restart in the same call.
    - **Why:** a stop that keeps what was already heard would not be what people expect from
      "stop".
- **The audio path:**
  - `risi-listen` is a Rust program (LiveKit Rust SDK) run by `RisiMe.Agent` as an Erlang Port,
    so keys and audio never touch a file or a socket path.
  - libwebrtc decrypts and decodes Opus. PCM is resampled to 16 kHz in memory and cut per speaker
    at 0.8 s pauses or 30 s, so at most 30 s per speaker is held, and it is zeroed after use.
  - Speaker attribution comes from the LiveKit track identity, so no diarisation model is needed.
  - **faster-whisper** (`faster-whisper-large-v3`, CTranslate2 CUDA) runs in the container
    `risime-whisper` on `127.0.0.1:8200`. It serves an OpenAI-compatible
    `/v1/audio/transcriptions`, is read-only with a tmpfs `/tmp`, runs offline and logs no text.
    The speaker side speaks the OpenAI API, so vLLM's Whisper serving is a drop-in fallback if the
    CTranslate2 CUDA build for GB10 fails. That fallback would be a decision amendment, not a
    contract change.
  - The transcript goes only to the sealed `risi_buffer` (TTL 24 h) and is **deleted after the
    call's extraction**, on a stop, or on Official off.
  - The learning log gets a `transcribe` row with metrics only (`output: null`).
- **Limits:** at most 4 listened calls at once (`RISI_LISTEN_MAX`, GPU). Transcription runs only
  while ≥ 2 humans are connected. Risi takes a +1 seat and human-only caps.
- **Switches:** `RISI_LEDGER` and `RISI_TRANSCRIBE` (both default `off`) in `/auth/config`, plus
  the capability `risi_ledger`.

## Consequences
- **Server:** the quiet job, the per-person fan-out, item actions in Risi chats, reminder jobs,
  `made_by` on every send, the rooms changes, Risi's token, the Port to `risi-listen`, and the
  transcript buffer rows.
- **Android:** the made-by sheet, the per-person card, reminder buttons, the call-screen banner
  rules, the start-sheet toggle, and the stop.
- **Crypto:** the Risi NIF exports `call_frame_keys` for the agent leaf.
- **Infra (root):**
  - `infra/docker-compose.whisper.yml` and `infra/whisper/Dockerfile`: an arm64 CUDA base from
    NGC, with CTranslate2 built from source with CUDA for sm_121, faster-whisper and a small
    OpenAI-compatible server;
  - `127.0.0.1:8200` only, `gpus: all`, model files in `~/risime-models/faster-whisper-large-v3`;
  - a smoke script.
  - **No sudo:** Docker and the NVIDIA runtime already serve vLLM.
  - LiveKit needs no change: the listener runs on the same host and reaches the media ports on
    `10.20.20.15`.
- **The memory budget** must be re-measured next to vLLM's 0.34 share (whisper large-v3 int8_fp16
  is about 3–4 GB).
- **Sinhala and Tamil speech quality of Whisper is limited.** English is the expected case;
  summaries are made from whatever the transcript gives, and items still need the owner's ✓.
