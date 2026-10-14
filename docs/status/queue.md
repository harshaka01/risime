# Queue

Harsha's requests, picked up after each release in the order he sets; never in parallel with a release.
Working rule from nightly.8 on: one release a day around 07:00 SLT; each item is ON for everyone or not shipped.

1. (after nightly.8) Run the gates left out of the core release (list in overnight.md, 14 Oct); fix real regressions.
2. Send a message for me: switch on for everyone once send-for-me-test passes on the release build.
3. Calendar v2: the app half (branch calendar-v2) is not on main; finish it and its gate, then switch on for everyone.
4. DB rename risime_dev → prod (Harsha's conditions: verified backup, rule-9 counts before/after, roll back on any
   mismatch, message him).
