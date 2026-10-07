# Proposal: superseded registered devices never block readiness (+ optional reasons)

From: server + android (nightly.16 tester findings), 2026-10-07. Needs root to fold into
PROTOCOL.md §10.2, §12.1, §14.1, §15.1 and §16.1.

## Finding (pilot, read-only counts)
- Every nightly.16 install in the pilot has a **new device id**; the user's previous install
  (nightly.11/12) stays registered (devices are pruned only after 60 days unseen). Its census
  instance was last seen 1–2 minutes before the new device was first registered and never again.
- §12.1/§14.1 count "a registered device seen in the last 30 days" as an install that can still
  receive, so that dead install (no `images`, `deletes`, `calls`) keeps `images_ready` and
  `deletes_ready` false for 30 days, and the apps say "<name> needs to update the app to receive
  photos" although the person runs the current version. The photo attach button stays disabled in
  every DM, which is why no photo was ever uploaded (0 `/blobs` requests, 0 blob rows).

## 1. Normative change (implemented on the server, no wire change)
Add to the "can still receive" rule of §12.1 (and by reference §10.2, §14.1, §15.1, §16.1):

> A registered device is **superseded** when it was last seen (census or `PUT`) before another
> device of the same user was first registered. A superseded device can't receive and never
> blocks readiness and is never listed in `missing*`. It counts again as soon as it is seen again
> (a second phone still in use).

This is the existing rule for device-less instances, applied to registered devices. The risk is
the same one the contract already accepts: a second phone that is offline when a new install
registers doesn't block until it is seen again.

## 2. Client (implemented on Android, no wire change)
- A device re-advertises its capabilities (`PUT /me/devices/{id}`) whenever they change, not only
  at sign-in (`calls` depends on POST_NOTIFICATIONS, which a fresh install grants after its first
  registration).
- No "needs to update" wording when the client can't know the version: a listed `device_id`
  means "<name>'s phone can't receive photos yet" / "can't take calls yet"; a member with no MLS
  device: "<name>'s phone hasn't registered encryption keys yet"; `device_id: null` with MLS
  devices present (a pre-v1.7 app still in use) keeps "needs to update".

## 3. Optional, for a later contract version (not implemented)
- `missing_images[]`, `missing_deletes[]`, `missing_calls[]` entries gain
  `"app_version": string | null` (the census version) and `"reason": "old_app" | "no_mls" |
  "not_advertised"`, so clients can say exactly "<name> needs to update" only for an old version,
  and "notifications are off on <name>'s phone" for a current one without `calls`.
- §12.4: a `devices` op whose affected user has no online in-group device and whose admins are
  all offline waits indefinitely (nightly.16: two groups wait for an admin). Consider letting any
  in-group member device commit a `devices` op for an existing member (needs crypto review), or a
  push wake to the candidate admin devices.
