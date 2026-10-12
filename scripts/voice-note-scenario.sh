# shellcheck shell=bash
# Sourced by scripts/call-device-test (CALLTEST_EXTRA) for scripts/voice-note-test (android-added gate, v1.41 §37):
# every helper of call-device-test is available (A, DEV, tap, has, wait_for, dump, pass, fail, official_on,
# open_chat, logcat_ok, $WORK, $PORT, $SUF, $REPO, $M, $PKG, $CLS). Names: "ZZ Call A" / "ZZ Call B".

VNSHOTS="$REPO/docs/status/screens"; mkdir -p "$VNSHOTS"
warn() { echo "CALLTEST WARN $*"; }
vshot() { A exec-out screencap -p > "$VNSHOTS/67-voice-$1.png" 2>/dev/null || true; }
slog_mark() { wc -l < "$WORK/server.log"; }
since() { tail -n +"$(( $1 + 1 ))" "$WORK/server.log"; }
touch "$WORK/vn-marker"
GROUP="ZZ Voice group"

# ---------------------------------------------------------------- 1. the server with §37.6 on
umask 077
WKEY="$(grep -E '^WHISPER_API_KEY=' "$REPO/.env" 2>/dev/null | tail -1 | cut -d= -f2-)"
[ -n "$WKEY" ] || fail "voice: WHISPER_API_KEY missing from .env"
{ echo "RISI_VOICE_TRANSCRIBE=on"; echo "RISI_LEDGER=on"; echo "WHISPER_API_KEY=$WKEY"; echo "RISI_SPEECH_URL=http://127.0.0.1:8200"; } >> "$WORK/risi.env"
unset WKEY
umask 022
echo "==> restarting the temp server with RISI_VOICE_TRANSCRIBE=on"
tmux kill-session -t "risime-calltest$SUF" 2>/dev/null || true
for _ in $(seq 1 30); do ss -ltnH "sport = :$PORT" | grep -q . || break; sleep 1; done
tmux new -d -s "risime-calltest$SUF" "cd '$REPO/server' && set -a && . '$WORK/risi.env' && set +a && MIX_BUILD_PATH='$MIX_BUILD_PATH' RISIME_CODE_RELOADER=false RISIME_DEV_DB=$RISIME_DEV_DB RISIME_DEV_KEYSPACE=$RISIME_DEV_KEYSPACE ERL_EPMD_ADDRESS=127.0.0.1 \
  PORT=$PORT DEV_LOCAL_AUTH=true OIDC_ENABLED=false OTP_DEV_LOG=true PHONE_VERIFICATION=off FCM_ENABLED=false LOG_FORMAT=text \
  ATTESTATION_KEY_FILE='$WORK/attest.jwk' RISIME_AUTH_LOG='$WORK/auth.log' $M mix phx.server >> '$WORK/server.log' 2>&1"
for _ in $(seq 1 120); do curl -sf -o /dev/null "http://127.0.0.1:$PORT/health" && break; sleep 1; done
ok=""; for _ in $(seq 1 60); do curl -sf "http://127.0.0.1:$PORT/api/v1/auth/config" | grep -q '"voice_transcribe":"on"' && { ok=1; break; }; sleep 2; done
[ -n "$ok" ] || fail "voice: /auth/config never said voice_transcribe: on (speech service healthy? Risi up?)"
pass "voice: the temp server says voice_transcribe: on"

# ---------------------------------------------------------------- 2. the clip, served to the phones (memory only on the phone)
mkdir -p "$WORK/clips"
gst-launch-1.0 -q filesrc location="$REPO/android/testdata/transcribe/en-tts.ogg" ! oggdemux ! opusdec ! audioconvert ! audioresample \
  ! "audio/x-raw,format=S16LE,rate=48000,channels=1" ! wavenc ! filesink location="$WORK/clips/en.wav" || fail "voice: decode the clip"
tmux kill-session -t "risime-calltest-clips$SUF" 2>/dev/null || true
tmux new -d -s "risime-calltest-clips$SUF" "python3 -I -m http.server $VN_CLIP_PORT --bind 127.0.0.1 --directory '$WORK/clips' > '$WORK/clips.out' 2>&1"
trap 'tmux kill-session -t "risime-calltest-clips$SUF" 2>/dev/null || true; cleanup' EXIT
for _ in $(seq 1 20); do curl -sf -o /dev/null "http://127.0.0.1:$VN_CLIP_PORT/en.wav" && break; sleep 0.5; done
# The apps re-read /auth/config (voice_transcribe) and advertise `voice` after a restart.
for DEV in A B; do
  A shell am force-stop "$PKG"; A shell am start -W -n "$PKG/$CLS.MainActivity" >/dev/null; sleep 3
  A reverse "tcp:$VN_CLIP_PORT" "tcp:$VN_CLIP_PORT" >/dev/null
  A shell am broadcast -a ai.risicloud.risime.debug.VOICE_INJECT -p "$PKG" --es url "http://127.0.0.1:$VN_CLIP_PORT/en.wav" >/dev/null
  ok=""; for _ in $(seq 1 20); do A logcat -d 2>/dev/null | grep -q "RisiMe debug: voice inject armed" && { ok=1; break; }; sleep 1; done
  [ -n "$ok" ] || fail "voice: $DEV's microphone seam was not armed (debug build?)"
done
pass "voice: the clip replaces both phones' microphones (debug seam)"

# ---------------------------------------------------------------- helpers
# Centre "x y" of the LAST node whose text or content-desc matches the regex $1 (after a dump).
node_at() {
  python3 -I - "$WORK/ui-$DEV.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[2]); hit = None
for n in ET.parse(sys.argv[1]).iter('node'):
    if pat.search(n.get('content-desc') or '') or pat.search(n.get('text') or ''):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds'))); hit = ((x1 + x2) // 2, (y1 + y2) // 2)
if hit: print(*hit)
PY
}
count_nodes() { python3 -I - "$WORK/ui-$DEV.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[2])
print(sum(1 for n in ET.parse(sys.argv[1]).iter('node') if pat.search(n.get('content-desc') or '') or pat.search(n.get('text') or '')))
PY
}
wait_node() {  # <regex> [seconds]
  for _ in $(seq 1 "${2:-30}"); do dump; [ "$(count_nodes "$1")" -gt 0 ] && return 0; sleep 1; done
  echo "    [$DEV] timeout waiting for /$1/"; return 1
}
# Hold the composer's mic for $1 seconds (a long press in place: hold-to-record, release to send).
record() {
  hide_keyboard; dump
  local p; p="$(node_at '^Record voice note$')"
  [ -n "$p" ] || { echo "    [$DEV] no mic button"; return 1; }
  # shellcheck disable=SC2086
  A shell input swipe $p $p "$(( $1 * 1000 ))"
}
# Slide up to lock, then Send (hands-free).
record_locked() {
  hide_keyboard; dump
  local p x y; p="$(node_at '^Record voice note$')"; [ -n "$p" ] || return 1
  read -r x y <<<"$p"
  A shell input swipe "$x" "$y" "$x" "$(( y - 400 ))" 600
  wait_node '^Send voice note$' 10 || return 1
  vshot "${2:-locked}"
  sleep "$1"
  tap "Send voice note"
}
# The debug seam's positions of the voice bubbles on screen: "<what> <id> <conv> <in|out> <ts> <x> <y>" lines.
spots() {
  local n; n="$(A logcat -d 2>/dev/null | grep -c 'RisiMe debug: voice ui end' || true)"
  A shell am broadcast -a ai.risicloud.risime.debug.VOICE_INJECT -p "$PKG" --ez where true >/dev/null
  for _ in $(seq 1 10); do [ "$(A logcat -d 2>/dev/null | grep -c 'RisiMe debug: voice ui end' || true)" -gt "$n" ] && break; sleep 0.5; done
  A logcat -d 2>/dev/null | awk '/RisiMe debug: voice ui end/{last=blk; blk=""; next} /RisiMe debug: voice ui /{sub(/.*RisiMe debug: voice ui /, ""); blk=blk $0 "\n"} END{printf "%s", last}'
}
# Tap the play button (or the speed pill) of the newest incoming note on screen.
tap_spot() {  # <play|speed> [in|out]
  local s; s="$(spots | awk -v w="$1" -v d="${2:-in}" '$1 == w && $4 == d' | sort -k5,5n | tail -1)"
  [ -n "$s" ] || { echo "    [$DEV] no $1 spot"; return 1; }
  # shellcheck disable=SC2086
  A shell input tap $(awk '{print $6, $7}' <<<"$s")
}
long_press() {  # <regex of the bubble's content-desc>
  dump; local p; p="$(node_at "$1")"; [ -n "$p" ] || return 1
  # shellcheck disable=SC2086
  A shell input swipe $p $p 900
}
voice_logged() { A logcat -d 2>/dev/null | grep -cE "RisiMe *: voice: $1" || true; }
group_open() {  # the group's chat on $DEV, on tab $1 (private|official)
  open_chat "$GROUP" || return 1
  if [ "$1" = official ]; then
    tap "● Official" || return 1
    for _ in $(seq 1 90); do
      dump; grep -qF 'Risi listens in Official' "$WORK/ui-$DEV.xml" && { tap "OK" || true; sleep 1; }
      has 'text="Start Official"' && { tap "Start Official" || true; sleep 2; }
      has 'Risi is listening' && has 'Record voice note' && return 0
      sleep 1
    done
    return 1
  fi
  for _ in 1 2 3 4 5; do
    dump; grep -qF 'Risi listens in Official' "$WORK/ui-$DEV.xml" && { tap "OK" || true; sleep 1; }
    has 'Risi is listening' || { has 'Record voice note' && return 0; }
    tap "🔒 Private" >/dev/null || true; sleep 2
  done
  return 1
}
dm_private() {  # the 1:1 chat with $1 on its Private tab, mic ready
  open_chat "$1" || return 1
  for _ in 1 2 3 4 5; do
    has 'Risi is listening' && { tap "🔒 Private" || true; sleep 2; continue; }
    wait_node '^Record voice note$' 30 && return 0
  done
  return 1
}

# ---------------------------------------------------------------- 3. 1:1 Private: send, receive, play at 2x, blue mic
DEV=B; dm_private "ZZ Call A" || fail "voice: B has no mic in the 1:1 (voice_ready?)"
DEV=A; dm_private "ZZ Call B" || fail "voice: A has no mic in the 1:1 (voice_ready?)"
vshot dm-composer-mic
mark="$(slog_mark)"
record 4 || fail "voice: A can't hold the mic"
wait_node '^You: Voice note, 0:0[3-5]' 30 || fail "voice: A's note isn't in the 1:1"
wait_node '^You: Voice note, 0:0[3-5].*(Sent|Delivered|Read)' 60 || fail "voice: A's note never went out (upload/send)"
since "$mark" | grep -qF 'INSERT INTO "blobs"' || fail "voice: no blob upload for the note"
DEV=B; wait_node '^Voice note, 0:0[3-5], not played yet' 60 || fail "voice: B never received A's note"
vshot dm-received
tap_spot play in || fail "voice: B can't tap play"
sleep 1; tap_spot speed in || fail "voice: B has no speed pill while playing"
sleep 0.5; tap_spot speed in || fail "voice: B's second speed tap"
ok=""; for _ in $(seq 1 10); do [ "$(voice_logged 'speed 2×')" -gt 0 ] && { ok=1; break; }; sleep 0.5; done
[ -n "$ok" ] || fail "voice: B never reached 2x"
[ "$(voice_logged 'play ')" -gt 0 ] || fail "voice: B's player never started"
vshot dm-playing-2x
A logcat -d 2>/dev/null | grep -qE "RisiMe: voice: cannot play" && fail "voice: B could not play the note (decoder)"
pass "voice: 1:1 Private: A's note reached B, B played it at 2x"
DEV=A; wait_node '^You: Voice note, 0:0[3-5], played' 60 || fail "voice: A's mic never turned blue (played receipt)"
vshot dm-played-blue-mic
DEV=B; dump; [ "$(count_nodes 'not played yet')" = 0 ] || warn "voice: B still shows the unplayed dot"
pass "voice: the played receipt (blue mic) reached the sender"

# ---------------------------------------------------------------- 4. reply-to (B answers A's note with a note)
DEV=B; long_press '^Voice note, 0:0[3-5]' || fail "voice: B can't long-press A's note"
wait_for "Reply" 10 && tap "Reply" || fail "voice: no Reply on a voice note"
sleep 1; record 3 || fail "voice: B can't record the reply"
DEV=A; wait_node 'Replying to You.*Voice note|Voice note.*Replying to You' 60 || fail "voice: A never got B's reply quoting the note"
vshot reply
pass "voice: reply-to: B's voice note quotes A's"

# ---------------------------------------------------------------- 5. a group (A + B): Private and Official
DEV=A; back_to_list || true
for _ in $(seq 1 30); do
  dump
  if grep -qE '(text|content-desc)="New"' "$WORK/ui-$DEV.xml" && ! grep -qE '(text|content-desc)="New group"' "$WORK/ui-$DEV.xml"; then tap "New" || true; sleep 2; fi
  tap "New group" && break; sleep 2; back_to_list || true
done
wait_for "Add members" 15 || fail "voice: no New group"
sleep 2; tap "ZZ Call B" && tap "Next" && sleep 1 && type_into "Group name" "$GROUP" && hide_keyboard && tap "Create" || fail "voice: group creation"
ok=""; for _ in $(seq 1 120); do
  dump; grep -qF 'Risi listens in Official' "$WORK/ui-$DEV.xml" && { tap "OK" || true; }
  grep -qF 'Group info' "$WORK/ui-$DEV.xml" && { A shell input keyevent KEYCODE_BACK; sleep 1; continue; }
  group_open private >/dev/null 2>&1 && { ok=1; break; }
  sleep 1
done
[ -n "$ok" ] || fail "voice: the group's e2ee composer never showed a mic on A"
DEV=B; group_open private || fail "voice: B can't open the group (Welcome?)"
DEV=A; mark="$(slog_mark)"
record_locked 4 group-locked || fail "voice: A can't record locked (slide up) in the group"
DEV=B; wait_node '^(ZZ Call A: )?Voice note, [0-9]+:[0-9]{2}, not played yet' 60 || fail "voice: B never got A's group note"
tap_spot play in || fail "voice: B can't play the group note"
sleep 5
DEV=A; wait_node '^You: Voice note, [0-9]+:[0-9]{2}, played' 60 || fail "voice: the group note's mic never turned blue (all_played)"
vshot group-played
for DEV in A B; do dump; grep -qE 'text="(Transcript|Couldn.t transcribe)"' "$WORK/ui-$DEV.xml" && fail "voice: $DEV shows a transcript in Private"; done
sleep 5
since "$mark" | grep -qE 'Risi voice|voice_transcript' && fail "voice: the server ran a Risi voice path for a Private group note"
pass "voice: group Private: sent locked, received, played, blue mic; no transcript and no Risi path"

# Official: the one-time note, the note, Risi's transcript under it on both phones.
DEV=A; group_open official || fail "voice: A can't start Official for the group"
DEV=B; group_open official || fail "voice: B can't open the group's Official tab"
DEV=A; dump; p="$(node_at '^Record voice note$')"
# The Official tab just started: the mic answers with the §37.1 notice until the chat is voice_ready (seconds).
ok=""; for _ in $(seq 1 8); do
  # shellcheck disable=SC2086
  A shell input tap $p
  wait_node 'Risi transcribes voice notes in Official chats' 6 && { ok=1; break; }
  sleep 4
done
[ -n "$ok" ] || fail "voice: no one-time Official note before the first Official voice note"
vshot official-note
tap "OK" || true; sleep 1
record 5 || fail "voice: A can't record in Official"
DEV=B; wait_node '^(ZZ Call A: )?Voice note, [0-9]+:[0-9]{2}' 60 || fail "voice: B never got the Official note"
ok=""; for _ in $(seq 1 60); do dump; grep -qE 'text="(Transcript|Couldn.t transcribe)"' "$WORK/ui-$DEV.xml" && { ok=1; break; }; sleep 2; done
[ -n "$ok" ] || fail "voice: no Risi transcript under the Official note on B within 2 min"
if has 'text="Transcript"'; then
  tap "Transcript" || true; sleep 1
  dump; grep -qiE 'text="[^"]*(office|quote|friday|revised|meet|the)[^"]*"' "$WORK/ui-$DEV.xml" || warn "voice: the expanded transcript has none of the clip's English words"
else
  warn "voice: Risi said \"Couldn't transcribe\" (speech-service quality, not the phone)"
fi
vshot official-transcript
DEV=A; wait_node '^(Transcript|Couldn.t transcribe)$' 30 || true
[ "$(count_nodes '^(Transcript|Couldn.t transcribe)$')" -gt 0 ] || fail "voice: the sender doesn't see the transcript under the note"
dump; grep -qiE 'whisper|faster-whisper|large-v3' "$WORK/ui-$DEV.xml" && fail "voice: a model name is shown to users"
pass "voice: group Official: the one-time note, the note, Risi's transcript collapsed under it on both phones"

# 1:1 Official: a note there too, with its transcript.
DEV=A; official_on "ZZ Call B" || fail "voice: A can't open the 1:1's Official tab"
DEV=B; official_on "ZZ Call A" || fail "voice: B can't open the 1:1's Official tab"
DEV=A; record 4 || fail "voice: A can't record in the 1:1's Official tab"
DEV=B; wait_node '^(ZZ Call A: )?Voice note, [0-9]+:[0-9]{2}' 60 || fail "voice: B never got the 1:1 Official note"
ok=""; for _ in $(seq 1 60); do dump; grep -qE 'text="(Transcript|Couldn.t transcribe)"' "$WORK/ui-$DEV.xml" && { ok=1; break; }; sleep 2; done
[ -n "$ok" ] || fail "voice: no transcript under the 1:1 Official note"
vshot dm-official-transcript
pass "voice: 1:1 Official: the note and its transcript"

# ---------------------------------------------------------------- 6. forward (a fresh upload into the group)
DEV=A; dm_private "ZZ Call B" || fail "voice: A back to the 1:1"
mark="$(slog_mark)"
long_press 'Voice note.*Replying to You|Replying to You.*Voice note' || long_press '^Voice note, 0:0' || fail "voice: A can't long-press B's note"
wait_for "Forward" 10 && tap "Forward" || fail "voice: no Forward on a voice note"
wait_for "Forward to…" 15 || fail "voice: no forward picker"
tap "$GROUP" || tap "$GROUP*" || fail "voice: the group isn't a forward target"
sleep 1; tap "Send" || fail "voice: no Send in the forward picker"
sleep 2; has "Forward to Official?" && { tap "Forward" || true; }
ok=""
for tab in private official; do
  DEV=B; group_open "$tab" || continue
  wait_node '^(ZZ Call A: )?Forwarded, Voice note' 40 && { ok=1; break; }
done
[ -n "$ok" ] || fail "voice: B never got the forwarded note"
vshot forwarded
since "$mark" | grep -qF 'INSERT INTO "blobs"' || fail "voice: the forward made no fresh upload"
pass "voice: forward: a fresh upload, \"Forwarded\" on B"

# ---------------------------------------------------------------- 7. the server holds ciphertext only
cat > "$WORK/vn-blobs.exs" <<'EXS'
import Ecto.Query
for id <- RisiMe.Repo.all(from b in "blobs", where: b.purpose == "media", select: fragment("?::text", b.id)) do
  IO.puts("vnblob: " <> Path.join([RisiMe.Blobs.blob_dir(), String.slice(id, 0, 2), id]))
end
EXS
mapfile -t BLOBS < <(cd server && $M mix run "$WORK/vn-blobs.exs" 2>/dev/null | sed -n 's/^vnblob: //p' || true)
[ "${#BLOBS[@]}" -ge 6 ] || fail "voice: only ${#BLOBS[@]} media blobs on the server (expected the notes)"
checked=0
for f in "${BLOBS[@]}"; do
  [ -f "$f" ] || continue
  checked=$((checked + 1))
  python3 -I - "$f" <<'PY' || fail "voice: plaintext audio in the blob store: $f"
import sys
b = open(sys.argv[1], "rb").read()
bad = [m for m in (b"OggS", b"OpusHead", b"OpusTags", b"ftypM4A", b"RIFF") if m in b]
sys.exit(1 if bad else 0)
PY
  if timeout 10 gst-launch-1.0 -q filesrc location="$f" ! oggdemux ! opusdec ! fakesink >/dev/null 2>&1; then fail "voice: a stored blob decodes as Ogg/Opus: $f"; fi
done
[ "$checked" -ge 6 ] || fail "voice: only $checked blob files found on disk"
grep -aqE 'OggS|OpusHead' "$WORK/server.log" && fail "voice: Ogg/Opus bytes in the server log"
srv="$(find /tmp "$REPO/server/tmp" -maxdepth 3 -newer "$WORK/vn-marker" \( -name '*.ogg' -o -name '*.opus' -o -name '*.wav' -o -name 'plug-*' \) 2>/dev/null | grep -v "$WORK/clips" || true)"
[ -z "$srv" ] || fail "voice: server-side audio/temp files: $srv"
wc="$(docker ps --format '{{.Names}}' | grep -m1 whisper || true)"
if [ -n "$wc" ]; then
  aud="$(docker diff "$wc" 2>/dev/null | grep -E '\.(ogg|opus|wav|webm|mp3|flac)$' || true)"
  [ -z "$aud" ] || fail "voice: audio files in the speech container: $aud"
fi
pass "voice: $checked stored media blobs are ciphertext (no Ogg/Opus magic, not decodable); no plaintext audio in the server log, temp dirs or the speech container"

# ---------------------------------------------------------------- 8. the phones keep only ciphertext
for DEV in A B; do
  left=""
  for e in ogg opus wav pcm m4a aac 3gp plain; do left="$left$(A shell "run-as $PKG find . -type f -name '*.$e'" 2>/dev/null | tr -d '\r')"; done
  while read -r f; do
    [ -n "$f" ] || continue
    h="$(A shell "run-as $PKG head -c 4 '$f'" 2>/dev/null | tr -d '\r' || true)"
    case "$h" in OggS|RIFF) left="$left $f";; esac
  done < <(A shell "run-as $PKG find . -type f -newer files/debug_keep_call_controls -size +4k" 2>/dev/null | tr -d '\r')
  [ -z "$left" ] || fail "voice: audio files left on $DEV: $left"
  n="$(A shell "run-as $PKG find no_backup/media -maxdepth 1 -type f -name '*.enc'" 2>/dev/null | tr -d '\r' | grep -c . || true)"
  [ "$n" -ge 2 ] || warn "voice: only $n .enc files in $DEV's media cache"
done
pass "voice: no audio file on either phone (only .enc ciphertext under noBackupFilesDir/media)"
logcat_ok "voice"
pass "voice: §37 device gate"
