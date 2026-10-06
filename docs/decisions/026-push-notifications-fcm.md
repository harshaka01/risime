# 026 — Push notifications: FCM, data-only wake-ups, content stays off Google

**Status:** accepted 2026-10-06 (autopilot slice C). Contract v1.5 §8.

## Decision
- **Transport:** Firebase Cloud Messaging.
  - The server uses the **FCM HTTP v1** API with a service-account key: a JWT signed with JOSE,
    exchanged at Google's token endpoint, with the access token cached until shortly before it
    expires.
  - Android uses `firebase-messaging`.
  - Firebase config (`google-services.json`) and the service-account key are **Harsha's
    credentials**. They are never committed:
    - the app reads `android/app/google-services.json`, which is gitignored and copied in at
      build time from `~/risime-keys/`;
    - the server reads `FCM_SERVICE_ACCOUNT_FILE`, a path outside the repo
      (`~/risime-keys/fcm-service-account.json`, mode 600).
- **No content in pushes.** The payload is `{"type":"inbox","v":"1"}` only. The app syncs over its
  channel and builds the notification locally (sender name and preview from its own DB). Google
  never sees bodies, names or phones, and this stays true after E2EE.
- **When:** only when the recipient has no live inbox channel. Coalesced to one per user every
  10 s; collapse key `inbox`; TTL 1 h; high priority.
- **Off until credentials exist:** `FCM_ENABLED=false` by default.
  - Without `google-services.json`, the Gradle `google-services` plugin isn't applied, and the
    app builds and runs without push (no Firebase init).
  - Device registration still works on the server, so tokens are stored the moment push turns
    on.
- **Android 13+:** the app asks for `POST_NOTIFICATIONS` once, after sign-in, with a short
  explanation. If it's denied, there's no push and chat still works.
