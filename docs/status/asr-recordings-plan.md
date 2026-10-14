# ASR real-recordings plan, round 2 (root, 2026-10-14)

Intelligence plan item 2, audit gap G5: "Transcription is scored on real speech, not public sets."
Today **no real recording exists**, so every ASR number is a public-set fallback. Round 1
(`docs/status/asr-eval.md`, 62 short Risi requests) still stands; this round adds **work-chat
sentences** (deliveries, invoices, leave, client calls), much more **Singlish**, and **voice notes**
whose action items are scored end to end.

## What this is, and what it is not
- **Eval-only recordings with explicit consent.** They are used for one thing: measuring how well
  RisiMe transcribes real speech (WER/CER, script %, and the action items found in a voice note).
- **Not training data.** Harsha's decision on collecting voice data for training (fine-tuning the
  Sinhala ASR, `asr-sinhala-finetune-plan-2026-10-14.md`) is **still pending**. Nothing in this plan
  collects or authorises training data. A recording made under this plan may **never** be used to
  train or fine-tune any model, even after that decision, unless the speaker gives a new, separate
  consent for training. Mixing the two would also make the eval worthless (a model scored on its own
  training clips).
- **Nobody (including Claude) collects audio automatically.** A teammate records on their own
  phone, by hand, after agreeing.
- **Synthetic text only.** Every sentence below is written for this purpose: fictional names
  (Nadeesha, Ruwan, Priya, Arun...), invented companies, invented amounts. Nobody reads a real chat
  or a real customer's details aloud.

## Consent (before the first clip)
Each speaker agrees in writing (a message to Harsha is enough) to this text, in their language:

> I agree to record the sentences on the list for RisiMe's internal speech-recognition **testing**
> only. The recordings stay on the RisiMe test server, are not shared outside the team, are not put in
> any app or code repository, and are **not used to train any model**. I can ask for them to be
> deleted at any time, and they will be deleted.

Harsha records, per speaker, in `~/risime-eval-data/real/speakers.txt` (no names needed):
`<letter> | <gender> <age band> <home area> | consent <date> eval-only | via <who>`,
e.g. `C | female 20s Galle | consent 2026-10-15 eval-only | via Harsha`.
A withdrawal: delete that speaker's files (`rm real/*/NN-<letter>.*` and their `16k/` copies), add
`withdrawn <date>` to their line, and rerun `scripts/asr-eval-data.py`.

## How a teammate records (phone)
1. Open any voice-recorder app (the phone's own recorder is fine). **Not** a chat app: a voice note
   sent through WhatsApp, RisiMe or similar leaves copies on other servers (and a RisiMe voice note
   would enter the transcription pipeline and learning log).
2. Hold the phone like a call, in a normal room. Some office noise is fine; no car with the window open.
3. One clip per numbered line: start, wait half a second, say the line **the way you would really
   say it** (normal speed, not dictation), stop. Numbers as words. If you would say it differently,
   say it your way and mark the line (Harsha edits `sentences.txt`, which is the reference).
4. Name each file `NN-<letter>.<ext>` (NN = the line number, two digits; the letter from
   `speakers.txt`), e.g. `si+en/12-C.m4a`, `notes-si+en/03-C.m4a`. Any phone format works
   (m4a, ogg, mp3, amr, wav); the eval converts to 16 kHz mono itself.
5. Copy them **straight to spark2** over the LAN or the tunnel (`scp *.m4a spark2:risime-eval-data/real/si+en/`)
   or with a USB cable to the laptop and then `scp`; then delete them from the phone and the laptop.
   No cloud drive, no email, no chat app.
6. Tell root "round-2 recordings are in".

Time per speaker: about 25 minutes for all the short lines, 10 minutes for the voice notes.
Ideal: **3 speakers** (at least one woman, at least one Tamil speaker for `ta`/`ta+en`), each reading
every line of their languages once.

## Where they go (outside git, sealed on spark2)
- `~/risime-eval-data/real/<folder>/` on spark2 only: `si`, `ta`, `en`, `si+en`, `ta+en` (sentences)
  and `notes-si`, `notes-si+en`, `notes-ta`, `notes-en` (voice notes). The folder is made `chmod 700`
  (owner `harsha`; today it is 775) by root before the first clip lands. It is **outside every git repo**, never copied to the laptop mirror, never in an
  APK (`scripts/check-apk-testdata` fails a build that contains test audio), and never sent to a
  commercial API (the eval talks only to the speech service on 127.0.0.1:8200).
- Each folder gets a marker file `CONSENT-SCOPE` containing `eval-only`. Rule for any future
  training-data script (for when Harsha's decision lands): **refuse any directory that has a
  `CONSENT-SCOPE: eval-only` marker.**
- Reference transcripts: `sentences.txt` in each folder (line NN = clip NN). Copies of the new lines
  are below; the round-1 lines stay as they are, the round-2 lines are **appended** (numbering
  continues).
- Backups: `scripts/backup` does not include `~/risime-eval-data`; if it ever does, only in sealed form.

## How they plug into the eval
1. `python3 -I scripts/asr-eval-data.py` converts new clips to `real/<folder>/16k/*.wav` and builds
   `~/risime-eval-data/manifests/all.jsonl`. Sentence clips are set `real` (as round 1); voice notes are
   set `real-notes` (wired on 2026-10-14: folders `notes-<lang>`, ids `real-notes-<lang>-NN-<letter>`).
   Real code-switched clips replace the synthetic code-switch fallback for that language automatically.
2. WER/CER per model and route:
   `scripts/asr-eval run --manifest ~/risime-eval-data/manifests/all.jsonl --models auto --sets real,real-notes --out R.jsonl`
   then `scripts/asr-eval score --manifest ... --results R.jsonl --markdown --by kind`. The language-hint
   runs (`--hint si --hint-mode prior`, Tamil `--hint ta`) on `si+en`/`ta+en` are the deciding ones.
3. Voice notes end to end (transcript -> action items): the ASR text of each `notes-*` clip goes through
   the same `extract_actions` prompt as `eval/sets/actions/` (as a one-speaker `voice_note` case whose
   `lines` are the ASR output, gold = the items listed under each script below), so the action-item
   score on real audio can be compared with the score on the typed transcript. (Add these as cases with
   ids `ac-<lang>-9NN` and `"audio": "real-notes-..."` once the clips exist; the gold is already here.)
4. The Index: per decision 085 the transcription Index uses these real sets, not the public fallback,
   as soon as each language has 20+ clips from 2+ speakers.

## Round-2 sentences (append to `sentences.txt`; numbering continues)

### `si/` (Sinhala), lines 26-40
26. සිකුරාදා හවස තුනට ලෝටස්බේ එකේ client meeting එක තියෙනවා.
27. මම හෙට උදේ දහයට කලින් invoice එක එවන්නම්.
28. නදීෂා ලබන සතියේ අඟහරුවාදා නිවාඩු.
29. delivery එක බදාදා වෙනකොට කරන්න බැරි වෙයි වගේ.
30. රුවන්, ඒ quotation එකේ මුදල රුපියල් හැටපන්දාහක් නේද?
31. අද රෑ අටට කලින් අලුත් build එක දාන්නම්.
32. client කිව්වා payment එක මාසෙ අන්තිමට කරනවා කියලා.
33. මට හෙට office එන්න වෙන්නේ නෑ, ගෙදර ඉඳන් වැඩ කරන්නම්.
34. ඒ server එක ආයෙත් down, කවුරුහරි බලන්න පුළුවන්ද?
35. interview එක බ්‍රහස්පතින්දා උදේ එකොළහට දාමු.
36. stock එක එන්නේ ලබන සඳුදා, ඊට පස්සේ තමයි ship කරන්න පුළුවන්.
37. මම ඊයේ ඒක යැව්වා, ඔයාට ලැබුණේ නැද්ද?
38. bill එකේ VAT එක දාලා නෑ, ආපහු හදන්න ඕනේ.
39. මගේ laptop එක හදන්න දීලා, දවස් දෙකක් යයි.
40. ඔක්කොම හරි නම් අපි හෙට launch කරමු.

### `ta/` (Tamil), lines 16-27
16. வெள்ளிக்கிழமை மதியம் மூன்று மணிக்கு client meeting இருக்கு.
17. நாளைக்கு காலை பத்து மணிக்குள்ள invoice அனுப்புறன்.
18. பிரியா அடுத்த செவ்வாய்க்கிழமை லீவு.
19. புதன்கிழமைக்குள்ள delivery செய்ய ஏலாது போல இருக்கு.
20. அருண், அந்த quotation அறுபத்தைந்தாயிரம் ரூபா தானே?
21. இன்றைக்கு இரவு எட்டு மணிக்கு முதல் புது build போடுறன்.
22. client சொன்னவர் மாசக் கடைசியில payment செய்வினம் என்று.
23. நாளைக்கு office வர ஏலாது, வீட்டில இருந்து வேலை செய்யிறன்.
24. server திரும்பவும் down, யாராவது பார்க்க முடியுமா?
25. interview வியாழக்கிழமை காலை பதினொரு மணிக்கு வைப்போம்.
26. நான் நேற்றே அதை அனுப்பிட்டன், உங்களுக்கு கிடைக்கேல்லையா?
27. bill-ல VAT போடேல்ல, திரும்ப செய்ய வேணும்.

### `en/` (English), lines 11-20
11. The client meeting at Lotusbay is on Friday at three.
12. I'll send the invoice before ten tomorrow morning.
13. Priya is on leave next Tuesday.
14. I don't think we can make the delivery by Wednesday.
15. Ruwan, the quotation was sixty five thousand rupees, right?
16. I'll push the new build before eight tonight.
17. The client said they'll pay at the end of the month.
18. The server is down again, can someone take a look?
19. Let's put the interview on Thursday at eleven.
20. VAT is missing on the bill, we have to redo it.

### `si+en/` (Singlish, Sinhala base), lines 7-26
Reference: Sinhala in Sinhala letters, English words in English letters, as people speak.
7. මචං client ට quotation එක send කරා ද? අද evening වෙද්දි ඕනේ.
8. ok මම tomorrow morning ම invoice එක email කරන්නම්.
9. meeting එක Friday three o'clock ට confirm කරා, Lotusbay එකේ.
10. delivery එක Wednesday වෙද්දි possible නෑ, stock එක තාම ආවේ නෑ.
11. ඒ bug එක fix කරලා අද night deploy කරන්නම්.
12. Nadeesha leave Tuesday, so ඒ presentation එක Ruwan කරයි.
13. payment එක tomorrow කරන්නම් කියලා client කිව්වා.
14. aiyo server එක ආයෙත් down, someone please check කරන්න.
15. interview panel එකට මාවත් add කරන්න, Thursday free.
16. VAT එක miss වෙලා, bill එක re-issue කරන්න වෙනවා.
17. sixty five thousand ද seventy ද final price එක?
18. ok no problem, මම report එක Monday morning ට ready කරන්නම්.
19. laptop එක repair එකට දුන්නා, two days යයි.
20. team lunch එක Saturday ද Sunday ද?
21. client call එකේදී අපි discount එක ගැන කිව්වේ නෑ නේද?
22. මම already ඒක forward කරා, inbox එක check කරන්න.
23. hosting renewal එක end of the month ට due.
24. deadline එක extend කරන්න පුළුවන්ද කියලා අහලා බලන්නම්.
25. sample එක courier එකෙන් send කරා, tracking number එක evening එවන්නම්.
26. if they approve today, අපි tomorrow ම start කරමු.

### `ta+en/` (Tamil with English), lines 7-14
7. machan client-க்கு quotation send பண்ணீங்களா? இன்னைக்கு evening-க்குள்ள வேணும்.
8. ok நான் tomorrow morning invoice email பண்ணுறன்.
9. meeting Friday three o'clock confirm, Lotusbay-ல.
10. delivery Wednesday-க்குள்ள possible இல்ல, stock இன்னும் வரல.
11. அந்த bug fix பண்ணி இன்னைக்கு night deploy பண்ணுறன்.
12. Priya Tuesday leave, so presentation-ஐ Arun செய்வார்.
13. hosting renewal end of the month-க்கு due.
14. sample courier-ல send பண்ணிட்டன், tracking number evening அனுப்புறன்.

## Round-2 voice notes (`notes-<lang>/`, one clip per number, 20-40 s each)
Read as if leaving a voice note for the team, with natural pauses and fillers. The reference is
the text as written; the gold action items are what Risi must find (chat clock for the eval:
Sunday 2026-10-11, so "tomorrow" = 2026-10-12, "Friday" = 2026-10-16). Speaker = the recorder
(owner "me" below = the speaker; in the eval case the speaker is the member who sent the note).

### `notes-si+en/`
1. හායි team, ඉතින් Lotusbay meeting එක Friday three ට confirm කරා. මම tomorrow morning quotation
   එක update කරලා client ට send කරන්නම්. Ruwan, ඔයා invoice එකට VAT එක add කරලා Tuesday වෙද්දි
   ready කරන්න. ඒ ගැන තව මොකුත් නෑ.
   *Gold:* meeting Fri 10-16 (with the client); promise me: update/send the quotation, due 10-12;
   task Ruwan: add VAT to the invoice, due 10-13.
2. ok so අද client call එකේදී කිව්වේ delivery එක next week කියලා, හැබැයි date එකක් කිව්වේ නෑ.
   මම stock එක ගැන supplier ගෙන් අහලා Wednesday ට කලින් කියන්නම්.
   *Gold:* promise me: ask the supplier about stock, due 10-14; no date for the delivery (must not invent one).
3. ඉතින් මේ ගැන මතක් කරන්න. server එක ඊයේ රෑ down වුණා, දැන් හරි. මම ඒකට report එකක් ලියන්නම්,
   හැබැයි අද නෙමෙයි, Monday.
   *Gold:* promise me: write the outage report, due 10-12; "server was down" is past, not a task.
4. hello, Nadeesha Tuesday leave. ඒ නිසා Tuesday interview එක Thursday eleven ට මාරු කරමු.
   මම candidate ට message කරන්නම්.
   *Gold:* meeting: interview Thu 10-15; promise me: message the candidate, no due.
5. bill එකේ amount එක sixty five thousand. client seventy කියලා හිතන් ඉන්නේ, ඒක wrong.
   කවුරුහරි ඒක clarify කරන්න ඕනේ.
   *Gold:* task (no owner): clarify the amount with the client, no due; number 65,000. Must not make
   anyone the owner.
6. machan just fyi, මම tomorrow office එන්නේ නෑ, work from home. urgent එකක් තිබ්බොත් call කරන්න.
   *Gold:* no action item (an absence notice; "call me if urgent" is not a task).

### `notes-si/`
1. සුබ උදෑසනක්. හෙට උදේ දහයට අපේ team meeting එක තියෙනවා. මම agenda එක අද හවස එවන්නම්.
   ලහිරු, ඔයා ගිය මාසේ sales වාර්තාව ගේන්න.
   *Gold:* meeting Mon 10-12; promise me: send the agenda, due 10-11; task Lahiru: bring last month's sales report, due 10-12.
2. ඉතින් ඊයේ delivery එක හරියට ගියා. client සතුටුයි. වෙන කරන්න දෙයක් නෑ.
   *Gold:* no action item (past, positive news).
3. බදාදා වෙනකොට invoice ඔක්කොම යවන්න ඕනේ. මම ඒවා check කරලා දෙන්නම්, ඉෂාර ඔයා යවන්න.
   *Gold:* promise me: check the invoices, due 10-14; task Ishara: send the invoices, due 10-14.
4. හෙට මට doctor ළඟට යන්න තියෙන නිසා දවල් දොළහට එන්නම්. ඊට කලින් මොනවා හරි ඕනේ නම් චමරගෙන් අහන්න.
   *Gold:* no owned task (arrival time notice); optional: none.

### `notes-ta/`
1. வணக்கம், நாளைக்கு காலை பத்து மணிக்கு team meeting. நான் agenda இன்றைக்கு மாலை அனுப்புறன்.
   அருண், போன மாச sales report கொண்டு வாங்க.
   *Gold:* meeting Mon 10-12; promise me: send the agenda, due 10-11; task Arun: bring the sales report, due 10-12.
2. நேற்று delivery சரியா போச்சு, client சந்தோசம். வேற ஒன்றும் செய்ய வேண்டியதில்லை.
   *Gold:* no action item.
3. புதன்கிழமைக்குள்ள எல்லா invoice-உம் அனுப்ப வேணும். நான் check பண்ணித் தாறன், கவிதா நீங்க அனுப்புங்க.
   *Gold:* promise me: check the invoices, due 10-14; task Kavitha: send the invoices, due 10-14.

### `notes-en/`
1. Hi all, quick one. The Meridian Tiles delivery slipped, they'll tell us a new date. I'll call
   their store manager tomorrow morning and update this group. Gayan, please hold the invoice till then.
   *Gold:* promise me: call the store manager, due 10-12; task Gayan: hold the invoice, no due; no
   delivery date (must not invent one).
2. So the demo went well yesterday. Nothing pending from my side, just sharing.
   *Gold:* no action item.
3. Reminder: hosting renewal is due on the twentieth. Amaya, can you renew it by Friday? Budget is
   twelve thousand.
   *Gold:* task Amaya: renew the hosting, due 10-16; number 12,000.

## Status
- 2026-10-14: plan written; `scripts/asr-eval-data.py` reads `notes-<lang>/` (set `real-notes`).
  **No audio collected.** Waiting for: speakers' consent, then the recordings (Harsha arranges).
