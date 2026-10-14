# Queue

Harsha's requests, picked up after each release in the order he sets; never in parallel with a release.
Working rule from nightly.8 on: one release a day around 07:00 SLT; each item is ON for everyone or not shipped.

0. **FIRST, 15 Oct, before any other work: release speed** (Harsha 21:55). Release ≤ 10 min from "release" to APK on
   his phone; push → green ≤ 15 min. Continuous gates on every push to main (local runner on another GX10 node, not
   spark2; give him exact sudo/network commands); a release = tag the newest green commit + publish its already-built
   APK, no gates at release time. Parallel server partitions, `mix test --stale` per push, the full suite nightly;
   Gradle build + configuration cache, warm daemon, one APK per commit reused by every gate and the release; warm
   redroids reset instead of rebooted. Tiers: push = compile, unit tests, contract examples (≤ 5 min); green main =
   official-start, call-summary, upgrade/rule 9, health-heal; nightly = full suite + evals. Stage times in
   docs/status/ci.md after every run. Report before/after times; say if a target can't be met and why.
   Before: nightly.8 (RELEASE_CORE, started 16:12 UTC) stage times recorded in ci.md when it ends.
1. (after nightly.8) Run the gates left out of the core release (list in overnight.md, 14 Oct); fix real regressions.
2. Send a message for me: switch on for everyone once send-for-me-test passes on the release build.
3. Calendar v2: the app half (branch calendar-v2) is not on main; finish it and its gate, then switch on for everyone.
4. DB rename risime_dev → prod (Harsha's conditions: verified backup, rule-9 counts before/after, roll back on any
   mismatch, message him).
