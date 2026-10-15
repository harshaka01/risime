# Proposal 2026-10-15: meeting titles from the phone during a Risi call (§41.8)

**Status:** applied as **v1.52** (§41.8, 2026-10-15; v1.51 stays reserved for the Private transcribe
proposal). Additive. With the capability absent, every call is byte-identical to v1.50.

**Why (Harsha, 15 Oct 12:45 SLT, P0 RISI INTELLIGENCE item 3):** "During a call, the PHONE is present. Fetch event
titles from the phone over the call's E2EE data channel for the voice answer only (not stored on the server), or
speak only what the user allowed and say so. Never answer 'I can't see your calendar' when permission is granted."

Today the voice answer can use only what the §25 calendar client tool returns. Under §44.5 that is times only
until the user says "Yes" to titles, so Risi can say "10 to 11 is busy" but not "a call with Shakya".

## 41.8 Meeting titles over the call's data channel

### Capability
The device capability **`"call_risi_cal"`** in `mls.capabilities` is valid only together with `call_risi`.
The phone advertises it once it implements this section. Without it, the server never asks, and a voice
answer speaks times only. Once per call, it adds: "I can see the times; the titles stay on your phone."

### Tokens
In a `k: "risi"` room only, **both** tokens get `canPublishData: true`:
- Risi's token: `livekit_token_claims_call_risi_v152.json`;
- the caller's token, as §20.2 with that one change.

No other room changes.

### Request (Risi → phone)
The packet is a reliable LiveKit data packet with topic **`"risi.cal"`**. Its destination is the caller's
identity only.

`call_risi_cal_request.json`:
```json
{"t": "cal_req", "id": "<uuid-v4>", "from": "2026-10-15T00:00:00+05:30", "to": "2026-10-17T00:00:00+05:30"}
```
`from`/`to` are the range the voice turn needs: RFC 3339, at most 8 days apart.

### Reply (phone → Risi)
The phone answers within **2 s**, with the same topic, to `risi_identity` only.

`call_risi_cal_reply.json`:
```json
{"t": "cal", "id": "<same>", "ok": true,
 "events": [{"start": "2026-10-15T10:00:00+05:30", "end": "2026-10-15T10:30:00+05:30", "all_day": false,
             "title": "Call with Shakya", "busy": true}]}
```
On a failure, `call_risi_cal_reply_error.json`:
```json
{"t": "cal", "id": "<same>", "ok": false, "reason": "no_permission"}
```

Fields and limits:
- `reason`: `no_permission` | `no_calendars` | `off` (the user's setting below) | `error`.
- `events`: at most 100, ordered by `start`.
- `title`: at most 200 chars, or `null` for a private or untitled event.
- The phone reads only the calendars the §29 client tool may read.

The phone must:
- answer only while the Risi call screen is up and the request came from `risi_identity`;
- ignore any other topic or sender;
- never send anything on `risi.cal` without a request.

### The user's control
The setting **"Risi can say my meeting titles on calls"** is on by default, under Settings → Risi and in the
first-call sheet (§41.4).
- **Off:** the phone replies `ok: false, reason: "off"`, and Risi says times only and says so once.
- The §44.5 "Only times / Yes" choice is unchanged. It still governs what the server may store and what the
  Risi chat card shows.

**Root decision, recorded:** default on. The user is asking aloud for their own calendar on a call with Risi,
and the titles are used for one spoken answer and then dropped. Harsha can switch the default to off.

### Server (normative)
- The titles live only in the call session's memory, for the one answer being composed. They are dropped after it.
- They are never written to the database, Cassandra, the learning log (it records `titles_n` only), logs,
  telemetry or the `answer` card.
- In the `risi_call_transcript` line of that answer, each title is replaced with the time-only wording, for
  example "10:00–10:30 busy", and the line gets **`"titles_spoken": true`**. The phone shows a small note:
  "Meeting titles were said aloud, not saved."
- A missing reply within 2 s → speak times only, and say so once per call.
- Never say "I can't see your calendar" when the §25 read succeeded.

### Honesty (decision 048)
LiveKit data travels with DTLS between the phone and RisiMe's SFU, then over loopback to Risi's worker: the
same path and the same honest label as the call audio (§41.0, "Not end-to-end encrypted: processed by Risi on
RisiMe's server").

### Examples
- `device_put_call_risi_cal.json`
- `livekit_token_claims_call_risi_v152.json`
- `call_risi_cal_request.json`
- `call_risi_cal_reply.json`
- `call_risi_cal_reply_error.json`
- `envelope_risi_call_risi_transcript_titles.json`

### Gate
- **Server:**
  - the request is sent only with the capability;
  - after the 2-s timeout, Risi speaks times only;
  - titles are never persisted: the learning-log row, the transcript line and the card are checked;
  - the transcript line carries `titles_spoken`;
  - parity without the capability.
- **Android (JVM):**
  - decoders for every new example;
  - a reply only to `risi_identity`, only on request, only while the call screen is up;
  - the setting set to off gives `reason: "off"`;
  - at most 100 events.
