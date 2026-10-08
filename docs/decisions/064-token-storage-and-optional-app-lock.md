# 064 — No mandatory lock: tokens readable in the background; WhatsApp-style optional fingerprint lock

**Status:** accepted 2026-10-08 (Harsha's correction to item 2, sent twice after the trade-off was
explained). Replaces the token storage and lock part of decision 014; AppAuth/PKCE, logout and the
error UI stay as they are.

## Context
- Decision 014 wraps the refresh token with a Keystore key that requires BIOMETRIC_STRONG or
  DEVICE_CREDENTIAL on every use. That causes three problems:
  - **every process start needs a fingerprint.** A process started by a push after Android killed
    RisiMe in the background has no bearer, so it can't sync, fetch a call or connect. This is the
    real-phone cause of the P0 "messages don't show and calls don't ring until the app is opened".
    The Redroid push gate signs in with dev OTP, so it couldn't see this;
  - **phones without biometrics or a screen lock** can't create the key, so tokens are kept in
    memory only and every process restart forces the email sign-in;
  - **a new fingerprint enrolment** invalidates the key, which forces a sign-in.
- Harsha: no lock by default, signed in until an explicit Log out, an optional fingerprint lock,
  exactly as in WhatsApp.

## Decision
- **Token storage:** the token set (refresh + ID token) is encrypted with AES-256-GCM under a
  hardware-backed Android Keystore key:
  - StrongBox when available, else TEE;
  - non-exportable;
  - **no user-authentication requirement** and not invalidated by enrolment;
  - the background (push, workers) can always get a bearer.
  Access tokens stay in memory only. Nothing is stored in plaintext.
- **Migration** (rule 9: never wipe chats):
  - an existing auth-bound vault is opened with one last fingerprint prompt at the next normal app
    open, re-encrypted under the new key, and the old key is deleted;
  - an install without a vault signs in once more and then stays signed in;
  - a cancelled or failed migration keeps the old vault and asks again at the next open.
- **App lock (a UI gate only):**
  - it never gates tokens, the socket, push, sync or calls;
  - Settings → Privacy → "Fingerprint lock", **off by default**;
  - offered only when `BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == SUCCESS`;
  - turning it on confirms with one fingerprint;
  - "Automatically lock": Immediately / After 1 minute / After 30 minutes;
  - "Show content in notifications": on/off. When off and locked, notifications show "New message"
    with no name or text;
  - the locked screen shows the RisiMe logo and "Unlock with fingerprint";
  - incoming calls ring and can be answered while locked;
  - if the fingerprint is removed later (canAuthenticate ≠ SUCCESS at start or resume), the lock
    turns itself off;
  - the lock never signs out, never wipes and never needs the network.
- **Sessions:** the user stays signed in until an explicit Log out. A real `invalid_grant` from
  Keycloak still leads to a sign-in with chats kept. Every sign-out records its trigger in a log
  line and on the health screen.

## Consequences
- **What's protected and what isn't:** the refresh token can't be extracted from the phone
  (hardware key), but someone holding the unlocked phone can open RisiMe unless the user turns the
  lock on. This is the same model as WhatsApp.
- **The release gate:** must cover an OIDC-style session that survives process death and is used
  by a push-started process with no UI.
