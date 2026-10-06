# 050 — Log out keeps the chats on the phone; deleting is a separate, labelled action

- **Status:** accepted (2026-10-06)
- **Decided by:** Harsha: "Log out keeps the chats encrypted on the phone, and they reappear when
  the same account signs back in (wipe only if a different account signs in, after asking). Add a
  separate, clearly labelled 'Log out and delete chats from this phone'."
- **Supersedes:** the logout part of decision 014 and the nightly.11 "Settings → Log out deletes
  chats" rule.

## Decision
- **Log out** (Settings and the chats menu):
  - Revokes the tokens (RisiCloud refresh token, end-session best effort) and unregisters push for
    this phone, so a logged-out phone shows no notifications.
  - **Keeps** the local database (encrypted at rest, its key in the Android Keystore) **and the
    phone's MLS state and device id.**
  - The device stays registered as a member of its 1:1 and group MLS groups, and the server keeps
    queueing its inbox (30-day TTL).
  - Signing back in as the **same account** resumes at once: chats, group content and new messages
    are back with no rejoin and no history gap.
- **Log out and delete chats from this phone** (a separate, clearly labelled action, with
  confirmation):
  - Today's full wipe: local DB, MLS state, and `DELETE /me/devices`, so the device leaves its
    groups.
  - A later sign-in is a new device: groups are rejoined automatically, and earlier group history
    comes back only through history sharing (decision 049) or backup.
- **A different account signs in:** "Chats from <previous> are on this phone. Signing in as <new>
  deletes them. Continue?" Cancel keeps them. Continue wipes as above.

## Consequences
- A logged-out phone still holds decryptable chats, protected by the device lock and the Keystore.
  The delete option exists for handing a phone over.
- Server: a logged-out device is not removed from groups. Its inbox keeps filling within the TTL.
  Push to it stops, because push tokens are unregistered on logout.
