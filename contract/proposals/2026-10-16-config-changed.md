# Proposal 2026-10-16: `config_changed` signal (phones refetch server flags at once)

Architect 2026-10-16 14:15 SLT. `RISI_VOICE_TRANSLATE` was turned `on` at 14:06, but Regina's phone
kept its cached `translate_available: false` until Settings → Risi was opened. The app fix (next APK)
refetches every server-driven capability at app start, on every return to the foreground and on every
socket (re)join. That still leaves a phone that is open and connected stale until one of those happens.
This proposal lets the server say "your configuration changed" so the phone refetches at once.

**The app must work without it**: the event only makes the refetch earlier. A client that never gets
it (an old server, a dropped socket) behaves exactly as today.

## Wire change (additive)

A new kind of the ephemeral **`signal`** push on `inbox:<own user id>` (§2.3): not stored, no
`event_id`, never moves the cursor, not replayed on join. Old clients ignore unknown signal kinds
(§2.3), so no capability is needed.

```json
{"kind": "config_changed", "data": {"scope": ["auth_config", "risi_voice", "me"]}}
```

- `scope`: optional list of what changed, as hints. Values: `auth_config` (`GET /api/v1/auth/config`),
  `risi_voice` (`GET /api/v1/risi/voice`, §37.7), `me` (`GET /api/v1/me`). Absent or empty means
  "refetch everything". A client may always refetch everything; unknown values are ignored.
- **No flag names or values in the signal.** The client reads the values over REST, as today, so the
  signal carries nothing a client could act on by itself.

## When the server sends it

- `RisiMe.Agents.Flags.put/3` and `clear/1` for a flag that drives a client-visible field: to every
  user the change affects (`on` / `off`: every connected user; `canary`: the dev users), debounced to
  at most one signal per user per 5 s.
- A per-user change of a field in `GET /me` (`call_risi`, `private_transcribe`, `risi_dev`) or in
  `/risi/voice` made by someone other than this device (an admin, another device of the user).
- Connected sockets only: no push wake-up, no inbox event. A phone that is offline refetches on its
  next start / foreground / rejoin anyway.

## Client behaviour (Android, after root applies this)

On `config_changed`: `AppContainer.refreshServerConfig("config_changed", force = true)` (the one
refresh path added for 14:15; it already runs on start, ON_START and every rejoin). No UI.

## Example

`contract/v1/examples/signal_config_changed.json`:

```json
{"kind": "config_changed", "data": {"scope": ["risi_voice"]}}
```

## Tests

- Server: a `Flags.put` sends one `config_changed` to each connected affected user (debounced);
  `canary` reaches only dev users; nothing is stored or replayed.
- Android: parsing the example; the signal triggers one forced refresh; an unknown `scope` value is
  ignored.
