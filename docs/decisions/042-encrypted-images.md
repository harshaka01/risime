# 042 — Encrypted images (contract v1.11)

**Status:** accepted 2026-10-06 (root), after the crypto, server and android reviews. Contract:
PROTOCOL §14 (v1.11); proposal `contract/proposals/2026-10-06-images-v1.11.md`.

## Context
Images are the next slice after groups (decision 041). Harsha fixed the product rules: re-encode
to 2048 px with metadata stripped, AES-256-GCM with a fresh key per image, blobs on spark2 outside
the repo with a 16 MB cap, a per-user quota, a 30-day TTL and rate limits, keys and an encrypted
thumbnail inside the MLS message, DMs and groups, group icons reusing the same mechanism. Several
choices weren't fixed and aren't obvious from the contract text.

## Decision
- **Plaintext chats refuse images** (`409 not_e2ee`), with no plaintext fallback. A fallback
  would put photos on the server readable by it, would need a second path that dies once E2EE is
  everywhere, and would work against removing the dev banner. Every pilot chat is e2ee after the
  v1.7 rollout.
- **One store.** Images are a `media` purpose of the v1.9 blob API, and group icons an `icon`
  purpose, not a second store (as planned in 041).
- **Blob format `A256GCM-S64K`** (crypto review): 64 KiB AES-256-GCM segments under an
  HKDF-derived payload key, STREAM nonces with a final flag, Padmé padding, no header; keys made
  only inside the Rust core. Chosen now, not single-shot GCM, because icons and cached blobs live
  on and video will reuse it; it also gives bounded memory and verifiable resumes. Vectors in
  `contract/v1/media_vectors.json`, byte-identical from the core and `scripts/gen-media-vectors`.
- **Single-request upload, streamed to disk, idempotent by `client_blob_id`, not resumable.** After the 2048 px
  re-encode a photo is typically 0.3–2 MiB; a retried single request is the simplest thing that
  works on mobile. Downloads support a single `Range` for resume. Chunked upload waits for video.
- **Limits:** 16 MiB of ciphertext per image, **2 GiB live per user** (`413 quota_exceeded`,
  introduced for `mls` in v1.10 at 256 MiB), 120 uploads per hour and 1000 per day, 3 concurrent
  uploads, icons 3 per hour. The quota is a backstop (about 1500 photos a month), not a product
  limit.
- **Free-space guard (server configuration, not protocol).** spark2's one NVMe also holds
  Postgres, Cassandra and backups. Defaults: `media` → `507 storage_full` under
  **max(50 GiB, 10 %)** free or over a global live `media` cap `BLOB_MEDIA_MAX` = **200 GiB**;
  `mls`/`icon` keep working down to **20 GiB**; a warning and `/health` detail at **100 GiB**.
  Free space is polled every 30 s, minus in-flight upload bytes.
- **Thumbnail inline, bounded:** at most 128 px and 4096 bytes, inside an envelope of at most
  22 KiB, leaving 2 KiB for MLS framing under the 24 KiB ciphertext cap. A caption long enough to
  break that bound drops the thumbnail. The thumbnail is protected by MLS only, like the caption.
- **Digest of the ciphertext only.** AES-GCM authenticates the plaintext; a plaintext hash would
  add a cross-chat fingerprint and no integrity.
- **Removed group members keep read access to blobs uploaded while they were members**, for the
  blob's TTL. They already received those messages and keys; refusing the bytes would only break
  images they hadn't downloaded yet, and the ciphertext is useless without a key. Members removed
  before an upload can't read it. This needs a membership-interval history on the server.
- **Group icons don't expire while current**; a replaced icon is deleted after 7 days. Only
  current members read icons; a removed member loses access at once (housekeeping, not a
  cryptographic guarantee).
- **The owner always reads its own `media` blob** for the TTL; DM participants always; group
  readers by membership interval (Postgres `group_member_intervals`).
- **An `images` device capability** gives clients an `images_ready` hint (DM attach is disabled
  until ready); the server can't enforce it because it can't see message types.

## Conflicts between the reviews (the safer option chosen)
- **Format:** crypto's segmented `A256GCM-S64K` in the Rust core over android's single-shot
  `javax.crypto` GCM. `enc.nonce` is removed; android seals `key` and `plain_size`.
- **Decode bounds:** crypto's 2048 px per side (128 px for thumbnails) plus a match with the
  envelope `w`/`h`, over android's 4096 px / 16 MP.
- **Save to gallery:** re-encode on save (crypto R5's option), over android's "write the
  decrypted bytes", so a receiver never relies on a sender's metadata stripping.
- **`mls` byte cap:** 256 MiB (v1.10 server review R5) over 512 MiB (v1.11 server review R9).
- **Free-space guard:** server-configured with the server review's defaults, over a fixed 10 GiB.
- **Icon upload rate:** 3/h (server S4) over 10/h.
- **Reused `client_blob_id` that doesn't match** (purpose, conversation or `Content-Length`):
  `400 bad_request` (server R1), not a silent `200` with the old blob and not a new
  `409 blob_id_conflict` code (android S3).
- **Icon readers:** current members only (proposal, crypto answer 7) over "owner or members"
  (server R3 table).

## Deferred
- MLS application-message padding (crypto S1), key commitment for abuse reporting (S2),
  progressive display for video (S8).
- Device removal revoking that device's session token (crypto S5), for server and root.
- Caddy `request_body` limits and dropping `:multipart` (server S1), `scripts/blob-purge` (S3),
  dropping the query from blob access logs (S5).
- **Root chunk before `media` ships:** incremental blob backups (`rsync --link-dest`) in
  `scripts/backup` (server R8), since encrypted blobs don't compress and full tarballs at
  `--keep 7` could fill the disk at the quota worst case.

## Consequences
- The server streams large bodies to disk, keeps membership intervals, enforces quotas and runs
  the expiry sweep for `media` and superseded `icon` blobs. Caddy has no body limit today, so
  16 MiB already passes; a limit added later must allow it.
- The crypto core gains a `media` module exposed through UniFFI.
- Android adds the re-encode pipeline, an upload job, a ciphertext-only cache with sealed keys and
  decrypt-on-display, a full-screen viewer and save to gallery (Room v6).
