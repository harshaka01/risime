# Proposal 2026-10-11: Basic messaging — copy, forward, reply, share, star, info, files, PDF export (v1.34, §33)

> **Accepted into v1.34 (root, 2026-10-11).** Folded into `contract/v1/PROTOCOL.md` **§33**, with
> examples under `contract/v1/examples/` and the fixture `contract/v1/copy_format_cases.json`.
> PROTOCOL.md wins where they differ. Root's decisions on the draft's open questions: **no blob
> grants** (a forward of media always re-uploads with a fresh key, so the server learns no forward
> graph; grants are a "later" note, §33.6); star is device-local plus backups; copy dates follow the
> phone locale; "Forwarded many times" → one target; own forwards unlabelled; the PDF writer is
> checked by android first (a library plus a decision note if `pdftotext` fails); no general file
> picker until A; no forward to Risi; only Private copies are marked sensitive; the view-once marker
> name `view_once` is reserved now for F. (PROTOCOL.md became v1.33 with D1, so this is v1.34.)

Requested by Harsha in `docs/NEXT-PHASE.md` §E (WhatsApp parity). It extends §10.3, §11, §12.7,
§14, §15, §17.7, §22.5, §24.9, §25.3 and §25.4, and overrides them where it differs (§33.17 lists
every amendment).

**Additive only:**
- three optional envelope fields: `forwarded`, `reply_to` and `file` (a new envelope type);
- two device capabilities, `files` and `pdf_export`;
- readiness fields `files_ready` and `missing_files`;
- the reserved envelope field `view_once` (its fields are defined by F);
- one Risi client tool `export_pdf`, one `confirm` button pair `["send", "cancel"]`, and one
  `next_actions` action `pdf`;
- `starred_at` on the backup `message` line.

No new event kind, no new REST endpoint, and no new error code. Apps before v1.34 ignore the new fields and the `file`
type (§10.3); what they show is in §33.18. **Hard rule 9:** the only Room change is the new table
`stars` (§33.11), plus nullable columns. Nothing is moved or rewritten.

---

## 33. Basic messaging (v1.34)

### 33.0 Principles
- **Client-first.** Copy, Share, Star and the PDF are on the phone only. Forward and Reply are
  ordinary MLS application messages sent through the normal outbox. The server learns nothing
  new from any of them (§33.16): a forwarded photo or file is a fresh upload with a fresh key.
- **No plaintext on the server, ever.** A forward is re-encrypted on the phone for each target
  (MLS, §10.3). A PDF is made on the phone and never on the server. Risi never sees a PDF's
  bytes, and the model never writes a PDF's content (§33.15).
- **View once is never copied, forwarded, shared, starred, quoted or exported.** The envelope
  field name **`view_once`** is reserved now: any envelope carrying a `view_once` key (any JSON
  value; F defines its fields) is **never forwardable, copyable, shareable, starrable, quotable
  or exportable**, and is never indexed for search. Clients apply this from v1.34 on, before F
  exists, so a v1.34 app can never leak an F item.
- **Private stays Private.** Moving content from Private into Official is the user's explicit
  choice, after a one-time warning (§33.8). The Risi chat is never a forward target in v1.34.
- **Honest labels.**
  - "Forwarded" is a hint that the sender asserts. It is not proof, as on WhatsApp.
  - Info shows only receipts the server already has (§33.12).
  - Risi says a PDF was "sent" only on a phone result `sent` (§33.15; the §25.3 v1.32 honesty
    rule).

### 33.1 Capabilities and readiness
- Device capability **`"files"`** (`device_put_files.json`). An app advertises it only once it
  can **receive, validate and open** the `file` envelope (§33.13).
- **`GET /api/v1/mls/groups/{conversation_id}`** gains **`files_ready`** and **`missing_files`**
  (`mls_group_files_ready.json`), computed exactly as `images_ready` (§14.1). Like `images_ready`,
  they are a hint only and are never enforced by the server.
  - **DMs:** a file can't be sent or forwarded to a DM that isn't ready. The target is shown
    disabled with "<name> needs to update the app to receive files".
  - **Groups:** sending is allowed, with the one-line notice "Some members need to update to see
    files".
- Device capability **`"pdf_export"`**:
  - Advertised by an app that implements §33.14 **and** the Risi parts of §33.15.
  - The server keeps it only together with `risi_tools`, and silently drops it otherwise (as for
    §30.1).
  - It gates only the Risi tool and the `pdf` chip. Manual export (§33.14) is client-only and
    needs no capability.
- `forwarded` and `reply_to` need no capability. An old app shows the message without the label
  or the quote.

### 33.2 Selection and the selection bar (client)
- **Long-press** a bubble to enter selection mode. Then a tap toggles a bubble.
  - The top bar shows the **selected count** and the actions **Copy, Forward, Share, Star, Reply,
    Info, Delete**. Actions that don't fit go in an overflow ⋮.
  - Up to **100** bubbles can be selected (the §15.2 target cap).
- **Never selectable:**
  - tombstones (§15.6);
  - system lines (§12.7 `group_event`, §13.3/§17.12 markers, the §12.8 lines);
  - call lines (§16.6) and the "couldn't be decrypted" lines;
  - reactions;
  - **any item whose envelope carries `view_once`** (§33.0; also excluded from search);
  - the §25.4 progress bubble.
- **When each action is enabled.** If an action doesn't apply to every selected bubble, it is
  disabled; it is not applied to a subset.

  | Action | Enabled when |
  |---|---|
  | Copy | 1–100 selected, each copyable (§33.3) |
  | Forward | 1–**30** selected, each forwardable (§33.7) |
  | Share | 1–**10** selected, each shareable (§33.10); any media already downloaded and verified |
  | Star / Unstar | 1–100 selected; "Unstar" when all are starred |
  | Reply | exactly 1 selected, with a `message_id`, not a Risi card with buttons (`confirm`, `offer`, `calendar_offer`, `calendar_invite`) |
  | Info | exactly 1 selected, **own**, with a `message_id` |
  | Delete | as §15.7 (for me / for everyone rules unchanged) |

- **Long-press menu.** A long-press without multi-select keeps the §11.3 reaction row. Its menu
  gains Reply, Forward, Star, Share and Info under the same rules. **Text selection inside a
  bubble:**
  - "Select text" in the menu opens the body in a `SelectionContainer`, so the system
    copy/select-all toolbar works on part of the text.
  - It is available for text bodies and captions only.

### 33.3 Copy (client only)
**A single message** (long-press → Copy, or one bubble selected): the content alone, with no
prefix.
- text: the body;
- image or file: the caption. Copy is hidden when there is no caption;
- a Risi card: its `body`. For a `note_card`, the §30.6 share text.

**Two or more messages**: one line per message, in **chat order** (the order the chat shows:
`server_ts`, then `message_id`; local outbox rows at their shown position). Lines are joined with
`"\n"`, with no trailing newline:
```
[<date>, <time>] <name>: <content>
```
- **Date and time.** Use the message's `server_ts` (for an unsent own row, its local creation
  time), in the phone's zone and the app's locale:
  - date: `DateFormat.getBestDateTimePattern(locale, "ddMMyy")`;
  - time: `getBestDateTimePattern(locale, "HHmm")` when the phone uses 24-hour time, else
    `"hhmma"`;
  - always use Latin digits (`-u-nu-latn`);
  - replace U+202F and U+00A0 with U+0020, and remove the bidi marks U+200E, U+200F and U+061C.

  The result for en-GB, 24 h: `[10/10/26, 07:26]`. For en-US, 12 h: `[10/10/26, 07:26 AM]`.
- **Name:**
  - own messages: the user's own display name (`/me`, not "You");
  - others: the name the chat shows for that sender;
  - Risi: "Risi";
  - an unknown sender: "Unknown". Never a phone number.
- **Content:**

  | Item | Content |
  |---|---|
  | text | the body. Line breaks are normalised to `\n`, and continuation lines have no prefix. `forwarded` and `reply_to` are not shown |
  | image | `<Photo>`, or `<Photo> <caption>` |
  | file | `<File: <name>>`, or `<File: <name>> <caption>` |
  | voice (A) | `<Voice note m:ss>`, or `h:mm:ss` from one hour |
  | video (A) | `<Video m:ss>`, plus ` <caption>` |
  | Risi message | its `body`. A `note_card` uses the §30.6 share text |
| own `risi_request` (shown as the user's bubble in the Risi chat, §25.2) | its `text` |

- **Clipboard:** a `ClipData` with the label "RisiMe".
  - **Copies from a Private conversation** set `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+,
    so the system clipboard preview doesn't show the text.
  - The app's toast "Copied" ("Copied 3 messages") shows on API ≤ 32 only, because 33+ shows its
    own.
- **Fixture:** `contract/v1/copy_format_cases.json` (Android only):
  - `{"locale", "hour24", "zone", "own_name", "messages": [{"from_name", "own", "server_ts", "payload"}], "expected"}`;
  - en-GB and en-US cases are exact; si-LK and ta-LK are checked against the regex
    `^\[[0-9/.\-]+, [0-9:]+( ?[^\]]+)?\] .+: .*$` (CLDR patterns vary by Android version);
  - cases (11, in the file): texts and a photo from two people; a multi-line body (CRLF) and a
    forwarded text (no marker); photos with and without a caption; files; a Risi request and
    answer; an own unsent row; en-US 12-hour (day ≠ month, U+202F); a single message (no prefix);
    a single photo (caption only); si-LK and ta-LK structure.
  - Example of case 1 (en-GB, 24 h, Asia/Colombo):
    ```
    [10/10/26, 07:26] Kamal: Can we move the site visit?
    [10/10/26, 07:27] Harsha: Yes, 3 PM
    [10/10/26, 07:27] Kamal: <Photo> Level 3 plan
    ```

### 33.4 Forward: the `forwarded` field and the flow
**Envelope.** Every forwardable envelope type (`text`, `image`, `file`, and A's `voice` and
`video`) gains the optional field **`"forwarded": {"hops": int}`**:
- `envelope_text_forwarded.json` (`hops` 1);
- `envelope_text_forwarded_many.json` (`hops` 7);
- `image_payload_forwarded.json`.

**Building the forwarded envelope (sender).** It is rebuilt from the **parsed known fields** of
the source envelope. The raw JSON is never copied, so unknown fields are never carried.
- **Dropped:** `risi`, `reply_to` and the source's `forwarded`. (A source with `view_once` is
  never forwarded, §33.0.)
- **`hops`:**
  - `source.forwarded.hops + 1` when the source was forwarded;
  - `1` when the source is someone else's unforwarded message;
  - **no `forwarded` field** when the source is the user's **own** unforwarded message (as on
    WhatsApp).
  - The value is capped at **255**.
- **Media fields** (`blob`, `enc`, `mime`, `w`, `h`, `thumb`, `caption`, `name`, `pages`, A's
  duration and waveform fields) are copied unchanged, except `blob` and `enc`, which come from a
  fresh upload for each target (§33.5).
- **A Risi message** (a `text` envelope with `risi` from the agent leaf) becomes a plain `text`
  envelope whose `body` is the card's `body`. A `note_card` uses the §30.6 share text, which ends
  "— shared from Risi Notes". The `risi` object is never copied (it would be ignored from a human
  leaf anyway, §24.11).
- The result must stay within the §10.3/§14.4 bounds. If a captioned image goes over 22 KiB,
  `thumb` becomes `null`, as §14.4.

**Receiving.**
- `forwarded` is valid when it is an object whose `hops` is an integer from 1 to 255. Anything
  else is **ignored** (`envelope_text_forwarded_bad.json`: `hops: 0`). The message is still shown
  and stored, without a label; it is never dropped for a bad `forwarded`.
- **Label** above the bubble content, in small grey italics with an arrow icon:
  - `hops` 1–4: **"Forwarded"**;
  - `hops` ≥ 5: **"Forwarded many times"**, with a double arrow.

  The number is never shown. The label applies in notifications too: "↪ Forwarded: <preview>" on
  the content line of a local notification (the FCM push stays content-free, §8.2).
- `forwarded` is stored with the message (Room: `forward_hops INTEGER NULL`, a nullable column)
  and included in backups and history shares as part of `payload`.

**Flow (client).**
1. **Pick the source:** select up to 30 forwardable bubbles (§33.2), then tap Forward.
2. **Pick the targets.** The picker shows a search box, then **"Recent chats"** (by last activity),
   then all chats A–Z.
   - Each tab is its own target, labelled "🔒 Private" or "● Official".
   - At most **5 targets**. When any source shows "Forwarded many times", only **1 target** is
     allowed; the snackbar says "Messages forwarded many times can be sent to one chat at a time".
   - **Not offered:**
     - the Risi chat;
     - a conversation that isn't e2ee ("Not end-to-end encrypted yet");
     - a conversation the user is no longer an active member of;
     - an Official tab that is `off` or `none`;
     - a DM with a blocked or unfriended user.
   - **Offered but disabled, with the reason:**
     - a DM that isn't `images_ready` (or `files_ready`) when the selection contains an image (or
       a file);
     - a DM that lacks A's capability for a voice note or video.
   - An optional **"Add a message"** field is sent **after** the forwarded items, as an ordinary
     unforwarded `text`.
3. Show the **Private → Official hint** (§33.8) if it is due.
4. **Persist before sending.** For each target and each source, in chat order: one outbox row with
   a fresh `client_msg_id`, the built envelope sealed as for any outbox row, and, for media, a
   pending re-upload job (§33.5) with its own `client_blob_id` (UUIDv4).
   - All rows are written in **one transaction** before anything is sent, so a crash resumes the
     whole forward.
   - The sender's bubbles appear at once in each target, with the "Forwarded" label (own view)
     and the clock tick.
5. **Media:** re-encrypt and upload for that target (§33.5), then send.
   Text rows are sent at once, without waiting for media rows. This is the §14.7 rule that a media
   row never blocks the outbox.
6. **Send** each row with the normal e2ee `msg:send` (§10.3 / §12.9). It is encrypted at send time
   for **that target's MLS group**, with `stale_epoch` handling unchanged.
   - The §4 rate limit applies: the outbox paces at 20 per 10 s and backs off on `rate_limited`.
   - 30 sources × 5 targets is at most 150 sends, about 75 s.
7. **Done:** a snackbar "Forwarded to 3 chats". With one target, the app opens that chat.

**Search** (local, §24.9) indexes forwarded messages like any other. View-once items are never
indexed (F).

### 33.5 Media on forward: always a re-upload with a fresh key (client)
Media is encrypted per blob with a fresh key `K` carried in the envelope (§14.3, §14.4), and the
§14.2 `media` read rule binds a blob to **its upload conversation**: the target's members can't
read the source blob. v1.34 therefore **never reuses a blob across conversations**. Every forward
of a photo, file, voice note or video is, **for each target**:
1. **Plaintext:** decrypt the cached, verified ciphertext with the stored key (§14.7), or, if
   the cache is gone and the blob is still fetchable (`server_ts + 29 days`), download and verify
   it first (with progress), then decrypt. Plaintext is held in memory or streamed file to file in
   `noBackupFilesDir`, and never kept after the job.
2. **Images** are re-encoded first as for Save (§14.7 receiving 8): the same output rules as a new
   photo (§14.7 sending 2–3), including a fresh thumbnail. **Files**, voice notes and videos are
   encrypted byte for byte; their `thumb` (if any) is copied.
3. **A new encrypt call** (§14.3): a fresh `K`, never the source's key and never the same
   ciphertext (a byte-identical blob would let the server link the two conversations).
4. **Upload** with `purpose=media` into the **target** conversation and a new `client_blob_id`
   (§14.2: idempotency, retries, quota and the §14.7 failure wording unchanged).
5. Put the new `blob` and `enc` into that target's envelope, then send it (§33.4 step 6).

- **One upload per target.** Five targets mean five uploads with five keys, so the server can't
  link targets by bytes. It sees only ordinary new uploads (§33.16).
- **The user's own media still uploading** is forwarded from its local ciphertext the same way; it
  doesn't wait for the source's upload.
- **Not forwardable:** an item whose blob is past its horizon (or `404`) and that this phone never
  downloaded, or whose cache was evicted. It is disabled in the selection with "This photo is no
  longer available" ("…file…", "…voice note…", "…video…").
- Quota (`413 quota_exceeded`): the row fails with "You've reached your photo and file storage
  limit. Older items free up space after 30 days." Each failed bubble offers Retry / Delete
  (§14.7).
- The PDF "Send to chat" (§33.14) follows the same rule: one encrypt and upload per target.

### 33.6 Later: blob grants (not in v1.34)
A server "grant" that lets a second conversation read an existing blob would forward media without
re-uploading, but it would tell the server which conversations a photo or file moved between (the
forward graph). Root decided against it for v1.34. If Harsha wants it, it needs its own proposal,
with that metadata cost stated in the privacy note.

### 33.7 What can be forwarded

| Source | Forwardable | Becomes |
|---|---|---|
| `text` | yes | `text` + `forwarded` |
| `image` (§14.4) | yes, if the phone has or can still fetch it (§33.5) | `image` + `forwarded`; the caption is kept |
| `file` (§33.13) | yes, as for an image | `file` + `forwarded` |
| `voice`, `video` (A) | yes, as for an image. A defines the fields; this rule applies to every envelope with `blob` + `enc` | the same type + `forwarded` |
| Risi messages (`answer`, `summary`, `report`, `digest`, `discussion_summary`, `discussion_card`, `notes_saved`, `event_card`, `reminder`, `reminder_set`, `draft`, `commitment*`, `ops_alert`, unknown kinds) | yes | `text` from `body` (§33.4); no `risi`. The receiver's label reads "Forwarded" like any forward |
| `note_card` (§30.4) | yes | `text` with the §30.6 share text ("…— shared from Risi Notes") |
| Risi `confirm`, `offer`, `calendar_offer`, `calendar_invite`, `error` | **no** | meaningless outside their conversation, with buttons |
| any envelope with `view_once` (F) | **never** | — |
| tombstones, system lines, call lines, reactions, `profile_photo`, history and control envelopes | no (not selectable) | — |
| own pending text | yes | `text` (the source's local content) |
| own media still uploading | yes, from its local ciphertext (§33.5) | the same type, own upload |

### 33.8 Private → Official hint (client only)
- **When:** the first time on this device that a forward has **at least one source from a Private
  conversation and at least one Official target** (including a 1:1's Official tab).
- **Dialog:**
  - title: "Forward to Official?";
  - body: "Risi can read messages, photos and files in Official chats. What you forward there is
    no longer only in Private.";
  - buttons: [Forward] [Cancel].
- After it has been shown, whatever the answer, the flag
  `hints.private_to_official_forward = true` is set in DataStore and the dialog is never shown
  again on this device. A reinstall shows it again once.
- It is not shown for Official → Private, Official → Official or Private → Private, or for the
  PDF "Send to chat" from a Risi source (that content is already Official).

### 33.9 Reply (`reply_to`)
**Envelope.** `text`, `image`, `file` (and A's types) gain the optional field
**`"reply_to": {"message_id": "<timeuuid>", "from": "<user uuid>"}`**
(`envelope_text_reply.json`).
- **No snippet travels.** This keeps §15.6 ("a quote … never keeps a copy of its body"): every
  receiver renders the quote from **its own local copy** of the target.
- **The target** must be a normal message of the **same conversation**: one with a `message_id`,
  not a reaction, control, system line or tombstone, and without `view_once` (§33.0).
- **Sender:**
  - Reply is offered only on such targets (§33.2).
  - The composer shows the quote bar, with the name and first line, and an ✕.
  - The reply is sent as a normal message.
- **Receiver.** `reply_to` is ignored, and the message is shown without a quote, when it is
  malformed or its target is known to be in another conversation. Otherwise, a quote block above
  the content shows:
  - the target's **authenticated** sender name (from the local row; `from` is used only when the
    target is missing) and the first 2 lines of its body or caption, or "📷 Photo", "📄 <name>",
    "🎤 Voice note 0:12", "🎬 Video";
  - for a target that isn't on this phone (joined later, a history gap, purged): "Original message
    isn't on this phone" (with "Reply to <name>" from `from`);
  - for a target that is **deleted** (a tombstone or a hidden tombstone): "This message was deleted"
    (§15.6). The quote updates live when the target is deleted later.
- **Tap** the quote to scroll to the target and flash it. If the target is above the loaded window,
  the app pages back until it is found (at most 2 000 rows, then a toast "Original message isn't
  loaded").
- Room: `reply_to_message_id TEXT NULL`, a nullable column. `reply_to` is kept in backups and
  history shares as part of `payload`. It is **not** copied on forward (§33.4) and not shown in
  Copy (§33.3).

### 33.10 Share (Android share sheet; client only)
- **Text only** (text bodies and Risi cards): `ACTION_SEND`, `text/plain`. One message uses its
  content; more than one uses the §33.3 multi-line format.
- **Photos and files:** `ACTION_SEND` (one) or `ACTION_SEND_MULTIPLE` (up to 10), with the item's
  `mime`, `*/*` when mixed. Any text items go in `EXTRA_TEXT`, in the §33.3 format.
- **Plaintext never touches the disk while sharing.**
  - Each item is offered as a `content://` URI of the app's own `ShareProvider`
    (`exported=false`, `grantUriPermissions=true`).
  - `openFile("r")` returns a `ParcelFileDescriptor.createReliablePipe()`, fed off the main thread
    from the decrypted bytes. An image is **re-encoded** as for Save (§14.7 receiving 8). A file is
    streamed through the §14.3 decryptor.
  - `query()` returns `DISPLAY_NAME` (the file name, or `RisiMe-<yyyyMMdd-HHmmss>.jpg`) and `SIZE`
    (from `plain_size`, or the re-encoded length).
  - URIs are valid for **10 minutes** or until the process dies, then
    `revokeUriPermission`. Every write mode is refused.
- **Only downloaded and verified media** can be shared. A tap on Share for an item not yet
  downloaded starts the download with progress first.
- **Not shareable:** anything with `view_once` (§33.0), and anything not copyable (§33.2).
- Share is one of the paths by which plaintext leaves the app (amends §14.7 receiving 8, "the only
  path": now Save, Share and Open-with, §33.13).
- (Inbound share, from other apps into RisiMe, is A's file and photo picker work, not v1.34.)

### 33.11 Star (device-local in v1.34)
- **Room (additive):** `stars(message_id TEXT PK, conversation_id TEXT NOT NULL, starred_at INTEGER NOT NULL)`.
  - A star icon is shown in the bubble's time row.
  - Chat ⋮ → **"Starred messages"** lists that conversation; each tab is separate (§24.9).
  - The chat list ⋮ → **"Starred"** lists all conversations, newest star first, with the chat
    name and tab.
  - Tapping a row jumps to the message.
- **Purge:** a delete (for me or for everyone), Clear chat or Delete chat removes the star rows of
  the purged messages **in the same transaction** (§15.6 "any derived row"). In v1.34 Clear chat
  has no "keep starred" option.
- **Backups (§22.5):** the `message` line gains **`"starred_at": ts | null`**. It is absent when
  the message isn't starred; schema 1/2 is unchanged. Restore re-creates the star.
- **History shares (§17):** stars are never included; they are personal.
- **Not synced to the user's other devices in v1.34. Why:**
  - An MLS application message goes to **every member** of the conversation, so a star sent there
    would tell the other members.
  - The only conversation with just the user's own devices, the **Risi chat**, contains Risi:
    a star on a Private message would reach the server agent, and Private never reaches Risi
    (§24.0).
  - A proper channel needs a new **own-devices MLS group** (`self:`), with core policy like the
    Risi chat minus the agent. That is a crypto change, for a later proposal.
    It would also carry read state and drafts.
  - Until then, stars live on the phone and in its backups. The Starred screen says "Stars are kept
    on this phone".

### 33.12 Info (delivery and read, from existing receipts)
- Info is offered **only on the user's own messages** with a `message_id` (§33.2). It shows the
  bubble, then:
- **DM:**
  - **Sent** `server_ts`, **Delivered** and **Read** from the `status` events' `at` (§2.3).
  - Room adds `delivered_at INTEGER NULL` and `read_at INTEGER NULL`, as nullable columns. Only
    statuses received from v1.34 on have times; older rows show the tick state with "—".
  - A `read` that arrives without a `delivered` sets both to its `at`.
- **Group, including Official:**
  - `GET /api/v1/groups/{id}/messages/{message_id}/receipts` (§12.7; sender only, while the message
    is retained) is called when the screen opens. `group_receipt` events refresh it live.
  - Sections: **"Read by"** (`read_at`, newest first), **"Delivered to"** (delivered, not read),
    **"Waiting"** (neither).
  - Each row shows the member's name and time.
  - **Agent members (`kind: "agent"`) are never listed.** Risi doesn't send human-style receipts,
    and "Read by Risi" would mislead.
  - `404` (expired from retention): "Receipts are no longer available". Offline: the last loaded
    list with "Couldn't refresh · Retry".
- Old messages and other people's messages have no Info. Nothing new on the wire.

### 33.13 The `file` envelope (minimal; coordinated with A)
No generic file message exists in v1.33: only `image` (§14.4). v1.34 defines a minimal **`file`**,
which A extends (picker, 100 MB, progress, resume); A must not redefine these fields.
`file_payload.json`:
```json
{"v": 1, "type": "file",
 "blob": {"blob_id": "…", "size": 196656, "sha256": "<b64 SHA-256 of the ciphertext>"},
 "enc": {"alg": "A256GCM-S64K", "key": "<b64 32 bytes>", "plain_size": 196608},
 "name": "Interview planning – 2026-10-09.pdf",
 "mime": "application/pdf",
 "thumb": {"mime": "image/jpeg", "w": 91, "h": 128, "data": "<b64, ≤ 4096 bytes decoded>"},
 "pages": 3,
 "caption": "Notes from Friday"}
```
- **`blob`, `enc`:** exactly as §14.4. `purpose=media` and its caps, quota and TTL (§14.5) apply,
  so **`plain_size` ≤ 16 515 072 bytes in v1.34**.
- **`parts`** is **reserved for A** (multi-blob files over 16 MiB). A v1.34 receiver that sees a
  `file` with `parts` and no `blob` shows the placeholder bubble "📄 <name> · Update RisiMe to open
  this file" (`file_payload_parts.json`), instead of dropping it.
- **`name`:**
  - 1–255 bytes of UTF-8, NFC;
  - no C0/C1 control characters, no U+202A–U+202E or U+2066–U+2069 (bidi overrides), and no `/`,
    `\` or NUL;
  - otherwise the envelope is **dropped as malformed and logged** (`file_payload_bad_name.json`).
  - Senders sanitise the name before sending: they replace those characters with `-`, trim, and
    cut to 255 bytes on a grapheme boundary.
  - Receivers also strip leading `.` and cut at 120 graphemes **for display and for saving**.
- **`mime`:**
  - a lowercase `type/subtype` matching `^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$`;
  - otherwise it is treated as `application/octet-stream` (not dropped);
  - it is a hint only and never trusted for in-app rendering.
- **`thumb`** (optional, or `null`): the §14.4 thumb rules (JPEG or WebP, ≤ 128 px, ≤ 4096 bytes).
  The sender renders it **only for files it made or picked**: page 1 of its own PDF through
  `PdfRenderer`.
- **`pages`** (optional): 1–10 000 for a PDF; otherwise absent.
- **`caption`:** as §14.4 (§11.1 limits). It is omitted when empty.
- `forwarded` and `reply_to` may be present (§33.4, §33.9). The envelope bound is **22 KiB**, as
  §14.4; `thumb` becomes `null` first.
- **Strict validation before storing or fetching:** the §14.4 checks for `blob` and `enc`, `name`
  as above, `pages` in range, and `thumb` as §14.4.

**Client (v1.34).**
- **Sending** a `file` comes only from **PDF export** (§33.14) and from **forwards**. The general
  document picker is A's.
- **The bubble:** a card with a type icon (PDF, DOC, XLS, ZIP, generic), the name, a human size
  from `plain_size`, the page count, and `thumb` if present. Under it, the caption.
  - Notifications and the chat list show "📄 <name>".
- **Download:**
  - **On tap.** Auto-download only up to **2 MiB** on unmetered networks (as §14.7 receiving 7).
  - The verify order is §14.7 receiving 3: size → SHA-256 → every segment → padding.
  - The ciphertext cache and eviction follow the §14.7 rules.
- **Open:**
  - Decrypt to `noBackupFilesDir/open/<random uuid>/<display name>`, then `ACTION_VIEW` through
    `FileProvider` with a read grant and the sanitised mime.
  - The file is deleted when the user returns to the app, after 1 hour, and on the next start.
  - **Received files are never rendered inside RisiMe**: no in-app `PdfRenderer` or WebView on
    untrusted bytes. A file is shown only through `thumb` and the external app.
  - An APK (by `mime` `application/vnd.android.package-archive` or a `.apk` name) has no Open: only
    Save, with "This is an app installer. Only install apps you trust."
- **Save:** MediaStore `Download/RisiMe` on API 29+, and `ACTION_CREATE_DOCUMENT` on 26–28. No
  storage permission.
- **Errors:**
  - `404`: "This file is no longer available".
  - A verify failure: "Couldn't open this file". There is one re-download, then the app gives up.
- **Delete:** the §15 purge covers the `file` row's key, thumb and cache. The `msg:delete`
  `blob_ids` include file blobs (§15.2 "image targets" now reads "media targets": `image`, `file`
  and A's types).

### 33.14 PDF export (phone only)
- **What can be exported:** any Risi `summary`, `report`, `answer`, `digest`,
  `discussion_summary` or `note_card`; a Note (§30, from the note screen); a **calendar view**
  (§29: day, week or agenda, ≤ 31 days); and **My Risi** views (B; reserved as the PdfSource type
  `my_risi`).
  - **Entry point:** ⋮ → "Export PDF" on the card, the note screen and the Calendar screen.
  - **`PdfSource`** (also used on the wire, §33.15):
    `{"type": "message", "conversation_id", "message_id"}` | `{"type": "note", "note_id"}` |
    `{"type": "calendar", "view": "day" | "week" | "agenda", "from": ts, "to": ts}` | (B)
    `{"type": "my_risi", …}`.
- **Generated on the phone only, never on the server.**
  - **The source is always the phone's own data:**
    - the stored message (its `risi` object);
    - `GET /api/v1/risi/notes/{id}` for a note (§30.6), the same data the note screen shows;
    - the synced Risi Calendar (§29.6), plus the phone's own calendar rows when the view shows
      them.
  - No new endpoint and no server rendering.
  - **The generator:** platform `android.graphics.pdf.PdfDocument` with `StaticLayout` (no new
    dependency). If §33.20 gate 4 fails with it (Sinhala or Tamil conjuncts not extractable),
    Android switches to a library, with a `docs/decisions/` note.
- **Fonts, embedded:**
  - **Noto Sans** (Latin), **Noto Sans Sinhala** and **Noto Sans Tamil**, Regular and Bold, as
    static TTFs bundled in the APK (about 1 MB; SIL OFL 1.1, listed under Settings → About →
    Licences).
  - System fonts are never used for Sinhala or Tamil.
  - **Fallback:** on API 29+, `Typeface.CustomFallbackBuilder` (Noto Sans → Sinhala → Tamil →
    system). On API 26–28, the text is split into runs by script (Sinhala U+0D80–U+0DFF; Tamil
    U+0B80–U+0BFF and U+11FC0–U+11FFF) and each run is drawn with its typeface.
  - Tick boxes and bullets are **drawn as vector shapes**, not glyphs.
- **Layout** (A4, 595 × 842 pt, 48 pt side margins):
  - **Every page:** a header with the "RisiMe" wordmark on the left, the title on the right
    (ellipsised) and a hairline. A footer with **"Page n of N"** centred and "Made on this phone
    with RisiMe" on the left (two layout passes).
  - **Page 1:**
    - the **title** (20 pt bold, wrapped);
    - a line with the kind and date ("Risi note · Fri 9 Oct 2026", "Calendar · 12–18 Oct 2026");
    - "Generated <date, time>" (the viewer's locale and zone);
    - **"Participants: Harsha, Shenika, Kamal"** (names as the phone shows them; more than 20 as
      "+n more"; omitted for a calendar or a Risi-chat-only answer);
    - for Risi content, "Written by Risi" plus the §27.1 `made_by` label when present.
  - **Body:** 11 pt, headings 14 pt **bold**, bullets, and tick boxes for items, with owner and
    due.
  - **Content mapping:**
    - Note: key points, then "Agreed", then "Meetings".
    - Summary: the summary, then "Decisions", "Action items" and "Open questions".
    - Report: `sections` (heading and body).
    - Answer: the answer, then "Sources" (names and times only, never message text).
    - Calendar: one heading per day; each event is "10:00–11:00 Title · with …".
  - Text is in the content's own language; headings are localised (§30.9).
  - At most **200 pages**. Above that: "Too long to export; choose a shorter period".
- **File:**
  - The name is `<title> – <yyyy-MM-dd>.pdf`, sanitised as §33.13 and at most 120 graphemes before
    `.pdf`.
  - PDF Info: `Title`, `Producer` = "RisiMe <app version>", `CreationDate`. **No `Author`**, no
    XMP, no ids.
  - Plaintext is written only to `noBackupFilesDir/pdf/<uuid>.pdf`. It is deleted when its action
    completes, after 1 hour, and on the next start.
- **Actions** (a bottom sheet after generation, with a preview of page 1):
  - **[Share]**: §33.10 (the PDF streamed through `ShareProvider`).
  - **[Save to Downloads]**: §33.13 Save.
  - **[Send to chat…]**: the §33.4 target picker (up to 5). The PDF is sent as a **`file`**: one
    encrypt and upload **per target** (§33.5).
- **Privacy:** the PDF and its text never reach the server, the model, the learning log or a push.
  The on-device behaviour log records "pdf exported" with the page count and the source type only.

### 33.15 Risi: "send this as a PDF"
The model **chooses only references**. The phone renders the PDF from its own copy of the source,
so the model never writes or sees the PDF's content. There are two paths, both under §25.
- **Offered** only when the asking device advertises `pdf_export` (§33.1), and the asker has an
  active Risi chat (a personal tool, §25.5).

**Server tool `export_pdf`** (in the `risi_next_action` enum when `authorize/3` allows it). Args:
`{"source": "<ref>", "send_to": "<conversation ref>" | null}`.
- **`source`** is one of:
  - a ref the server handed the model in this turn: an `m<n>` that is a message **from the agent
    leaf**, or an `n<n>` note;
  - `{"calendar": {"view", "from", "to"}}`, at most 31 days.

  The server maps it to a `PdfSource`. Anything else is the step status `failed` ("I couldn't tell
  which item to export"), and the `final` asks.
- **`send_to`** is resolved like `schedule_message`'s `conversation_id` (§26.6): a conversation of
  a chat the asker is an active member of, Private by default, and Official only when the asker
  says so. Never a Risi chat.

**1. Without `send_to`** ("make this a PDF", "send me this as a PDF"):
- The `final` is a server-built `answer`: "Tap **PDF** to create it on your phone." It has **`next_actions`**
  `[{"label": "PDF", "action": "pdf", "source": PdfSource}]`
  (`envelope_risi_answer_next_action_pdf.json`; extends the §25.4 v1.32 list).
- A tap generates the PDF locally and opens the §33.14 sheet. There is no tool call, no timeout,
  and nothing is sent.
- A v1.34 app drops a `pdf` action whose source isn't on the phone, with the toast "This isn't on
  this phone". Older apps drop the unknown action (§25.4).

**2. With `send_to`** ("send this as a PDF to Shenika"): a **write**, so it needs a confirm card
(§25.0).
- **The card:** a `confirm` with `tool: "export_pdf"`, **`buttons: ["send", "cancel"]`**,
  `when: null`, `text` = the PDF title, and the new field **`export: {"source": PdfSource,
  "conversation_id": "<target>"}`** (`envelope_risi_confirm_export_pdf.json`).
  - `summary` example: 'Send "Interview planning · Fri 9 Oct" as a PDF to Shenika (🔒 Private)'.
  - **The server builds** the title and summary **from metadata it already holds**: the note
    topic and date, the report title, the Risi message's kind and date, the calendar range, and
    the chat's display name. It never builds them from model text.
  - It goes to the asker's Risi chat (the audience rule, §25.1).
- **[Send]** sends `confirm_write`. Risi then sends **`risi_tool_call` `export_pdf`**
  (`event_risi_tool_call_export_pdf.json`; the §25.3 transport, 15 s, 16 KiB) to the confirming
  device if it advertises `pdf_export`, else to the turn's device. Args: `{"write_id", "source":
  PdfSource, "conversation_id"}`.
- **The phone accepts it only if:**
  - it has seen, in the Risi chat's MLS history, the agent-leaf `confirm` with this `write_id` and
    `tool: "export_pdf"`;
  - it has seen a `confirm_write` for it from a leaf of its own user;
  - the args equal the card's `export`;
  - the `write_id` is unused.

  Otherwise it answers `declined` (as `calendar_add`, §25.3).
- **Then the phone:**
  1. generates the PDF (§33.14);
  2. persists a `file` outbox row for `conversation_id` (normal send path, its own `client_msg_id`);
  3. uploads and sends;
  4. waits **up to 10 s** for the `msg:send` reply.
- **Result** (`risi_tool_result_export_pdf.json`):
  - `ok` with `{"state": "sent" | "queued", "pages": int}`;
  - `error` with `{"code": "source_unavailable" | "not_member" | "files_not_ready" | "too_large" | "pdf_failed"}`
    (`risi_tool_result_export_pdf_error.json`);
  - or `declined`.
- **Honesty** (the §25.3 v1.32 rule):
  - `sent`: "Sent the PDF to Shenika."
  - `queued`: "The PDF is in your phone's outbox. It goes as soon as your phone is online."
  - errors, in order of the codes above: "I couldn't send it:
    - "the note isn't on this phone";
    - "you're not in that chat any more";
    - "Shenika needs to update RisiMe to receive files";
    - "it's too long for a PDF";
    - "the phone couldn't make the PDF".
  - A timeout is §25.4 `tool_timeout`, with [Retry].
- **The server never sees the PDF.** The tool call's args are references. The result is a state
  and a page count. The learning log keeps the §25.1 hashes only.

### 33.16 What the server sees; privacy; learning log
- **Copy, Share, Star, Info, Reply and PDF:** nothing new. Info reads receipts the server already
  holds; a reply is a normal ciphertext.
- **Forward:** each forwarded item is an ordinary e2ee `msg:send`, indistinguishable from a typed
  message, except by length. MLS application messages aren't padded yet (§14.9, crypto S1), so a
  forwarded text has about the length of its source plus about 20 bytes. That is a weak
  cross-conversation correlation, which already exists for any repeated text.
- **Forwarded media** is a fresh upload with a fresh key per target (§33.5): to the server it is
  an ordinary new `media` upload in that conversation. It can at most guess from timing (a
  download followed by an upload of a similar padded size, §14.9). There is **no new metadata**
  and no forward graph.
- **Private → Official:** after the hint (§33.8), the forwarded item is Official content. Risi
  receives it like any Official message (the MLS key included for media), under §24.12.
- **Learning log:** no model is involved, except the `export_pdf` turn steps (§25.1 hashes only).
  The on-device behaviour log records counts only: forwarded n items to m chats; copied n; shared
  n; pdf pages. It never records content, names or ids.

### 33.17 Amendments
- **§10.3:** `text` gains the optional `forwarded` and `reply_to`. The new type is `file`.
- **§14.4:** `image` gains the optional `forwarded` and `reply_to`.
- **§14.7 receiving 8:** Share (§33.10) and Open-with (§33.13) are further paths by which plaintext
  leaves the app. Copy of an image copies the caption (unchanged).
- **§15.2:** `blob_ids` covers **media targets** (`image`, `file`, A's types), not only images.
- **§15.6:** star rows, `forward_hops`, `reply_to_message_id` and opened-file temporaries are
  derived rows that the purge removes. The quote rule is now normative (§33.9).
- **§17.7:** the export filter `kind` `text` or `image` becomes `text`, `image` or `file`.
- **§22.5:** `payload` may be `file` (by reference, like `image`; full files are not in backups).
  The `message` line gains `starred_at`.
- **§24.9:** the forward picker lists tabs as separate targets. The Private → Official hint is
  §33.8.
- **§25.3:** a new client tool `export_pdf` (§33.15).
- **§25.4:** `confirm` gains `tool: "export_pdf"`, `buttons: ["send", "cancel"]`, `when: null`
  and `export`. `next_actions` gains `action: "pdf"`.
- **§30.6:** "Share into a chat" uses the forward flow (§33.4: a `text` with `forwarded`) or the PDF
  (§33.14).

### 33.18 Rollout and old apps
- **Server first** (`files` readiness, the `pdf_export` filter, `export_pdf`, the examples),
  **then the app.** No `required` update (decision 016).
- **Pre-v1.34 apps:**
  - They show forwarded messages and replies as plain messages, without the label or quote.
  - They ignore the `file` type and store nothing visible (§10.3). DMs therefore never send them
    one (`files_ready`), and groups show the §33.1 notice.
  - They never get the `export_pdf` tool or the `pdf` chip.
- **Upgrade gate (hard rule 9):** the Room migration only creates `stars` and adds nullable
  columns (`forward_hops`, `reply_to_message_id`, `delivered_at`, `read_at`). Per-conversation
  counts (1:1, groups, photos, call records) are identical before and after the update.

### 33.19 Examples (`contract/v1/examples/`)

| File | Content |
|---|---|
| `envelope_text_forwarded.json` | `{"v":1,"type":"text","body":"Site visit moved to 3 PM","forwarded":{"hops":1}}` |
| `envelope_text_forwarded_many.json` | `{"v":1,"type":"text","body":"Site visit moved to 3 PM","forwarded":{"hops":7}}` |
| `envelope_text_forwarded_bad.json` | `{"v":1,"type":"text","body":"Site visit moved to 3 PM","forwarded":{"hops":0}}`: shown **without** a label, not dropped |
| `envelope_text_reply.json` | `{"v":1,"type":"text","body":"Yes, 3 works","reply_to":{"message_id":"c1a2b3e1-a0b1-11f0-8000-0242ac120002","from":"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"}}` |
| `image_payload_forwarded.json` | `image_payload.json` with a **new** `blob` and `enc` (the per-target re-upload, §33.5) plus `"forwarded":{"hops":2}` |
| `envelope_text_view_once_reserved.json` | `{"v":1,"type":"text","body":"Door code 4711","view_once":{}}`: shown (F defines the rendering) but never selectable, quotable, searchable or forwardable (§33.0) |
| `file_payload.json` | as §33.13 (below) |
| `file_payload_bad_name.json` | `file_payload.json` with `"name":"../../evil.pdf"`: **dropped** |
| `file_payload_parts.json` | `{"v":1,"type":"file","parts":[{"blob_id","size":16777216,"sha256"}, …],"enc":{"alg":"A256GCM-S64K","key","plain_size":30000000},"name":"Site survey.zip","mime":"application/zip"}`: a placeholder bubble, not dropped (A reserves `parts`) |
| `device_put_files.json` | `device_put_risi_notes.json` with `"files"` and `"pdf_export"` added to `capabilities`, `app_version` `0.3.0-nightly.50` |
| `mls_group_files_ready.json` | `mls_group_images_ready.json` plus `"files_ready":false,"missing_files":[{"user_id":"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e","device_id":"c0a80101-0000-4000-8000-000000000004"}]` |
| `envelope_risi_answer_next_action_pdf.json` | below |
| `envelope_risi_confirm_export_pdf.json` | below |
| `event_risi_tool_call_export_pdf.json` | below |
| `risi_tool_result_export_pdf.json` | `{"status":"ok","result":{"state":"sent","pages":3}}` |
| `risi_tool_result_export_pdf_error.json` | `{"status":"error","result":{"code":"files_not_ready"}}` |
| `backup_entry_message_v134.json` | below |
| `copy_format_cases.json` (in `contract/v1/`, a fixture, not an example) | §33.3 |

`file_payload.json`:
```json
{"v":1,"type":"file","blob":{"blob_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","size":196656,"sha256":"OmRGrwNuM4bzkILNWQT16tHm5bpZDP+uBAMD+z2MNfI="},"enc":{"alg":"A256GCM-S64K","key":"usaTFgbKkrHupsKDgiEilO6aTvojlqaCfF8jyJ2PAII=","plain_size":196608},"name":"Interview planning – 2026-10-09.pdf","mime":"application/pdf","thumb":{"mime":"image/jpeg","w":91,"h":128,"data":"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/Z"},"pages":3,"caption":"Notes from Friday"}
```
(`blob.size` is the §14.3 `cipher_size` of `plain_size`: Padmé(196 608) = 196 608, `n` = 3,
196 608 + 3·16 = 196 656.)

`envelope_risi_answer_next_action_pdf.json`:
```json
{"v":1,"type":"text","body":"Tap PDF to create it on your phone.","risi":{"v":1,"kind":"answer","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","answer":"Tap PDF to create it on your phone.","refs":[],"confidence":1.0,"steps":[{"tool":"export_pdf","status":"ok"}],"sources":[],"next_steps":[],"next_actions":[{"label":"PDF","action":"pdf","source":{"type":"note","note_id":"1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"}}],"local_search":null,"turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":"7e2a3f4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b","notify":["7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"]}}
```

`envelope_risi_confirm_export_pdf.json`:
```json
{"v":1,"type":"text","body":"Send \"Interview planning · Fri 9 Oct\" as a PDF to Shenika (Private)? Update RisiMe to answer.","risi":{"v":1,"kind":"confirm","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","write_id":"2b3c4d5e-6f70-4812-93a4-b5c6d7e8f901","tool":"export_pdf","summary":"Send \"Interview planning · Fri 9 Oct\" as a PDF to Shenika (🔒 Private)","when":null,"text":"Interview planning · Fri 9 Oct","export":{"source":{"type":"note","note_id":"1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"},"conversation_id":"dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"},"for":["7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"],"buttons":["send","cancel"],"expires_at":"2026-10-12T09:15:30.456Z","turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":"7e2a3f4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b","notify":["7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"]}}
```

`event_risi_tool_call_export_pdf.json`:
```json
{"event_id":"e5b2c3d4-a5b0-11f1-8000-0242ac120002","kind":"risi_tool_call","data":{"tool_call_id":"4c5d6e7f-8a9b-4c0d-9e1f-2a3b4c5d6e7f","turn_id":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","conversation_id":"grp:2f3a4b5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c","device_id":"c0a80101-0000-4000-8000-000000000001","tool":"export_pdf","args":{"write_id":"2b3c4d5e-6f70-4812-93a4-b5c6d7e8f901","source":{"type":"note","note_id":"1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"},"conversation_id":"dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"},"undo_entry_id":null,"expires_at":"2026-10-11T09:20:12.000Z","to_devices":["c0a80101-0000-4000-8000-000000000001"],"server_ts":"2026-10-11T09:18:12.000Z"}}
```

`backup_entry_message_v134.json`:
```json
{"type":"message","conversation_id":"grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d","message_id":"c1a2b3f0-a0b1-11f0-8000-0242ac120002","client_msg_id":"a4b5c6d7-e8f9-4a0b-9c1d-2e3f4a5b6c7e","from":"7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","from_device":"c0a80101-0000-4000-8000-000000000001","server_ts":"2026-10-11T08:15:30.456Z","payload":{"v":1,"type":"file","blob":{"blob_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","size":196656,"sha256":"OmRGrwNuM4bzkILNWQT16tHm5bpZDP+uBAMD+z2MNfI="},"enc":{"alg":"A256GCM-S64K","key":"usaTFgbKkrHupsKDgiEilO6aTvojlqaCfF8jyJ2PAII=","plain_size":196608},"name":"Interview planning – 2026-10-09.pdf","mime":"application/pdf","thumb":null,"pages":3,"forwarded":{"hops":1}},"origin":null,"shared_by":null,"status":"delivered","starred_at":"2026-10-11T08:20:00.000Z"}
```

### 33.20 Gate (all roles; NEXT-PHASE E gate first)
**NEXT-PHASE E gate (blocking):**
1. **Copy format:** an Android unit test runs every `copy_format_cases.json` case. On the
   two-phone Redroid (`scripts/ui-entry-test --basic-messaging`, en-GB, 24 h, Asia/Colombo), the
   test multi-selects text + photo-with-caption + text and reads the clipboard
   (`cmd clipboard` / a debug seam). It must equal the expected 3 lines exactly.
2. **Forward to a DM and a group:**
   - On phone A, forward a text and a photo from DM(A,B) to DM(A,C) and to a group with B. Both
     arrive, and the photo opens on the receiving phone.
   - Server check: **one new `media` upload per target** for the photo, each in its target
     conversation, and the two forwarded envelopes carry different `enc.key`s (checked on the
     receiving phones' debug dump).
3. **The "Forwarded" label on the second phone** (B, in the group). Then B forwards it on; after
   the chain reaches `hops` 5 (a debug seam may inject `hops` 4), **"Forwarded many times"** shows,
   and the picker allows 1 target.
4. **A Sinhala and a Tamil PDF:**
   - Export a note with Sinhala content and one with Tamil content (fake-llm scripted
     `discussion_summary`), and pull both PDFs (debug seam: copy to `/sdcard/Download/RisiMe`).
   - `pdffonts` lists NotoSansSinhala, NotoSansTamil and NotoSans with `emb yes`.
   - `pdftotext -enc UTF-8` output, after NFC and removing U+200C/U+200D and whitespace runs,
     contains the expected strings: the topic, a key point, and an item text in each script,
     including conjuncts (`ශ්‍රී ලංකා`, `க்ஷ`), plus "RisiMe" and "Page 1 of".

**Further gates:**

5. **Expired source:** a photo past its horizon that the phone never downloaded is disabled for
   Forward with "This photo is no longer available"; one that was downloaded is still forwarded
   (a fresh upload). (Android test with a clock seam.)
6. **Delete independence:** the original sender deletes the photo for everyone in DM(A,B); the
   forwarded copy in the group still opens (it is its own blob). A delete for everyone of the
   forwarded message removes **its** blob (§15.2 `blob_ids`, `file` included).
7. **Private → Official hint:** forward from Private to an Official tab. The hint shows once. A
   second forward shows no hint.
8. **Reply:**
   - B sees the quote.
   - A deletes the original for everyone, and B's quote turns into "This message was deleted".
   - A reply to a message B doesn't hold shows "Original message isn't on this phone" (a unit
     test).
9. **Star, Info, Share:**
   - Star 2 messages and restart. Starred shows them, and a backup round trip keeps them.
   - Info on a DM message shows Delivered and Read times. On a group message it lists members
     and **not Risi**.
   - Share a photo: the chooser intent is captured by a test receiver with the right mime and a
     readable stream, and **no file** is left under the app's `cache/` and `files/` (`run-as …
     find`).
10. **Risi "send this as a PDF to <B>":**
    - fake-llm scripts `export_pdf` with `send_to`;
    - the confirm card shows; tap [Send];
    - B receives a `file` "….pdf" that opens externally;
    - Risi says "Sent the PDF to B" only after the result `sent`.
    - With phone A offline, Risi says the `queued` sentence. A `declined` with a forged
      `write_id` is a unit test.
11. **No plaintext on the server:**
    - `CANARY-<uuid>` is in a Private text that is forwarded Private → Private, and in a PDF's
      content and file name.
    - The canary appears in no server table, log or blob file (ciphertext only), and in no
      push. The §24.14 canary also covers `export_pdf` tool args (references only).
12. **View once:** a unit test feeds a synthetic envelope with `"view_once": {}` and checks that
    it is not selectable, not quotable, not searchable and never built into a forward; F's gate
    repeats it with real items once F lands.
13. **The upgrade gate** (hard rule 9): counts are unchanged from v1.33 to v1.34. Also the logcat
    gate, and ui-entry-test at font scale 1.0 and 1.3 with screenshots of the selection bar, the
    forward picker, the hint, Info, the file bubble and the PDF sheet.
14. **Server tests:**
    - `msg:delete` `blob_ids` accepting `file` blobs;
    - the `files_ready` computation;
    - the `pdf_export` capability being dropped without `risi_tools`;
    - `export_pdf` authorisation (offered only to `pdf_export` devices), the confirm card built
      from metadata (no model text), the tool-call routing to the confirming device, strict
      result validation, and the honesty sentences;
    - every new example parsed or produced exactly.
15. **Android tests:**
    - the forward builder: strips `risi`, `reply_to` and unknown fields; hops +1, the own-message
      rule and the cap;
    - `forwarded` and `reply_to` validation;
    - `file` validation (bad name dropped, `parts` placeholder, mime fallback, APK rule);
    - the selection-bar matrix; the target rules (5/1, disabled reasons);
    - outbox persistence of a 30 × 5 forward and resume after a kill;
    - the re-upload state machine (§33.5: cached, download-first, own unsent, expired, quota);
    - the `ShareProvider` pipe (no disk writes);
    - the Room migration and its counts; purging the star rows;
    - the PDF generator on API 26 and 34 (the run splitting versus `CustomFallbackBuilder`).

### Decisions (root, 2026-10-11, on the draft's open questions)
1. No blob grants in v1.34; media forwards always re-upload with a fresh key (§33.5, §33.6).
2. Star is device-local and kept in backups; own-device sync later (§33.11).
3. Copy dates follow the phone locale (§33.3).
4. "Forwarded many times" → one target only (§33.4).
5. The user's own forwards carry no label (§33.4).
6. PDF Sinhala/Tamil extraction: android checks the platform writer first; if `pdftotext` fails,
   a library plus a `docs/decisions/` note (§33.14).
7. No general file picker until A (§33.13).
8. No forward to Risi (§33.4).
9. Only Private copies are marked sensitive (§33.3).
10. The view-once marker name `view_once` is reserved for F (§33.0).
