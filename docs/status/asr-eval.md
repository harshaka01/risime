# ASR eval: recordings request and how to run (root, 2026-10-12)

Harsha's real call on 2026-10-12: English good, Sinhala poor. The earlier Sinhala numbers
(whisper-small-sinhala 14.5% WER in docs/RISI-MODELS.md) come from a YouTube test set that may
overlap its training data, so they are **not trusted**. This page asks for in-house recordings and
explains the eval that scores them. Results: docs/RISI-MODELS.md ("ASR eval 2026-10-12").

**No pilot clips exist:** the server keeps no call audio (decision 066: audio is transcribed in
memory and dropped), so today's pilot calls can't be replayed. Only the transcripts' quality was
seen, by Harsha, live.

## Status (2026-10-12 ~20:00 UTC)
- Held-out public sets scored (omni-si, yt5-si, slr30-si, fleurs-ta, fleurs-en, synthetic
  code-switch): tables in docs/RISI-MODELS.md. Production now routes `si` to
  Qwen3-ASR-0.6B-Sinhala (decision 071 amendment c); ta/en unchanged.
- (Done 2026-10-13, see below.) Not finished then (memory floor, MemAvailable 1.7–7 GB): kasunw/whisper-large-v3-sinhala
  on the held-out sets (11 yt-si clips only). Rerun: start an eval instance and
  `scripts/asr-eval run --manifest ~/risime-eval-data/manifests/all.jsonl --langs si,si+en
  --models whisper-large-v3-sinhala --out ~/risime-eval-data/results/si-4.jsonl`.

## Status (2026-10-13 Sri Lanka, 2026-10-12 ~22:10 UTC; Harsha PART 1/2)
- Routing check: per-chunk language/probability/model logging is live in the speech service
  (asr5). Today's pilot calls have only session-level labels: 3 of 4 sessions were labelled 100%
  English, and one was 84 s si + 36 s en. Details are in docs/RISI-MODELS.md, "Routing check and
  language hints".
- Language hints: `prior` beat auto-detect and `force` and is now the service default
  (`WHISPER_HINT_MODE=prior`). It takes effect once the app server sends `language_hint`
  (a server-role change, described there).
- kasunw/whisper-large-v3-sinhala is finished (omni-si 20.1%, yt5-si 35.6%, slr30 6.5% CER). It is
  not in the top 2.
- **Still no recordings** in `~/risime-eval-data/real/`. The request below stands.
- Hint runs on the recordings, when they arrive (si+en / ta+en are the deciding sets):
  `scripts/asr-eval run --manifest M --models auto --hint-mode off --sets real --out R` and
  the same with `--hint si --hint-mode prior` (Tamil: `--hint ta`).
- **Waiting for the recordings below.** When they arrive: `python3 -I scripts/asr-eval-data.py`
  (converts and adds them), then run the si/ta/en routes + compare candidates on `--sets real`.

## Request: please record (Harsha + a colleague)
About **62 short clips**, ideally by **two speakers** (you and a colleague), each reading every
line once. The sentence lists are already on spark2, one sentence per line:

| Folder (`~/risime-eval-data/real/…`) | Lines | What |
|---|---|---|
| `si/` | 25 | Sinhala: 20 Risi requests + 5 everyday call lines (21–25) |
| `ta/` | 15 | Tamil Risi requests |
| `en/` | 10 | English Risi requests |
| `si+en/` | 6 | Sinhala with heavy English (Singlish code-switching) |
| `ta+en/` | 6 | Tamil with heavy English (Tanglish code-switching) |

The lists are `sentences.txt` in each folder (copies are at the end of this page).
- **Say it the way you would on a call**, at normal speed, not slow dictation. If you would say a
  line differently, say it your way and **edit that line in `sentences.txt`** to match: the file
  is the reference transcript. English words stay in English letters (meeting, report, client).
  Say numbers as words.
- **Length:** one sentence per clip, 2–12 s. Start recording, wait half a second, speak, stop.
- **How:** any phone voice-recorder app, phone held like a call (not on a table across the room).
  A normal office or home room is right; some background noise is fine (that's the point). Not in
  a car with the window open.
- **Format:** whatever the phone records (m4a, ogg, mp3, wav, amr all work). The eval converts
  them to mono 16 kHz WAV itself (`real/<lang>/16k/`).
- **Names:** `NN-<speaker>.<ext>`, NN = line number with two digits, speaker = one letter per
  person. Examples: `si/01-A.m4a`, `si/01-B.m4a`, `si+en/04-A.m4a`, `ta/15-B.ogg`. Write who A and B
  are (no names needed, e.g. "A male 40s Colombo, B female 20s Kandy") in
  `~/risime-eval-data/real/speakers.txt`.
- **Drop them** into the folders on spark2 (e.g. `scp *.m4a spark2:risime-eval-data/real/si/`).
  Then tell root ("recordings are in"); the next eval run picks them up.
- **Consent and privacy:** only record people who agreed to it, for internal testing only. The
  recordings stay in `~/risime-eval-data` on spark2 (outside Git, never in an APK:
  `scripts/check-apk-testdata`). The sentences are synthetic: no real chats, names or numbers.
- **Optional, very useful:** 2–3 free conversations of 30–60 s (two people talking about work, in
  Sinhala with English words as they come), recorded on one phone, plus what was said typed out as
  well as you can, in `si+en/free-01.txt` next to `si+en/free-01-AB.m4a`. These are scored the
  same way when a `.txt` with the same name exists (not yet wired; root adds it when they arrive).
- The **Sinhala wording is Claude's**: fix anything unnatural, especially si 21–25 and the
  code-switched lines.

## How the eval works
- `scripts/asr-eval-data.py` builds `~/risime-eval-data/manifests/all.jsonl` (audio stays outside
  Git): the public held-out subsets, the recordings above, and **synthetic** code-switch clips (a
  si/ta clip followed by an en clip) only while no real code-switched recordings exist.
- Manifest line: `id, audio, text, lang (si|ta|en|si+en|ta+en), source, licence,
  in_training_of [models], kind, duration_s`. A clip whose source is in a model's training data is
  **excluded from that model's score** (`in_training_of`, from the provenance table in the script).
- `scripts/asr-eval run --manifest M --models a,b,auto --out R.jsonl` sends each clip to the
  running speech service (127.0.0.1:8200: the production loaders and Sinhala prompt) with the
  clip's language forced, or through `model=auto` (routing included). Resumable; waits when the
  service has no memory to load a model.
- `scripts/asr-eval score --manifest M --results R.jsonl [--markdown]`: WER, CER (corpus totals
  after NFC, lower-case, punctuation and ZWJ/ZWNJ removal), **script %** (letters in the
  expected script: Sinhala U+0D80–U+0DFF, Tamil U+0B80–U+0BFF), romanised count, empty count, RTF.

## Sentence lists (copies of `~/risime-eval-data/real/<folder>/sentences.txt`)

**si/**

1. හෙට උදේ හයට මාව ඇහැරවන්න.
2. කුමුට සුබ උදෑසනක් කියලා මැසේජ් එකක් යවන්න.
3. හෙට උදේ දහයට බැංකුවට කෝල් කරන්න මට මතක් කරන්න.
4. බදාදා හවස මම නිදහස්ද කියලා බලන්න.
5. බ්‍රහස්පතින්දා දවල් දෙකට නිමල් එක්ක රැස්වීමක් දාන්න.
6. සිකුරාදා වෙද්දි මම client ට quotation එක යවන්නම්.
7. අද හවස පහට කලින් report එක එවන්න පුළුවන්ද?
8. ලබන සඳුදා අලුත් website එක launch කරනවා.
9. මේ චැට් එකේ අද කතා කරපු දේවල් සාරාංශ කරන්න.
10. ගිය සතියේ අපි තීරණය කරපු දේවල් මොනවද?
11. මගේ පොරොන්දු ලැයිස්තුව පෙන්නන්න.
12. සෙනසුරාදා දවල් දොළහමාරට team lunch එක තියෙනවා.
13. කම්පැනි එකෙන් රුපියල් පහළොස් දහසක් දෙනවා.
14. හැමදාම රෑ දහයට කසුන්ට සුබ රාත්‍රියක් කියලා යවන්න.
15. විනාඩි විස්සකින් ඕවන් එකෙන් කේක් එක එළියට ගන්න මතක් කරන්න.
16. client meeting එක බ්‍රහස්පතින්දා උදේට මාරු කළා.
17. invoice එකේ hosting ගාස්තුව දෙපාරක් වැටිලා තියෙනවා.
18. finance එකෙන් අහලා මම ආපහු කියන්නම්.
19. කුමුට යවන්න හදපු මැසේජ් එක අවලංගු කරන්න.
20. ස්තූතියි රිසි, අද ඔයා ගොඩක් උදව් කළා.
21. ඔයා අද කීයටද ගෙදර එන්නේ?
22. මම තව විනාඩි දහයකින් එන්නම්, පොඩ්ඩක් ඉන්න.
23. අම්මා කිව්වා හෙට උදේම කොළඹ යන්න ඕනේ කියලා.
24. ඒ ගැන මම හෙට උදේ ඔයාට කෝල් එකක් දෙන්නම්.
25. ගාස්තුව ගෙව්වද නැද්ද කියලා මට තාම විශ්වාස නෑ.

**ta/**

1. நாளை காலை ஆறு மணிக்கு என்னை எழுப்பு.
2. கவிக்கு காலை வணக்கம் என்று மெசேஜ் அனுப்பு.
3. நாளை காலை பத்து மணிக்கு வங்கிக்கு போன் பண்ண நினைவூட்டு.
4. புதன்கிழமை மதியம் நான் ஃப்ரீயா என்று பார்.
5. வியாழக்கிழமை மதியம் இரண்டு மணிக்கு ராஜனுடன் மீட்டிங் சேர்.
6. வெள்ளிக்கிழமைக்குள் client-க்கு quotation அனுப்புகிறேன்.
7. இன்று மாலை ஐந்து மணிக்கு முன் report அனுப்ப முடியுமா?
8. அடுத்த திங்கள்கிழமை புதிய website launch செய்கிறோம்.
9. இந்த சாட்டில் இன்று பேசியதைச் சுருக்கமாகச் சொல்.
10. கடந்த வாரம் நாம் என்ன முடிவு செய்தோம்?
11. என் வாக்குறுதிகளின் பட்டியலைக் காட்டு.
12. சனிக்கிழமை மதியம் பன்னிரண்டரை மணிக்கு team lunch இருக்கிறது.
13. கம்பெனி பதினைந்தாயிரம் ரூபாய் தருகிறது.
14. தினமும் இரவு பத்து மணிக்கு அருணுக்கு இனிய இரவு என்று அனுப்பு.
15. இருபது நிமிடத்தில் ஓவனிலிருந்து கேக்கை எடுக்க நினைவூட்டு.

**en/**

1. Remind me to call the bank at ten tomorrow morning.
2. Can you send the report before five this evening?
3. Set up a meeting with Nimal on Thursday at two.
4. Am I free on Wednesday afternoon?
5. I'll send the quotation to the client by Friday.
6. Summarise what we discussed in this chat today.
7. The hosting charge was billed twice on the invoice.
8. Move the client meeting to Thursday morning.
9. Cancel the message I scheduled for Kumu.
10. Thanks Risi, that was really helpful today.

**si+en/**

1. මචං, අද afternoon meeting එක postpone කරමුද? client තාම reply කරලා නෑ.
2. මම report එක email කරලා තියෙන්නේ, please check කරලා feedback එකක් දෙන්න.
3. next week Monday deadline එක miss කරන්න බෑ, so අපි weekend එකේ වැඩ කරමු.
4. server එක down වෙලා, DevOps team එකට inform කරන්න.
5. invoice එකේ amount එක wrong, finance එකෙන් ආපහු confirm කරගන්න.
6. අද client call එක cancel වුණා, අපි tomorrow morning reschedule කරමු.

**ta+en/**

1. இன்னைக்கு afternoon meeting-ஐ postpone பண்ணலாமா? client இன்னும் reply பண்ணல.
2. நான் report-ஐ email பண்ணிட்டேன், please check பண்ணி feedback சொல்லுங்க.
3. next week Monday deadline-ஐ miss பண்ண முடியாது, so weekend-ல வேலை செய்யலாம்.
4. server down ஆயிடுச்சு, DevOps team-க்கு inform பண்ணுங்க.
5. invoice-ல amount தப்பா இருக்கு, finance-கிட்ட திரும்ப confirm பண்ணுங்க.
6. இன்னைக்கு client call cancel ஆயிடுச்சு, tomorrow morning reschedule பண்ணலாம்.
