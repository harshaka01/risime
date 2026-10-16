# Proposal 2026-10-16: Translate to more languages (`translate_languages`)

Architect 2026-10-16 14:05 item 3 (Harsha, asked again): the Translate picker has Sinhala / English / Tamil
first, then "More languages…" with a search. §37.13.2 allows only `en | si | ta` as `target` (and as
`always_translate`), and the server rejects any other with `400 bad_request`. The app is ready for more
languages (next APK) but offers "More languages…" only when the server says which ones it can do, so no
user sees a language that then fails.

## Wire change (additive)

1. `GET` / `PUT /api/v1/me/risi/voice` (§37.7, §37.13.2) gain a read-only
   `"translate_languages": ["si", "en", "ta", "fr", "hi", …]`: every target this server translates to, as
   lowercase ISO 639-1 codes (ISO 639-3 only where no 639-1 code exists). Present only while
   `translate_available` is true. Absent = `["en", "si", "ta"]` (today's behaviour). Never sent by clients;
   ignored on a `PUT`.
2. §37.13.2 `POST /api/v1/risi/translate`: `target` may be any code in `translate_languages`; another one
   is `400 bad_request` as today. The reply's `label` stays "Translated by Risi".
3. §37.7 `always_translate`: `"off"` or any code in `translate_languages`.
4. Unchanged: "Wrong language?" (§37.13.1) stays `si | ta | en` (it picks the recogniser, not a target);
   the text-language detection (§37.13.3) stays si / ta / en.

## Server

`RisiMe.Agent.VoiceTranslate`: `@targets` becomes a configured list (default the three; Harsha picks the
rest, e.g. the languages the commercial model translates well: hi, ml, ar, zh, ja, ko, fr, de, es, pt,
ru, it, nl, ms, id, th, vi, tr, ur, bn). The prompt names the target by its English name.

## Client (Android, next APK; already built behind the absent field)

- Buttons: Sinhala / English / Tamil without the text's own language. "More languages…" opens a
  searchable list of `translate_languages`, the user's recent picks (this phone, up to 5) at the top.
- The same picker for voice-note transcripts, typed messages and Settings → Risi → "Always translate
  voice notes to".
- Without `translate_languages` (today's servers): the three buttons only, no "More languages…".

## Example

`contract/v1/examples/risi_voice_settings_languages.json`:

```json
{"transcribe": true, "action_items": true, "translate_available": true, "always_translate": "off",
 "translate_languages": ["si", "en", "ta", "hi", "fr"]}
```

## Tests

- Server: `translate_languages` present only with `translate_available`; a target outside it is
  `bad_request`; `always_translate` accepts a listed code.
- Android: parsing the example; the picker's lists (defaults, source hidden, recents first, search).
