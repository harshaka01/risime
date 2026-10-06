# 042 — Encrypted images (contract v1.11)

**Status:** proposed 2026-10-06 (root). The proposal is
`contract/proposals/2026-10-06-images-v1.11.md`; it becomes accepted when the crypto, server and
android reviews are merged into PROTOCOL.

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
- **Single-request upload, idempotent by `client_blob_id`, not resumable.** After the 2048 px
  re-encode a photo is typically 0.3–2 MiB; a retried single request is the simplest thing that
  works on mobile. Downloads support a single `Range` for resume. Chunked upload waits for video.
- **Limits:** 16 MiB per image, **2 GiB live per user** (`413 quota_exceeded`), 120 uploads per
  hour and 1000 per day, 3 concurrent uploads, `507 storage_full` when `BLOB_DIR` has under 10 GiB
  free. The quota is a backstop (about 1500 photos a month), not a product limit.
- **Thumbnail inline, bounded:** at most 128 px and 4096 bytes, inside an envelope of at most
  22 KiB, leaving 2 KiB for MLS framing under the 24 KiB ciphertext cap. A caption long enough to
  break that bound drops the thumbnail. The thumbnail is protected by MLS only, like the caption.
- **Digest of the ciphertext only.** AES-GCM authenticates the plaintext; a plaintext hash would
  add a cross-chat fingerprint and no integrity.
- **Removed group members keep read access to blobs uploaded while they were members**, for the
  blob's TTL. They already received those messages and keys; refusing the bytes would only break
  images they hadn't downloaded yet, and the ciphertext is useless without a key. Members removed
  before an upload can't read it. This needs a membership-interval history on the server.
- **Group icons don't expire while current**; a replaced icon is deleted after 7 days.
- **An `images` device capability** gives clients an `images_ready` hint (DM attach is disabled
  until ready); the server can't enforce it because it can't see message types.

## Consequences
- The server streams large bodies to disk, keeps membership intervals, enforces quotas and runs
  the expiry sweep for `media` and superseded `icon` blobs. Caddy's body limit must allow 16 MiB.
- Android adds the re-encode pipeline, an upload job, a ciphertext-only cache with
  decrypt-on-display, a full-screen viewer and save to gallery.
- Blob backups grow with use; an incremental blob backup is likely needed (open point).
