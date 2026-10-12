# 081 — Voice notes: an E2EE blob like a photo, a `played` receipt, Risi transcription in Official only

**Status:** accepted 2026-10-12 (NEXT-PHASE §A; Harsha's requirements, root's details). PROTOCOL §37 (v1.41).

## Decision
- **Envelope `voice`** inside MLS, built on the §14 image shapes: `blob` + `enc` (`A256GCM-S64K`, fresh key per
  upload, `purpose=media`), `mime` `audio/ogg; codecs=opus` (AAC `audio/mp4` receive-only), `duration_ms`
  (≤ 30 min), `waveform` = 64 one-byte levels. No nonce field: the key is single-use, as for photos.
  Blobs are never reused across conversations; forwarding re-uploads per target.
- **`played` is a third `msg:ack` status** (`delivered` < `read` < `played`), forwarded only to `voice` devices
  so older apps never see an unknown status. Groups get `played` / `all_played` counts. The mic turns blue.
- **Risi transcription only in Official.** Risi is already a visible MLS member there, so it decrypts the note
  like any member; the audio is held only in memory/tmpfs and deleted after transcription (§36 no-storage
  rules), with the §36.5 per-language local models and the §36.11 script check. Private notes have no Risi
  path at all. The transcript (`voice_transcript`) is visible to every member, collapsed under the bubble.
- **Opt-out twice:** `transcribe: false` on the note (the phone stamps it from the setting) and the server-side
  setting `/me/risi/voice`; either wins. Forwarded notes are never transcribed (the speaker didn't consent to
  this chat). Notes over 5 min are not transcribed.
- **Risi chat:** a voice note is a request; it is transcribed and answered as text in the user's language.
- **Backups by reference** (like images); audio inside backups is left for a later backup schema.

## Why
WhatsApp parity without weakening E2EE: the only party that ever decrypts audio outside the members' phones
is Risi, which is itself a member of Official and is shown as such. Reusing the photo blob format means no new
crypto and no new server storage.

## Consequences
- Android: recorder/player, `voice_ready`, the unplayed dot and blue mic, strict validation.
- Server: `played` ordering and delivery filter, group receipt fields, the Risi audio path (no disk), the
  setting, `RISI_VOICE_TRANSCRIBE` switch (default off).
