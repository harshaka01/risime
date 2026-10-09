# Proposal: §23.5 screen sharing: no FLAG_SECURE on normal screens; a pre-share dialog; the viewer fit

**Status:** requested by Harsha (2026-10-09, real-phone feedback). Implemented on android (see
docs/status/android.md, "Real-phone fixes"). Root folds it into §23.5 and updates decision 062.
Client-only behaviour: no wire change.

## Replace in §23.5 "Privacy rules"
Old: "**RisiMe's own windows are `FLAG_SECURE` while sharing** (chats and the call screen show black
to viewers: no mirror loop, other chats never leak)."

New:
- **Only the secure screens are `FLAG_SECURE`** (always, sharing or not): the app-lock screen, the
  Locked chats folder, and a locked chat while it is open (with its chat/group info). Normal
  screens (the chat list, other chats, the call screen) are never `FLAG_SECURE`: an "Entire screen"
  share shows RisiMe to the viewer, as WhatsApp does. Below API 33 the app lock's Recents fallback
  (`FLAG_SECURE` on every screen) is not applied while the phone shares its screen.
- **Before every share** (all Android versions), the app asks: "Share your screen? — Your whole
  screen, including notifications, will be visible." [Start] [Cancel]. On Android ≤ 14 it also
  offers the Do Not Disturb link (the old once-per-share warning is folded into this dialog).
- Message notifications stay silent and content-free while sharing (unchanged).

## Add to "The viewer"
- The shared screen is shown **at the sender's orientation and aspect ratio** (the frame's width and
  height after its rotation; a rotation on either phone re-fits), letterboxed, never cropped: the
  viewer sees exactly what the sender sees. (Already normative "fitted, never cropped"; the android
  renderer did not do it — its view filled the stage and the renderer cropped to the view.)
