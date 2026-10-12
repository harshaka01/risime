# Proposal 2026-10-13: §38 action items, gaps against Harsha's 2026-10-13 plan

From the android role while building v1.42 §38 on the phone. Nothing here is implemented on the wire; the app stays
within v1.42 and these are the gaps it found. Root decides; android and server follow.

## 1. "Never guess names — ask": an item with no owner has no question
§38.3: an owner that can't be resolved among the members is `owner: null`, shown as "Unassigned", with no confirm card.
Nobody is asked. Harsha's rule is to *ask*.
**Proposal:** an Official-card action `item_claim` (`risi_action` in the claimer's own Risi chat, `target` = `item_id`,
`edit: null`), allowed for any active member on an unowned `promise`/`task`/`meeting` before `expires_at`; the server
sets `owner`, posts the claimer an `action_confirm` for that one item and an `item_update` (`owner`). The phone would
show "Is this yours? [It's mine]" under an Unassigned item. Alternatively Risi asks in the Official chat via the §34.5
`clarify` shape (one question, member chips).

## 2. "Never guess dates — ask": an undated promise is confirmed without a date
`action_confirm` has no "when?" step; §27 server item 10's `item_clarify` exists for vague ledger items.
**Proposal:** state that a confirmed §38 item with `due: null` (or only a vague `due_text`) gets the `item_clarify`
question in the owner's Risi chat, as other ledger items do (no new kind).

## 3. Add to calendar for all-day and undated items
§38.4 offers `add_calendar` only for a `meeting` or an item with a non-all-day future `due`, yet also says "with no
usable time the phone opens the picker first and sends `edit`". With the button rule as written the picker case only
happens for an undated meeting. Harsha's plan says confirmed items land on the calendar.
**Proposal:** offer `add_calendar` for every ledger item; without a usable time the phone asks for one (picker) and
sends `edit` `{start, end, all_day}` — the app already does this (never a guessed time).

## 4. The other members' Official card never learns the owner's answer for a voice note
§38.4 posts `item_update` to "every participant of the source"; a voice note's only participant is its sender, so the
other members' Official card shows `waiting` forever (seen on two phones: the owner's card says "confirmed ✓", the
other phone's "waiting"). For a call both speakers get it.
**Proposal:** send the `item_update` for §38 items to every active member of the Official conversation (their Risi
chat), or let the server re-post a compact `action_items_update` `{summary_id, item_id, state}` into the Official
conversation (silent, `notify: []`).

## 5. "Remind me" depends on the Reminders skill
`remind` is offered only while the Reminders skill is `ask`/`allowed` (§26.3). Fine, but the plan lists Remind me as
always present; a phone with the skill off never sees it. **Proposal:** none on the wire; a note in §38.4 that the
button is missing with the skill off (the app could add "Turn on Reminders" later).

## 6. Wording of the unclear card
The plan says "I couldn't hear this clearly". The server's `body` reads "Risi couldn't hear this call clearly, so it
made no action items."; the app renders its own text from `reason` ("…couldn't hear any speech…" for `no_speech`).
**Proposal:** none on the wire; root may want one canonical string.
