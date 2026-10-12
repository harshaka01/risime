# Proposal 2026-10-12: fix `blob.size` in the §37 voice examples (android)

**From:** android (building §37). **For:** root. **Type:** example fix only, no wire change.

## Problem
The five `voice_payload*.json` examples (and the `payload` of `backup_entry_message_v141.json`) carry
`"plain_size": 49152` with `"size": 49248`. The §14.3 `cipher_size` of a 49 152-byte plaintext is
**49 168** (Padmé(49 152) = 49 152, one segment, + 16-byte tag). §37.2 requires the §14.4 checks, one of
which is "`cipher_size` computed from `plain_size` equals `blob.size`", so a strict receiver drops every one
of these examples as malformed — including `voice_payload.json`, which is meant to be valid.

## Proposed fix
Set `"size": 49168` in `voice_payload.json`, `voice_payload_reply_no_transcribe.json`,
`voice_payload_forwarded.json`, `voice_payload_risi_chat.json`, `voice_payload_bad_waveform.json` and the
`payload.blob` of `backup_entry_message_v141.json` (the bad-waveform example then fails only on its waveform).

## Meanwhile (android)
`ContractExamplesTest` checks each voice example twice: as shipped (strictly dropped, or decoded once fixed)
and with the size corrected (decoded field by field). Nothing else depends on the wrong value.
