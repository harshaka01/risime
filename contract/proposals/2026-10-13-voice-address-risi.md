# Proposal: "Risi, …" in an Official voice note counts as @Risi (2026-10-13, risi role)

**Status:** proposal for root/Harsha (PART 9, point 2). Nothing is implemented. The server keeps
today's behaviour until root edits PROTOCOL.md.

## Why this needs the contract
Harsha (PART 9): a voice note whose transcript starts by addressing Risi ("Risi, …", Sinhala රිසි,
Tamil ரிசி, and transcription variants such as "Rishi" or "Risee") should count as @Risi. Risi then
replies in the chat, as it does for a typed @Risi in Official (decision 083). In a group's Official,
Risi replies only when the note addresses it.

The contract forbids the server from doing this today:
- §25.0: "free text is never parsed for intent **by the server or by Risi** (decision 066). The one
  exception is client-side and the sender's own (decision 083) … The wire stays a `risi_request`."
- §24.11: "free text is never parsed for intent [decision 066]".
- v1.42 clarification (decision 083): "the server still never parses free text".

For a voice note, only the server sees the words. Risi is the member that transcribes the note in
memory (§37.6), and the sender's phone never holds a transcript before Risi posts one. So the
decision-083 client exception can't apply, and the server would have to read the transcript. Decision
083 argues that "addressing Risi by name is not intent parsing", but the contract text says that the
exception is client-side only. The risi role therefore stops here and asks for this amendment.

## Proposal (server-only; no new envelope, no new field)
Amend §25.0 and §37.6 with a second, narrow exception (proposed §37.10, "Addressing Risi by voice"):

1. **Scope.** Only an Official `grp:` with Official on (never Private, never the Risi chat, where every
   note is already a request, §37.8). The note must belong to the sender. It must not be forwarded,
   must have been transcribed under §37.6 (`status: "ok"`), and its language and script checks must
   have passed. The rule is the same in 1:1 and group Official: a spoken name is the voice form of
   the @Risi chip, because you can't speak a chip. In a group, a note that doesn't address Risi
   stays an ordinary message (Risi stays quiet), exactly as today.
2. **What counts as addressing.** Only the opening of the transcript. After trimming whitespace and
   leading punctuation, the first word, optionally preceded by "hey", "hi", "ok", "අනේ" or "ஏய்",
   is one of:
   - `risi`, `rishi`, `risee`, `reesi`, `reesee`, `risie`, `rizi`, `risy` (case-insensitive, Latin);
   - `රිසි`, `රිශි`, `රීසි` (Sinhala);
   - `ரிசி`, `ரிஷி`, `ரீசி` (Tamil).

   The word must be followed by a separator (`,` `:` `!` `.` `?` `-`, the Sinhala/Tamil comma, or
   a space) or end the transcript. "Risi" in the middle of a sentence ("I told Risi …") does
   **not** count. The rest of the text is never parsed for what to do: it becomes the question,
   exactly as the typed `@Risi` token does on the phone (decision 083).
3. **Effect.** The server handles the note as a §24.11 `risi_request` `ask` from the sender, with:
   - `text` = the transcript after the address (1–1000 grapheme clusters; an empty remainder gets
     the decision-083 "What can I do?"-style short answer, or nothing, as Harsha decides);
   - `request_id` = a UUIDv5 of the note's `message_id` (namespace fixed in the server), so a
     repeat is idempotent;
   - `lang` = the transcript's language, so the answer comes back in it (as §37.8).
   It goes through the same §24.13 rate limits, §25 loop, §34 routing and decision-083 "never
   silent" notice. The answer is the ordinary `answer` kind in the same Official conversation,
   visible to its members, with `notify: [sender]`. **No new kind, field or envelope.** Phones
   render it as today. One optional addition: the answer's `refs` holds the note's `message_id`,
   so a phone may draw it as a reply to the note.
4. **Opt-outs.** A note with `transcribe: false`, or from a sender whose §37.7 `transcribe` is off,
   is never transcribed, so it can never address Risi. The memory opt-out (§35.3) does not block
   the request: a request is not learning, the same as typed @Risi.
5. **Learning log.** The `request` row gains `meta.via = "voice_address"` (no text).
6. **Switch.** Server env `RISI_VOICE_ADDRESS` = `off` | `canary` | `on`, default `off`, read at
   runtime (`RisiMe.Agents.Flags`), so root can try it on Harsha first.

## Gate (when accepted)
Server tests with the fake speech service and the fake LLM:
- every listed variant at the start (with and without "hey", and each separator) → exactly one
  request job with the remainder as `text`;
- "Risi" mid-sentence → no request, in both group and 1:1;
- Private → no path (the note is never transcribed);
- the Risi chat → the §37.8 path only, no double request;
- a forwarded note, `transcribe: false`, and an `unclear` note → no request;
- a repeat → the same `request_id`, so no second answer.

Phones need no change. This is a fast-lane change (rule 12) once §25.0 and §37 are amended.
