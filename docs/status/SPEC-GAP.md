# SPEC-GAP: vision vs what is live on phones

As of 2026-10-10. Live server `0.2.0-nightly.47` (`/health` ok), contract v1.30, latest published APK
`~/risime-releases/v0.2.0-nightly.47/`. Pilot switches (`infra/pilot/pilot.env`, `/auth/config`):
**on** TABS, RISI, RISI_TOOLS, RISI_SKILLS, RISI_EVENTS, FCM, OPEN_SIGNUP, TURN_URLS; **off** RISI_LEDGER,
RISI_TRANSCRIBE, RISI_NOTES, LIVEKIT_URL, PHONE_VERIFICATION (SMS log mode). "Live" = in the published
APK and its switch on. Status words: live / behind flag / code only / not started.

## Top 10 gaps
1. Commitment Ledger / My promises is built but off (RISI_LEDGER); nobody can use the product's core promise-tracking until Harsha says "turn the ledger on".
2. Risi Notes (and meeting-note follow-ups) is built but off; it needs the ledger on first.
3. Group calls do not work: LiveKit is installed but `LIVEKIT_URL` is unset (needs the Caddy `/livekit` route, Harsha's sudo step), so rooms answer 503.
4. Google Calendar is not connected: no Google code is in the app yet and the Google Cloud setup (docs/GOOGLE-CALENDAR-SETUP.md) is waiting on Harsha; Risi answers "Am I free" from the Risi Calendar only.
5. Call transcription (section 27) is off and has no phone-side use yet (RISI_TRANSCRIBE off).
6. Calls across networks are unproven on real phones: TURN is configured and ports were opened 2026-10-07, but no real-phone cross-network check is recorded.
7. History sharing to a new phone shipped (nightly.20, option A, approve once per phone), but older group messages after "delete chats" depend on it being exercised; full-automatic option B is undecided.
8. No on-device agent, no digital twin: nothing runs on the phone that learns the user's behaviour (planned 0.7/0.8, not started).
9. Own-model cascade is only partly real: a self-hosted L1 (`risi-l1`) exists behind the router, but there is no L2, no confidence-based task-by-task handover, and no measured quality gate for it.
10. Phone SMS verification, MCP/A2A partner agents and the Risi ERP / Git verification connectors are not live (sender ID pending; MCP registry and connectors not started), so identity is Keycloak/dev OTP only.

## Messaging, E2EE and MLS
| Feature | Status | Next step |
|---|---|---|
| 1:1 chat, store-and-forward, ticks, presence, typing | live | none |
| Push (FCM, content-free wake-ups) and watchdog | live | real-phone check on locked/doze devices |
| 1:1 E2EE with MLS, per-chat lock / reason (dec. 048) | live | monitor chats stuck as "not E2EE yet" |
| Emoji and reactions (section 11) | live | none |
| Delete for me / for everyone, clear and delete chat (section 15) | live | none |
| Reinstall history and encrypted backups (sections 13, 22) | live | run the restore drill on a real phone |
| History sharing between devices (section 17, dec. 049 option A) | live | Harsha: keep A or choose fully automatic B |
| Two tabs Private / Official (section 24) | live | none |
| Invites and friends (section 9) | live | none |
| Open sign-up (section 21) | live | close when the pilot widens |
| SMS phone verification (section 7) | behind flag | Notify.lk sender ID approval, then PHONE_VERIFICATION on |
| RisiCloud (Keycloak) sign-in | live | Keycloak post-logout redirect URIs (optional) |
| iOS client | not started | later (Kotlin Multiplatform) |

## Groups
| Feature | Status | Next step |
|---|---|---|
| MLS groups (section 12) with auto rejoin after logout | live | none |
| Group ticks, delivery state | live | none |
| Profile photos (section 18) | live | real-phone check |
| Older group messages after "delete chats" | live | only via history sharing; exercise it |

## Photos and media
| Feature | Status | Next step |
|---|---|---|
| Encrypted images with metadata stripping (section 14) | live | none |
| Voice notes | not started | schedule after Ledger turn-on |
| Files / documents, video messages | not started | decide scope, then contract proposal |

## Calls and group calls
| Feature | Status | Next step |
|---|---|---|
| 1:1 voice calls, ringing when locked (section 16) | live | same-network proven; confirm cross-network on mobile data |
| 1:1 video, mid-call switch, screen share (sections 19, 23) | live | real-phone check |
| TURN relay (coturn, TURN_URLS set) | live | verify from outside with two phones on different networks |
| Calls option A (caller name while locked, dec. 051) | not started | Harsha decides; B is shipped |
| Group calls via LiveKit (section 20) | code only | Harsha: Caddy `/livekit` sudo step; then set LIVEKIT_URL |

## Risi agent, tools and skills
| Feature | Status | Next step |
|---|---|---|
| Risi as a visible member, Official tab only (section 24, stage 1) | live | none |
| Direct chat with Risi | live | none |
| Risi tools and the action loop (sections 25, 28) | live | none |
| Risi skills with switches (section 26) | live | add skills as needed |
| Risi events / proactive offers (RISI_EVENTS) | live | watch the per-chat daily cap |
| Model transparency (section 27 part) | behind flag | goes with the ledger turn-on |
| Risi inside E2EE with MLS NIF (dec. 067) | live | none |
| Partner agents over A2A/MCP (Lia, eDrop) | not started | spec proposal (decision 066 stage 2) |

## Commitment Ledger / My promises
| Feature | Status | Next step |
|---|---|---|
| Commitment detection and confirm / edit | behind flag | RISI_LEDGER on |
| Follow-ups, quiet-rule summaries, per-person copies, reminders | behind flag | RISI_LEDGER on, then watch for noise |
| My promises screen and daily digest (section 28) | behind flag | RISI_LEDGER on |
| Verification connectors (Git, Risi ERP, calendar) | not started | after ledger is in use |
| Projects view built from commitments (0.8) | not started | after ledger data exists |

## Risi Calendar
| Feature | Status | Next step |
|---|---|---|
| Calendar tab: agenda, day, week, month (section 29) | live | none |
| Risi-made events with Add / Edit / Cancel card; invite cards | live | none |
| Reminders and digest | live | none |
| Honest "Am I free" (names what was checked) | live | none |

## Risi Notes
| Feature | Status | Next step |
|---|---|---|
| Notes list, note screen, tick-boxes (section 30) | behind flag | RISI_NOTES on (needs ledger on) |
| Meeting notes to events and promises | behind flag | same |

## Google Calendar
| Feature | Status | Next step |
|---|---|---|
| Authorization API read of busy blocks (dec. 072) | not started | Harsha: Cloud Console steps in docs/GOOGLE-CALENDAR-SETUP.md; then android build |
| Phone CalendarContract fallback | live | unreliable (Risi wrongly said "clear"); honesty rule covers it |
| Write events to Google | not started | after read works |

## Transcription (section 27)
| Feature | Status | Next step |
|---|---|---|
| Server transcription path with Whisper (dec. 071) | behind flag | RISI_TRANSCRIBE on after ledger |
| Call transcription UX and consent on phones | code only | real-phone test once the flag is on |

## On-device agent
| Feature | Status | Next step |
|---|---|---|
| Gemini Nano / llama.cpp / ExecuTorch client agent | not started | decision note, pick runtime (0.7) |
| Learning from the behaviour log, on device | not started | define the behaviour log first |

## Digital twin
| Feature | Status | Next step |
|---|---|---|
| Twin as a linked device (opt-in), draft mode, autonomy levels | not started | after the on-device agent (0.7/0.8) |

## Own-model cascade (L1/L2)
| Feature | Status | Next step |
|---|---|---|
| Model router (commercial or own by route) | live | add confidence-based routing |
| Self-hosted L1 `risi-l1` (dec. 061) | live | measure quality against the commercial model |
| L2 model and task-by-task handover | not started | define tasks and a pass bar |

## Learning log
| Feature | Status | Next step |
|---|---|---|
| Cassandra learning log of model calls (ids and hashes, sealed outputs) | live | add cost field and review queue |
| User feedback rows | live | feed into L1 evaluation |

## MCP registry and pgvector memory
| Feature | Status | Next step |
|---|---|---|
| pgvector fact memory (`risi_fact_embeddings`) | live | check recall quality |
| Internal tool registry for Risi tools (section 25) | live | none |
| External MCP tool registry / A2A marketplace | not started | spec proposal with partner agents |

## Ops and release
| Feature | Status | Next step |
|---|---|---|
| Nightly release with both gates, Redroid upgrade gate, backup, rollback | live | none |
| Updates never delete chats (rule 9, dec. 055) | live | keep the per-conversation count gate |
| Pilot behind Caddy + systemd, `scripts/risi-flag` guarded switches | live | none |
| Oban background jobs, metrics, JSON logs, load tests | live | none |
| fail2ban jail, Tailscale removal | code only | Harsha's sudo steps (docs/PROD.md) |
| RisiWork `version.json` / updater field names | code only | Harsha supplies the example |
| Separate prod environment (pilot still uses DB `risime_dev`) | not started | split DB names before wider rollout |
