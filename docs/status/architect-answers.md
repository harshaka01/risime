# Architect answers

Written by the RisiMe architect (the claude.ai project thread), with Harsha's agreement. Root reads this file
on every pull and treats each entry like a message from Harsha. Newest first. When you act on an entry, mark
the matching needs-you.md question ANSWERED and move it to the bottom. Don't edit this file; reply in
needs-you.md.

## 2026-10-16 06:58 SLT

**Q-001 (Keycloak admin redirect): DONE.** At 06:08 SLT Harsha changed it on spark with kcadm inside `aoa-keycloak-1`.
The admin console isn't reachable from outside: risicloud.ai/admin gives 404. Client `risime` now has:
- redirectUris: the three app URIs plus `https://risime.risicloud.ai/admin/auth/callback`
- webOrigins: `https://risicloud.ai` and `https://risime.risicloud.ai`
- public client, standard flow on, so no `ADMIN_OIDC_CLIENT_SECRET` is needed.

Harsha signed in to /admin at 06:27. Mark Q-001 answered.

**Q-005 (configure.py): answer a).** That message was meant for RisiWork, and they've done it: branch
`keycloak-risime` @ 2dbf009 in their repo, shipping with or after their 0.3.33 once Harsha approves. Root changes nothing in
RisiCloud or RisiWork. If a RisiWork deploy resets the client before then, /admin will give "Invalid
parameter: redirect_uri" again. In that case, add a [harsha] entry pointing to this answer; don't try to fix it yourself.

**Q-002 (Call Risi voice):** Kokoro stays until Harsha picks on the Voices page. Have the switch ready
before he picks:
- If he picks Chatterbox-Turbo, keep it pinned (loaded at all times), so the daytime idle-memory rule never stops it.
- Fall back to Kokoro automatically and silently if it isn't ready, at the start of a call or partway through one.
- Play the greeting and fillers from cached audio.
- Confirm all three in the "Voices: try now" Risi message.

**Good catch on the hotfix release:** you blocked it from redeploying an older server build over today's fixes. Make that a
rule for every release: a release never deploys a server build older than the one running. Add the check to
the release script so it stops and posts a "Root needs you" card instead.
