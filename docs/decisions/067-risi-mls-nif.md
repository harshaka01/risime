# 067 — Risi's MLS client runs as an optional Rustler NIF with a sealed journal store

**Status:** accepted 2026-10-08 (root). Commits `fe71d6c` (crypto) and `027c3f4` (server loader).

## Decision
- **Crate and loader:**
  - `crypto/risime-mls-nif` is a Rust crate on `rustler = "0.38.0"`. It works on OTP 28 /
    Elixir 1.19.
  - There is no Elixir `:rustler` dependency: `RisiMe.Agent.Mls.Nif` loads
    `priv/native/librisime_mls_nif.so` (or `$RISI_MLS_NIF`) with `:erlang.load_nif` from `@on_load`.
- **Optional:** without the library the module still compiles and `loaded?/0` is false. The server
  gate never needs Rust, and the NIF tests skip themselves.
- **Builds:**
  - `scripts/build-mls-nif` builds with the `test-peer` feature for tests;
  - `--prod` leaves the test NIFs out;
  - the output goes to `server/priv/native/` (gitignored).
  - `scripts/nightly-release` will call `--prod` once the server's Risi tree (S5) uses the NIF.
- **Journal store:** every call returns `{:ok, result, journal}`. Elixir writes the journal to
  `risi_mls_kv` in one transaction before acting on the result. If the write fails, it drops the
  handle and reopens.
- **Sealing, in Rust:**
  - AES-256-GCM under `RISI_MLS_KEK` (32 bytes, `.env`), with a random 96-bit nonce;
  - AAD = `"risi-kv-v1" ‖ u16be(len device_id) ‖ device_id ‖ key`;
  - a tampered or moved row fails the load. The KEK is never logged or echoed.
- **Defence in depth (§24.0):** the NIF refuses to join, encrypt, process or self-update in any
  `dm:` or Private group (`:private_tab`), and rolls back a refused join.

## Consequences
- The spark2 release build needs cargo (present in `~/.cargo`).
- A separate agent process remains the later hardening step named in §24.11.
