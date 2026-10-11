# Flaky / non-blocking test steps

CLAUDE.md rule 11: a test-harness problem gets at most 2 attempts or 90 minutes, then the step becomes non-blocking,
is listed here, and the release goes out. Product-safety gates are never listed here (never skipped).

| Since | Step | Why non-blocking | Status / fix plan |
|---|---|---|---|
| nightly.48 (2026-10-10) | `ui-entry-test --ui-batch` (UI batch at font scale 1.0/1.3, chips, Markdown, calendar add/read on redroid) | Harness problems on 2026-10-10 attempts i–n: scrolling into long chats, the Official tab left open by earlier steps, calendars left by the --calendar step, app restart timing after `pm revoke`, the question scrolled away when a card arrives. Each was a script fault, not a product bug. | Runs once per release on its own redroids (`UITEST_UIBATCH_ONLY=1`, instance _relb); every step's PASS/FAIL goes into `docs/releases/v<ver>.md`. Make it blocking again after 3 clean releases in a row. |
| E gate (2026-10-11) | `ui-entry-test --messaging` (copy format, star/info/reply, forward + "Forwarded" label, Sinhala/Tamil PDF via pdftotext and pdffonts) | New end-to-end script (step 19), not yet proven over several releases; it is non-blocking from the start. The JVM tests and the Android unit gate stay blocking. | Runs once per release on its own redroids (`UITEST_MESSAGING_ONLY=1`, instance _relm, ports 4555/8394, redroids -relm1/-relm2 5869/5870); every step's PASS/FAIL/SKIP goes into `docs/releases/v<ver>.md`. Make it blocking after 3 clean releases in a row. |
