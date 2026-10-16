# Shared test cases: RisiMe voice assistant ("Risi" on a phone call)

30 cases in three groups of 10: **intent** (I-01..I-10), **contact name** (N-01..N-10), **dictation** (D-01..D-10).
They follow the shapes of real calls to the assistant (utterances are paraphrased from spoken, messy transcripts:
self-corrections, filler words, speech-to-text slips). All names are invented; there are no phone numbers and no
real company data.

## How to read a case

- **Utterance(s):** what the caller says, in order. `(Risi: ...)` is what the assistant said in between.
- **Context:** the state before the last utterance: an open card, a pending question, and the contact list.
- **Expected:** the one tool or behaviour the assistant should choose for the LAST utterance. Pass = the right
  action; the exact wording of a reply is not scored.
- **Why:** one line.

## Tools (the action vocabulary)

| Tool | Meaning |
|---|---|
| `send_message(to, text)` | Draft/send a work message to a contact (read back, then send on "yes") |
| `read_messages(person?, chat?, since?)` | Read or summarise messages the caller received (never sends) |
| `promises_by(person)` | Promises OTHER people made to the caller (from the commitment ledger) |
| `my_promises(range?)` | Promises the caller made |
| `calendar_read(range)` | Read the calendar |
| `calendar_add(title, start, end)` | Add a calendar event (needs a confirmation) |
| `reminder(text, when)` | Set a reminder |
| `list_contacts` | Name some contacts |
| `cancel` / `edit` | Cancel or change the open card or pending task |
| `chat` | A general answer or small talk, no tool |
| `cannot_do` | "I can't do that yet, but here is what I can do" (never force the nearest tool) |
| `ask` | Ask ONE short question; never draft or act while unsure |
| `silent` | Say nothing (the caller is thinking aloud or talking to someone else) |

## Shared contact list (used by every case unless it says otherwise)

Tharindi Weeraman, Tharindu Perera, Oshan Peiris, Dilhan Kottage, Madhavi Rankoth, Anura Jayakody,
Kavindu Seneviratne, Ishara Wickrama, Lakmal Hettiarachchi, Sanduni Pathirana, Janith Gamage.
Recent chats (most recent first): Oshan Peiris, Tharindi Weeraman, Dilhan Kottage.

Now (unless stated): Thursday 15 Oct, 10:00 local. No card open, no pending question.

---

## A. Intent (which tool)

**I-01** · intent
- Utterance: "Did Oshan reply to me?"
- Context: the caller messaged Oshan Peiris earlier today.
- Expected: `read_messages(person=Oshan Peiris)`
- Why: a question about a reply is a read; a message is only drafted on a clear send request.

**I-02** · intent
- Utterance: "No, no, I'm not asking you to write any message, I'm asking you to read the messages sent by Dilhan."
- Context: Risi just asked "What should I say to Dilhan Kottage?" (pending message text).
- Expected: `read_messages(person=Dilhan Kottage)`; the pending message task is closed, and the sentence is NOT used as message text.
- Why: this is an instruction, not dictation (see group C); it must change the tool.

**I-03** · intent
- Utterance: "Any promises by Madhavi or Anura?"
- Context: none.
- Expected: `promises_by(person=Madhavi Rankoth)` and `promises_by(person=Anura Jayakody)` (two people, one answer)
- Why: promises others made to the caller come from the ledger, not the calendar.

**I-04** · intent
- Utterance: "What did I promise today?"
- Context: none.
- Expected: `my_promises(range=today)`
- Why: the caller's own promises are a ledger question; answering with calendar events is a known failure.

**I-05** · intent
- Utterance: "Check my calendar for today and tomorrow."
- Context: none.
- Expected: `calendar_read(range=today..tomorrow)` (two days, not tomorrow only)
- Why: a two-day range must not be narrowed to one day.

**I-06** · intent
- Utterance: "Can you add an appointment tomorrow, block it from ten to twelve, discuss the supplier contract." (Risi: "I haven't added it yet; tap Add.") "I don't know, from nine to ten, discuss the supplier visit."
- Context: an open calendar card for tomorrow 10:00-12:00 "Discuss the supplier contract", not yet confirmed.
- Expected: `edit` of the open card (new time 09:00-10:00, new title), not a new event and not "added".
- Why: a correction while a card is open edits the card; nothing is claimed as done before the confirmation.

**I-07** · intent
- Utterance: "Just tell me ten names in my contacts."
- Context: none.
- Expected: `list_contacts` (ten names)
- Why: a direct request for contacts is a tool call, not a capabilities list or a message.

**I-08** · intent
- Utterance: "Remind me to call Lakmal at five."
- Context: none.
- Expected: `reminder(text="call Lakmal Hettiarachchi", when=17:00 today)`
- Why: "remind me" is a reminder, not a message to Lakmal and not a calendar event.

**I-09** · intent
- Utterance: "Can you book me a taxi to the airport?"
- Context: none.
- Expected: `cannot_do` (say what it can do: calendar, messages, reminders, promises)
- Why: nothing fits a tool; the assistant must not force the nearest one (a calendar add or a message).

**I-10** · intent
- Utterance: "Let me see. I need to listen to it." (the caller is talking to someone next to them)
- Context: no card, no question pending.
- Expected: `silent`
- Why: thinking aloud is not a request; answering it (for example "I don't have access...") is a failure.

---

## B. Contact name (misheard or partial name against the contact list)

**N-01** · contact name
- Utterance: "Send a message to Tharindy saying I will be ten minutes late."
- Context: shared contact list (two close matches: Tharindi Weeraman, Tharindu Perera).
- Expected: `ask` ("Tharindi Weeraman or Tharindu Perera?"), keeping the text "I will be ten minutes late".
- Why: two close matches; ask once, do not guess, do not lose the text.

**N-02** · contact name
- Utterance: "Tell Sandune the invoice is ready."
- Context: shared contact list.
- Expected: `send_message(to=Sanduni Pathirana, text="the invoice is ready")` with no question.
- Why: one clear phonetic match; use it without asking.

**N-03** · contact name
- Utterance: "Message Ishara."
- Context: shared contact list; only one Ishara.
- Expected: `send_message(to=Ishara Wickrama)`, then ask for the text.
- Why: a first name that matches one contact is enough.

**N-04** · contact name
- Utterance: (earlier in the call: "Tell Tharindi about the schedule.") ... "Send it to Sineka."
- Context: the call so far and the recent chats point to Tharindi Weeraman. The list also has Kavindu Seneviratne.
- Expected: `send_message(to=Tharindi Weeraman)`
- Why: context (people in the call, recent chats) breaks the tie for a misheard name.

**N-05** · contact name
- Utterance: "No, Tharindi, not Tharindu."
- Context: Risi just put Tharindu Perera on an open draft card.
- Expected: `edit` the card recipient to Tharindi Weeraman; keep the text; pin her for the rest of the call.
- Why: a spoken correction fixes the recipient, not the text.

**N-06** · contact name
- Utterance: "Send it to work."
- Context: Risi is talking about Oshan Peiris; the draft has no chat chosen yet.
- Expected: `send_message(to=Oshan Peiris, chat=Official)`, not a contact called "work".
- Why: work, official, private, home, the chat and the group are never names.

**N-07** · contact name
- Utterance: "Read the messages from Dill-han."
- Context: shared contact list.
- Expected: `read_messages(person=Dilhan Kottage)`
- Why: the same phonetic matching applies to read tools, not only to sending.

**N-08** · contact name
- Utterance: "N-I-S-H-A-N." (spelling a name after Risi asked who)
- Context: Risi asked "Who should I send it to?". No contact is called Nishan; the nearest is Oshan Peiris, whom the caller messaged recently.
- Expected: `ask` ("I don't have a Nishan. Did you mean Oshan Peiris?")
- Why: a spelled name with no exact match must not be forced onto a contact silently.

**N-09** · contact name
- Utterance: "Send a message to Madhavi Rancoat saying the parcel arrived."
- Context: shared contact list.
- Expected: `send_message(to=Madhavi Rankoth, text="the parcel arrived")`
- Why: a full name with one wrong syllable still has one clear match.

**N-10** · contact name
- Utterance: "Did Janet reply?"
- Context: shared contact list (no Janet; Janith Gamage is the only close match). The caller recently messaged Janith.
- Expected: `read_messages(person=Janith Gamage)`, or `ask` ("Do you mean Janith Gamage?"); never `send_message`.
- Why: a partial, slightly wrong name with a recent-chat boost may resolve, but it stays a read.

---

## C. Dictation vs an instruction given during dictation

Rule: while Risi waits for message text, ONLY dictation becomes the text, in the caller's own words. A correction,
"no", a question to Risi, "check...", "read...", "cancel..." is an instruction and changes the plan.

**D-01** · dictation
- Utterance: "Tell her that I called her a couple of times and she did not answer, call me back."
- Context: pending message to Madhavi Rankoth; Risi asked "What should I say?".
- Expected: dictation. Text = "I called you a couple of times and you did not answer. Please call me back." Read it back and ask "Send it?"; do NOT ask the same question again.
- Why: reported speech is turned into direct speech once; a reworded text is not rejected.

**D-02** · dictation
- Utterance: "Good morning, and I'm just testing whether you can send a message on behalf of me."
- Context: pending message to Oshan Peiris, waiting for the text.
- Expected: dictation. Text = the caller's words (leading filler stripped). Read back, ask to send.
- Why: a plain sentence while waiting for text is the text.

**D-03** · dictation
- Utterance: "Cancel that."
- Context: pending message text for Dilhan Kottage; Risi asked "What should I say?".
- Expected: instruction `cancel`; drop the pending task; "Cancelled." The text is NOT "Cancel that."
- Why: "cancel" is always an instruction.

**D-04** · dictation
- Utterance: "No, no, I want to send a message to Anura. So you should ask me what the message should be and then I'll tell you."
- Context: Risi just read back a card whose text was "No no I want to send a message" to Anura Jayakody.
- Expected: instruction. Drop the wrong card, ask "What should I say to Anura?" and wait.
- Why: the caller is correcting the flow; the sentence is about the task, not its content.

**D-05** · dictation
- Utterance: "Check whether Kavindu replied."
- Context: pending message text for Kavindu Seneviratne.
- Expected: instruction `read_messages(person=Kavindu Seneviratne)`; the sentence is not the text.
- Why: "check..." is a read instruction.

**D-06** · dictation
- Utterance: "Listen to my message properly and then send it to Tharindi." (Risi: "I'm listening, go ahead.") "The meeting is moved to Friday at three, please confirm." "Okay, that's it."
- Context: dictation mode; recipient Tharindi Weeraman.
- Expected: second utterance is dictation (append to the text); third is an end-of-dictation marker: read the whole text back as one flowing line, ask "Send it?", send only after "yes".
- Why: dictation mode collects until the caller ends it; "okay, that's it" is control, not text.

**D-07** · dictation
- Utterance: "Yes, send it."
- Context: Risi read back "I'll send Oshan: 'The files are ready.' Send it?" (work chat).
- Expected: instruction: confirm and `send_message`; say "Sent." only after the server confirms.
- Why: a yes after a read-back is a confirmation, not new text.

**D-08** · dictation
- Utterance: "Can you just send it by yourself?"
- Context: an open draft card for Ishara Wickrama (work chat).
- Expected: instruction. Answer the question and read the draft back to send on yes; do NOT draft the same card again.
- Why: a question about the open card is answered, not re-drafted.

**D-09** · dictation
- Utterance: "Hello? Are you there?"
- Context: pending message text for Lakmal Hettiarachchi; Risi asked "What should I say?" and there was a pause.
- Expected: instruction/chat: "I'm here. What should I say to Lakmal?" Keep the task and the recipient; the text is NOT "Hello are you there".
- Why: a presence check keeps the pending task and neither restarts it nor becomes the text.

**D-10** · dictation
- Utterance: "Please tell her the shipment arrived, and a receipt can send me the invoice." (speech-to-text slip: the caller said "Risi can send")
- Context: pending message to Sanduni Pathirana; dictation mode.
- Expected: dictation. Text kept in the caller's words; Risi offers "Shall I fix the speech errors?" and shows both versions; nothing is changed without a yes.
- Why: obvious mis-hearings are offered as a fix, never silently rewritten.
