# Risi test sets (eval/sets)

Root, 2026-10-14 (Harsha, intelligence plan item 2; audit gap G4). Test cases for three
capabilities, in four languages, in one directory per capability:

```
eval/sets/
  ledger/{si,ta,singlish,en}.jsonl    promise / commitment detection (Ledger agent)
  summary/{si,ta,singlish,en}.jsonl   chat summaries: facts that must appear, facts that must not
  actions/{si,ta,singlish,en}.jsonl   action items from voice transcripts (calls, voice notes)
  pilot-ids/<capability>.txt          ids of consented pilot cases (the cases themselves are sealed, see below)
  tools/validate.py                   schema + privacy check (run before every commit)
  tools/spotcheck.py                  the stratified 20 % sample for Harsha's spot-check
  tools/baseline.py                   one run against an OpenAI-compatible endpoint (risi-l1), today's prompts
```

The older 81-case golden sets in `server/test/risime/agent/golden/` stay as they are (they are the
`mix test` fixtures of `RisiMe.Agents.Eval`). The cases here use **the same core fields**, so the
harness reads them unchanged once it loads a directory instead of one file; the fields added here
are optional for the harness.

## Rules for every case
- **Synthetic.** Every case here is written by Claude for testing (`"src": "synthetic"`). No real
  customer or pilot message is ever copied into git.
- **Fictional people and companies only.** First names only (Nadeesha, Ruwan, Tharshan, Fathima...),
  invented companies (Lotusbay Hotels, Kandyan Spice Exports...). No phone numbers, emails, account
  numbers or real addresses. `tools/validate.py` rejects digit runs that look like phones, and `@`.
- **Unchecked until a human checks it.** Every case is `"checked": false`. Decision 085: a score on
  unchecked cases is reported as "unchecked set", never as an Index input. A human who checks a case
  sets `"checked": true, "checked_by": "<initials>", "checked_on": "YYYY-MM-DD"` and fixes the gold.
- **One clock.** The harness renders every chat at **Sunday 2026-10-11 10:00, Asia/Colombo**
  (`RisiMe.Agents.Eval` `@now`). Gold dates resolve from that: today = 2026-10-11 (Sun),
  tomorrow / Monday = 10-12, Tue 10-13, Wed 10-14, Thu 10-15, Fri 10-16, Sat 10-17,
  "the 20th" = 2026-10-20, "end of the month" = 2026-10-31. `due` is compared on the date only
  (a time like "5pm" is fine in the text; the gold keeps `YYYY-MM-DD`). Vague deadlines ("next
  week", "soon", "ලබන සතියේ") have `due: null`; such cases carry the tag `vague_due`.
- **Languages.** `lang` is one of `si` (Sinhala script, English loan words as people write them),
  `ta` (Tamil script, Sri Lankan usage, English loan words), `singlish` (Sinhala and English mixed:
  `script: "latin"` = romanised Sinhala + English, `script: "mixed"` = Sinhala script + English words in
  Latin, or English written in Sinhala letters), `en` (Sri Lankan office English). Typos,
  missing punctuation and chat shorthand (pls, k, ok, tmrw, mchn) are on purpose.

## Common fields (all capabilities)
| field | type | meaning |
|---|---|---|
| `id` | string | unique: `ld-`/`sm-`/`ac-` + `si`/`ta`/`sg`/`en` + `-NNN` (e.g. `ld-sg-014`) |
| `lang` | `si`, `ta`, `singlish`, `en` | the case's language (the harness groups `by_lang` on it) |
| `script` | `sinhala`, `tamil`, `latin`, `mixed` | the writing system (Singlish has both) |
| `members` | [name] | the chat members, in order (rendered as u1, u2, ...) |
| `tags` | [string] | what the case tests (see each capability) |
| `src` | `synthetic` or `pilot` | where the case comes from; `pilot` only in the sealed store |
| `checked` | bool | a human checked the gold; always `false` when drafted |
| `checked_by`, `checked_on` | string or null | who checked it and when |
| `note` | string | optional: why the gold is what it is (for the checker) |

## ledger: promise / commitment detection
The `commitment_extract` call (`RisiMe.Agent.Prompts.extract_system/0`, schema `extract_schema/0`).
```json
{"id":"ld-sg-003","lang":"singlish","script":"latin","members":["Nadeesha","Ruwan"],
 "messages":[["Nadeesha","Ruwan quotation eka adha yawanna puluwanda?",false],
             ["Ruwan","ow mama hawasa 4ta kalin yawannam",true]],
 "expect":[{"owner":"Ruwan","to":["Nadeesha"],"kw":["quotation","quote","yawa","send"],"due":"2026-10-11"}],
 "tags":["positive","agreement_after_request"],"src":"synthetic","checked":false}
```
- `messages`: `[sender, text, new?]`, oldest first. Only lines with `new?: true` may produce a
  commitment; `false` lines are context (a promise only in a `false` line is a negative, tag
  `context_only`).
- `expect`: every commitment the model must report, and nothing else.
  - `owner`: the member who promised or agreed (must be in `members`).
  - `to`: the members it was promised to: the member who asked for it, or who is named as its
    receiver. `[]` when nobody is clear (a promise to the group, an unprompted "I'll do X").
  - `kw`: any one of these substrings (case-insensitive) must appear in the model's `text`.
    Lists hold the English word and the Sinhala/Tamil stems the model may write.
  - `due`: `YYYY-MM-DD` or `null` (no deadline, or a vague one).
- `expect: []` is a negative. Negative tags: `joke`, `sarcasm`, `hypothetical` (if/would),
  `past` (already done), `other_person` (someone outside the chat, or a member reporting another
  person's promise), `question`, `suggestion`, `wish`, `group_plan` (no owner), `refusal`,
  `context_only`, `vague_intent` ("maybe I'll try").
  Positive tags: `positive`, `agreement_after_request`, `multi` (several items), `conditional_met`,
  `injection` (a message tries to give orders to Risi), `typo`, `vague_due`, `cancelled_then_new`.
- Optional `trap` (as in the golden set): a deliberate hallucination for the fake model.
- **Pass** (harness `ledger_case/2`): every expected item matched (owner, `to`, `due`, a `kw`),
  no extra item, and no hallucination. **Hallucination**: an owner or counterpart who isn't a member,
  a due date where the chat gives none, or a name, amount or date in the item text that the chat
  doesn't contain (`Eval.invented/2`).

## summary: chat summaries
The `summarise` call (`Prompts.summary_system/0`, schema `summary_schema/0`: summary, decisions,
action_items, open_questions; the checker reads all four joined).
```json
{"id":"sm-en-004","lang":"en","script":"latin","members":["Dilini","Kasun","Tharshan"],
 "messages":[["Dilini","Lotusbay wants the invoice reissued with VAT",true], ...],
 "must":[["invoice"],["vat"],["15,000","15000"]],
 "forbid":["paid","20,000"],
 "forbid_why":{"paid":"nobody said the invoice was paid","20,000":"the old figure was withdrawn"},
 "tags":["invoice","number_trap"],"src":"synthetic","checked":false}
```
- `messages`: as in ledger; all `new?: true` in drafted cases.
- `must`: the key facts. Each entry is an any-of list of substrings (case-insensitive); every entry
  must be matched somewhere in the output. Variants cover English words and Sinhala/Tamil spellings
  (`["quotation","quote","කොටේෂන්"]`). Numbers are listed with and without separators.
- `forbid`: substrings that appear **only if the model makes something up**: an amount nobody
  stated, a person who wasn't assigned, a decision that wasn't taken, a date that wasn't set. Each
  one is explained in `forbid_why` (for the checker). A forbid hit counts as one hallucination.
  Forbid strings are chosen so a correct summary cannot contain them. The one exception is a case
  tagged `injection`: there `forbid` may hold words of the injected message itself (as golden case
  s-en-03 does), because a summary must not repeat an injected claim as a fact.
- The automatic check (`Eval.invented/2`) adds every number, weekday/month name or capitalised
  name in the output that the chat doesn't contain.
- Optional `fake` / `trap`: oracle and trap replies for the fake model (golden-set style). The
  drafted sets don't carry `fake`; the fake-model test should skip a summary case without it.
- **Pass**: every `must` matched and zero hallucinations. **Made-up items** = the sum of
  hallucinations (forbid hits + invented tokens) over the set.

## actions: action items from voice transcripts
The `extract_actions` call (`Prompts.action_system(source)`, schema `action_schema/0`), over an
ASR-style transcript: no punctuation or wrong punctuation, fillers ("ah", "ඉතින්", "அப்ப"),
mis-heard words, a speaker per line.
```json
{"id":"ac-ta-002","lang":"ta","script":"tamil","source":"call","members":["Priya","Arun"],
 "lines":[["Priya","அருண் அந்த invoice நாளைக்கு அனுப்ப முடியுமா"],["Arun","ஓம் நாளைக்கு மத்தியானம் அனுப்புறன்"]],
 "expect":[{"kind":"promise","owner":"Arun","to":["Priya"],"kw":["invoice"],"due":"2026-10-12"}],
 "also":[{"kind":"number","kw":["45,000","45000"]}],
 "forbid":[],"tags":["promise"],"src":"synthetic","checked":false}
```
- `source`: `call` (several speakers) or `voice_note` (one speaker, `members` may list the chat).
- `lines`: `[speaker, text]` (speaker in `members`), rendered like chat lines without `new`.
- `expect`: the items that must be found: `kind` `promise`, `task` or `meeting` (promise and task
  match each other: the model may label either), `owner` (a member, or `null` for a task with
  no owner said), `to`, `kw`, `due` as in ledger (for a meeting, the meeting day). A **meeting** is
  matched on `kw` and `due` only: its `owner`/`to` are informative (who proposed it, who attends),
  because "who owns a meeting" is ambiguous. A `task` with `owner: null` means nobody was named:
  a model that guesses an owner fails it (the prompt says "never guess an owner").
- `also`: optional decision / question / number items. Reported, not required.
- `forbid`: as in summary (checked over every item text and the summary).
- **Pass**: every `expect` matched; no extra promise/task/meeting item with an owner that `expect`
  doesn't have; zero hallucinations (owner not a member, a due where the transcript gives none,
  invented name/amount/date, a forbid hit). Negatives (`expect: []`, tags `chit_chat`,
  `past`, `hypothetical`, `other_person`, `joke`) pass only with no owned promise/task/meeting.

## Running
- Validate: `python3 -I eval/sets/tools/validate.py` (schema, unique ids, owners in members, dates,
  no phones/emails, `checked` present).
- Baseline against the live risi-l1 (read-only API calls, one at a time, no commercial LLM):
  `python3 -I eval/sets/tools/baseline.py --url http://127.0.0.1:8100 --model risi-l1 --key-env LLM_API_KEY --prompts <prompts.json> --out <dir>`
  where `prompts.json` is today's prompts dumped from the server code
  (`extract_system`, `summary_system`, `action_system_call`, `action_system_voice_note` + schemas;
  `tools/baseline.py --help` shows the one-line `elixir` dump). It renders chats exactly like
  `Prompts.render/4` and scores with a port of `Eval.invented/2`; the Elixir harness stays the
  reference (`scripts/risi-eval --live`) once it reads `eval/sets/`.
- Spot-check sample: `python3 -I eval/sets/tools/spotcheck.py` rewrites
  `docs/status/testset-spotcheck-<date>.md` (stratified by capability x language x tag group, 20 %,
  fixed seed).

## Adding consented pilot cases (sealed, never in git)
Real pilot messages arrive only through the sealed learning log (plan item 0: replayable Official
input text, sealed at rest, retention + opt-out; Private never). The path:
1. A reviewer picks a candidate from the learning log on spark2 (only for users who did not opt
   out, Official chats only), redacts names/numbers it doesn't need, and writes the case in this
   schema with `"src": "pilot"`, a fresh id `p<cap>-<lang>-<8 hex>` and `checked: true` once labelled.
2. The case is stored **sealed** (the server's at-rest seal, `RisiMe.Agent.Seal`) under
   `~/risime-sealed/eval-sets/<capability>/<lang>.jsonl.sealed` on spark2: outside git, outside any
   APK, mode 0700, in the nightly backup only in sealed form. Its retention follows the learning log
   (deleting the source on opt-out deletes the case: the reviewer tool keeps the source ref).
3. Only the id goes into git: one line in `eval/sets/pilot-ids/<capability>.txt`.
4. The harness reads `eval/sets/<capability>/` plus, when `RISI_EVAL_SEALED_DIR` is set on spark2,
   the unsealed stream of that directory in memory (never written out). Reports name pilot cases
   by id only.
Until that exists, `pilot-ids/` stays empty and every score here is on synthetic cases.
