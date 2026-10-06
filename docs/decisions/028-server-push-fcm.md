# 028 — Server: device registry and FCM push wake-ups (contract v1.5)

## Context
Contract v1.5 §8 and decision 026: data-only FCM wake-ups when a user has no live inbox
channel, coalesced per user. Harsha provides the Firebase service-account key later, so push
ships switched off.

## Decision
- **Devices** (`devices`): `user_id`, `device_id` (a client UUID), `platform` (`android`),
  `push_token`, `app_version`, `user_token_id` (the dev token it was registered with) and
  `last_seen_at`, unique on `(user_id, device_id)`.
  - `PUT` upserts the row and refreshes `last_seen_at`. An FCM token moves with its install: the
    same token is removed from other rows.
  - Each user has at most 10 devices; the least recently seen are evicted.
  - `DELETE` is idempotent; a bad UUID is `422 invalid_device`.
  - Dev-token logout removes that token's devices.
  - Oban's daily prune removes devices unseen for 60 days.
  - The device routes sit behind the phone gate, like the other non-exempt routes.
  - `Device`'s `Inspect` hides the token.
- **`RisiMe.Push` behaviour:** `deliver(push_token, payload)`. Implementations are `Push.FCM` and
  `Push.Test`. `:push_sender` is nil (off) unless `FCM_ENABLED=true`. The payload is always
  exactly `{"type":"inbox","v":"1"}`: no body, sender, phone or name.
- **FCM HTTP v1** (`Push.FCM`):
  - It reads the service-account JSON at `FCM_SERVICE_ACCOUNT_FILE` (default
    `~/risime-keys/fcm-service-account.json`, outside the repo, mode 600) at use, never logging
    its path or contents.
  - It signs an RS256 JWT with JOSE (scope `firebase.messaging`, `aud` = `token_uri`) and
    exchanges it at `https://oauth2.googleapis.com/token`.
  - The access token is cached in the `Push.FCM` process until 5 min before expiry, and dropped
    on a 401.
  - The send is `POST …/v1/projects/{project_id}/messages:send` with `data`, `android.priority:
    high`, `collapse_key: inbox` and `ttl: 3600s`.
  - Results:
    - `UNREGISTERED` or `INVALID_ARGUMENT` → the device is deleted;
    - 5xx, 429, 401 or a transport error → retryable;
    - anything else → failed.
  - Req runs with no retries of its own and no logging steps. Logs carry the HTTP status and
    FCM's error code only.
- **Trigger and coalescing:** an in-memory debounce, `Push.Dispatcher`, not Oban.
  - `Messaging.publish/2` calls `Push.notify/1` for every stored inbox event (`message` and
    `status`). It does nothing when push is off or `Presence.online?/1` is true (a live inbox
    channel, including the 5 s grace).
  - Otherwise the user's first push goes out now, and further events within 10 s produce one
    trailing push at the end of the window, if the user is still offline.
  - Sends run in `Push.TaskSupervisor`, so FCM latency never touches message sends: one attempt,
    plus one retry after 1 s if retryable.
  - Why not Oban: a push is a best-effort wake-up. Losing one in a restart costs nothing, because
    the app syncs when opened. A debounce needs no Postgres writes per message, and the 10 s
    window is per node, matching Presence.

## Consequences
- Push stays off until Harsha supplies the key and sets `FCM_ENABLED=true`. Until then devices
  register, and nothing is sent.
- Coalescing and presence are per node; clustering will need both shared.
- `INVALID_ARGUMENT` also covers payload bugs. Because the payload is a constant checked by the
  contract test, a payload bug that deletes every device is guarded against.
