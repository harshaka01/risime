# Proposal: Risi 30-day summaries (server, 2026-10-09)

Requirement (Harsha, 2026-10-09): Risi keeps a daily summary of each Official chat, rolled up
weekly, so "summarise the last 7 / 30 days" works although raw text stays in the buffer for 24 h
only. This proposal describes exactly what the server added (commit "feat(server): §27 30-day
summaries …"). **Everything is optional and backward compatible**; root folds it into
PROTOCOL.md (suggested: a §27.13 plus amendments to §24.11).

## 1. Server behaviour (normative for the server)
- **Switch:** runs while `RISI_LEDGER=on` (no new switch).
- **Day summary:** for every Official conversation (`chat_kind` `dm` or `group`, never a Risi
  chat) Risi posts nothing; it stores one summary per **local day** of the chat (the chat's zone,
  §24.11) when its clock reads 23:30 or later (checked every 15 min), over the buffered `text`
  messages of that day not yet covered by an earlier day summary. No message, no summary. One
  model call, learning-log task **`daily_summarise`** (schema = the §24.11 `summary` schema).
- **Weekly rollup:** on the chat's local Sunday, the week's day summaries are rolled into one
  (`scope: "week"`), learning-log task **`summary_rollup`**, from the stored summaries only
  (never raw text).
- **Storage:** Postgres `risi_daily_summaries(id, conversation_id, chat_id, scope day|week,
  period_from date, period_to date, tz, covered_from, covered_to, message_count,
  summary_sealed, call_ref, made_by, created_at)`; the summary JSON (`summary`, `decisions`,
  `action_items`, `open_questions`) is **sealed with `RISI_DATA_KEY`** (AAD
  `risi_daily_summaries:<id>:summary`, no plaintext column). Kept **35 days**; deleted with the
  chat's Risi data (Official off, §24.4) and on request (§3).
- Raw text is still kept 24 h only (§24.12 unchanged).

## 2. `risi_request` `summarise`: periods
`scope` (today `{"since": ts}`, unchanged and still accepted) may also be, for `summarise` only:
- `{"period": "today" | "7d" | "30d"}` (**recommended form**: an older server reads no `since`
  and answers its 24-h default instead of failing);
- the bare strings `"today"`, `"7d"`, `"30d"` (accepted);
- `{"from": ts, "to": ts}` (at most 31 days back, else the `error` `out_of_window`).

`today` = from 00:00 in the requester's zone. The answer rests on the stored day summaries of
the range (and the week rollups that lie wholly inside it), the chat's tracked commitments, and
the raw buffered text of the last 24 h not yet covered by a day summary. Nothing in the range:
`error` `nothing_to_summarise`. The reply is the §24.11 `summary` kind with two **optional**
fields (absent for a `since` scope):
```
"period": {"from": ts, "to": ts, "scope": "today" | "7d" | "30d" | "range"},
"days": [{"date": "YYYY-MM-DD", "to": "YYYY-MM-DD", "scope": "day" | "week",
          "summary_id": uuid}]   (the stored summaries used, oldest first)
```
`body` names the period covered: "Summary of the last 7 days (3–9 Oct): …", "Summary of
today: …", "Summary of 12–20 Sep: …". `made_by` as §27.1 (task `summarise`). Apps that don't
know `period`/`days` ignore them.

## 3. "Summaries" in `GET /api/v1/risi/facts`
To a **`risi_tools`** device (`X-Device-Id`), the reply also lists the stored summaries of the
Official chats the caller is an active member of, newest first, after the caller's facts:
```
{"fact_id": uuid (= summary id), "kind": "summary", "text": str (the one-paragraph summary),
 "chat_id": str, "created_at": ts,
 "scope": "day" | "week", "period": {"from": "YYYY-MM-DD", "to": "YYYY-MM-DD"}}
```
Older devices don't get `kind: "summary"`. `DELETE /api/v1/risi/facts/{fact_id}` with a summary
id deletes that summary **for the chat** (any active member may; `404` otherwise).
`DELETE /api/v1/risi/facts` (everything about me) is unchanged and does not delete chat
summaries.

## 4. Examples to add (root)
`envelope_risi_request_period.json` (`{"period": "7d"}`), `envelope_risi_summary_period.json`
(with `period` and `days`), `risi_facts_reply_summaries.json`.
