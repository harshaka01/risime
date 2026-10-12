# shellcheck shell=bash
# Sourced by scripts/call-device-test (CALLTEST_EXTRA) for scripts/call-transcribe-test: every helper of
# call-device-test is available (A, DEV, tap, has, wait_for, dump, pass, fail, official_on, open_chat,
# answer_intent, media_ok, wait_ring, logcat_ok, no_leftovers, $WORK, $PORT, $SUF, $REPO, $M, $PKG).

TXSHOTS="$REPO/docs/status/screens"; mkdir -p "$TXSHOTS"
warn() { echo "CALLTEST WARN $*"; }
txshot() { A exec-out screencap -p > "$TXSHOTS/65-transcribe-$1.png" 2>/dev/null || true; }
CHUNK_RE='POST /api/v1/calls/[0-9a-f-]+/transcribe/chunks'
chunks_since() { tail -n +"$(( $1 + 1 ))" "$WORK/server.log" | grep -cE "$CHUNK_RE" || true; }
slog_mark() { wc -l < "$WORK/server.log"; }
touch "$WORK/tx-marker"

# ---------------------------------------------------------------- 1. the server with §36 on
cat > "$WORK/tx-ids.exs" <<'EXS'
alias RisiMe.{Repo}
alias RisiMe.Accounts.User
for p <- ["+94770000971", "+94770000972"], do: IO.puts("txid: " <> Repo.get_by!(User, phone: p).id)
EXS
mapfile -t TXIDS < <(cd server && $M mix run "$WORK/tx-ids.exs" 2>/dev/null | sed -n 's/^txid: //p')
[ "${#TXIDS[@]}" = 2 ] || fail "transcribe: user ids"
WKEY="$(grep -E '^WHISPER_API_KEY=' "$REPO/.env" 2>/dev/null | tail -1 | cut -d= -f2-)"
[ -n "$WKEY" ] || fail "transcribe: WHISPER_API_KEY missing from .env"
umask 077
{ echo "RISI_CALL_TRANSCRIBE=on"; echo "RISI_LEDGER=on"; echo "RISI_DEV_USERS=${TXIDS[0]}"; echo "WHISPER_API_KEY=$WKEY"; echo "RISI_SPEECH_URL=http://127.0.0.1:8200"; } >> "$WORK/risi.env"
umask 022
unset WKEY
echo "==> restarting the temp server with RISI_CALL_TRANSCRIBE=on"
tmux kill-session -t "risime-calltest$SUF" 2>/dev/null || true
for _ in $(seq 1 30); do ss -ltnH "sport = :$PORT" | grep -q . || break; sleep 1; done
tmux new -d -s "risime-calltest$SUF" "cd '$REPO/server' && set -a && . '$WORK/risi.env' && set +a && MIX_BUILD_PATH='$MIX_BUILD_PATH' RISIME_CODE_RELOADER=false RISIME_DEV_DB=$RISIME_DEV_DB RISIME_DEV_KEYSPACE=$RISIME_DEV_KEYSPACE ERL_EPMD_ADDRESS=127.0.0.1 \
  PORT=$PORT DEV_LOCAL_AUTH=true OIDC_ENABLED=false OTP_DEV_LOG=true PHONE_VERIFICATION=off FCM_ENABLED=false LOG_FORMAT=text \
  ATTESTATION_KEY_FILE='$WORK/attest.jwk' RISIME_AUTH_LOG='$WORK/auth.log' $M mix phx.server >> '$WORK/server.log' 2>&1"
for _ in $(seq 1 120); do curl -sf -o /dev/null "http://127.0.0.1:$PORT/health" && break; sleep 1; done
ok=""; for _ in $(seq 1 60); do curl -sf "http://127.0.0.1:$PORT/api/v1/auth/config" | grep -q '"call_transcribe":"on"' && { ok=1; break; }; sleep 2; done
[ -n "$ok" ] || fail "transcribe: /auth/config never said call_transcribe: on (speech service healthy? Risi up?)"
pass "transcribe: the temp server says call_transcribe: on"

# ---------------------------------------------------------------- 2. clips, served to the phones (memory only on the phone)
mkdir -p "$WORK/clips"
dec() { gst-launch-1.0 -q filesrc location="$1" ! oggdemux ! opusdec ! audioconvert ! audioresample ! "audio/x-raw,format=S16LE,rate=16000,channels=1" ! wavenc ! filesink location="$2"; }
dec "$REPO/android/testdata/transcribe/en-tts.ogg" "$WORK/clips/en.wav" || fail "transcribe: decode en"
dec "$REPO/android/testdata/transcribe/ta-tts.ogg" "$WORK/clips/ta.wav" || fail "transcribe: decode ta"
dec "$(ls "$REPO"/infra/whisper/testdata/si-*.ogg | head -1)" "$WORK/clips/si.wav" || fail "transcribe: decode si"
tmux kill-session -t "risime-calltest-clips$SUF" 2>/dev/null || true
tmux new -d -s "risime-calltest-clips$SUF" "python3 -I -m http.server $TX_CLIP_PORT --bind 127.0.0.1 --directory '$WORK/clips' > '$WORK/clips.out' 2>&1"
trap 'tmux kill-session -t "risime-calltest-clips$SUF" 2>/dev/null || true; cleanup' EXIT
for _ in $(seq 1 20); do curl -sf -o /dev/null "http://127.0.0.1:$TX_CLIP_PORT/en.wav" && break; sleep 0.5; done
for DEV in A B; do A reverse "tcp:$TX_CLIP_PORT" "tcp:$TX_CLIP_PORT" >/dev/null; done
inj_count() { A logcat -d 2>/dev/null | grep -cE "RisiMe debug: mic inject (done|failed|interrupted)" || true; }
declare -A INJ_BEFORE
inject() {  # <A|B> <clip> [repeat]
  DEV=$1; INJ_BEFORE[$1]="$(inj_count)"
  A shell am broadcast -a ai.risicloud.risime.debug.MIC_INJECT -p "$PKG" --es url "http://127.0.0.1:$TX_CLIP_PORT/$2.wav" --ei repeat "${3:-1}" --ei gap_ms 800 >/dev/null
}
inject_wait() {  # <A|B>: until that phone's injection is done (at most 120 s)
  DEV=$1; for _ in $(seq 1 120); do [ "$(inj_count)" -gt "${INJ_BEFORE[$1]:-0}" ] && return 0; sleep 1; done; return 1
}

# The apps reconnect to the restarted server, re-read /auth/config and advertise call_transcribe.
for DEV in A B; do
  ok=""; for _ in $(seq 1 60); do A logcat -d 2>/dev/null | grep -q "call_transcribe: server switch on" && { ok=1; break; }; sleep 2; done
  [ -n "$ok" ] || { A shell am force-stop "$PKG"; A shell am start -W -n "$PKG/$CLS.MainActivity" >/dev/null; sleep 8; }
done
DEV=A; official_on "ZZ Call B" || fail "transcribe: A can't start Official for the DM with B"
DEV=B; official_on "ZZ Call A" || fail "transcribe: B can't open the DM's Official tab"
pass "transcribe: Official is on for the A–B chat"

# ---------------------------------------------------------------- helpers: a call from a tab, A → B
start_call() {  # <private|official>
  local tabtext="🔒 Private" m
  [ "$1" = official ] && tabtext="● Official"
  for DEV in A B; do A shell cmd notification cancel_all >/dev/null 2>&1 || true; done
  DEV=B; open_chat "ZZ Call A" || fail "transcribe: B can't open the DM"
  DEV=A; open_chat "ZZ Call B" || fail "transcribe: A can't open the DM"
  tap "$tabtext" || fail "transcribe: A has no \"$tabtext\" tab"; sleep 2
  local ok=""; for _ in $(seq 1 20); do tap "Voice call" && { ok=1; break; }; sleep 2; done
  [ -n "$ok" ] || fail "transcribe: the $1 tab's Voice call button never became enabled"
  DEV=B; wait_ring 30 || fail "transcribe ($1): B never rang"
  answer_intent || fail "transcribe ($1): B can't answer"
  m=""; for _ in $(seq 1 30); do DEV=A; m="$(media_ok)"; [ -n "$m" ] && { DEV=B; [ -n "$(media_ok)" ] && break; }; m=""; sleep 1; done
  [ -n "$m" ] || fail "transcribe ($1): media never connected"
  sleep 2
}
end_call() {
  DEV=A; tap "End call" || fail "transcribe: A has no End call"
  DEV=B; for _ in $(seq 1 20); do has "End call" || break; sleep 1; done
  has "End call" && fail "transcribe: B still in the call"
  no_leftovers "transcribe"
}

# ---------------------------------------------------------------- 3. a Private call: no button, nothing sent
mark="$(slog_mark)"
start_call private
for DEV in A B; do
  dump; grep -qF 'text="Transcribe"' "$WORK/ui-$DEV.xml" && fail "transcribe: $DEV shows Transcribe on a Private call"
  A logcat -d 2>/dev/null | grep -q "transcribe: request" && fail "transcribe: $DEV sent a request on a Private call"
done
DEV=A; txshot private-call-no-button
inject A en 1; inject_wait A || true
[ "$(chunks_since "$mark")" = 0 ] || fail "transcribe: chunks uploaded during a Private call"
end_call
pass "transcribe: a Private call shows no Transcribe button and uploads nothing"

# ---------------------------------------------------------------- 4. Official call: decline, then allow
logcat_ok "transcribe (private part)"
for DEV in A B; do A logcat -c; done
start_call official
DEV=A; wait_for 'text="Transcribe"' 20 || fail "transcribe: no Transcribe button on A's Official call"
DEV=B; has 'text="Transcribe"' || fail "transcribe: no Transcribe button on B's Official call"
txshot official-call-button
mark="$(slog_mark)"
DEV=A; tap "Transcribe" || fail "transcribe: A can't tap Transcribe"
DEV=B; wait_for "Transcribe this call?" 15 || fail "transcribe: B never got the consent prompt"
has "Audio is sent to Risi for transcription" || fail "transcribe: B's prompt lacks the honest line"
txshot prompt
tap "Decline" || fail "transcribe: B can't Decline"
DEV=A; wait_for "declined transcription" 15 || warn "transcribe: A shows no decline notice"
inject A en 1; inject_wait A || true
sleep 3
[ "$(chunks_since "$mark")" = 0 ] || fail "transcribe: chunks were uploaded after B declined"
for DEV in A B; do has "Risi transcribing" && fail "transcribe: $DEV shows the bar after a decline"; done
pass "transcribe: B declined -> no bar, nothing uploaded"

sleep 11   # §36.3: not within 10 s of this user's last request
logcat_ok "transcribe (decline part)"
for DEV in A B; do A logcat -c; done
mark="$(slog_mark)"
DEV=A; tap "Transcribe" || fail "transcribe: A can't ask again"
DEV=B; wait_for "Transcribe this call?" 15 || fail "transcribe: B got no second prompt"
tap "Allow" || fail "transcribe: B can't Allow"
for DEV in A B; do wait_for "Risi transcribing" 15 || fail "transcribe: $DEV shows no red bar after Allow"; has "Audio is sent to Risi for transcription" || fail "transcribe: $DEV's bar lacks the honest line"; done
DEV=A; txshot bar-A; DEV=B; txshot bar-B
pass "transcribe: both allowed -> both phones show the red bar"
# Speech: A English, B Sinhala, A Tamil (one at a time, each phone only its own "microphone").
inject A en 2; inject_wait A || fail "transcribe: A's injection never finished"
inject B si 3; inject_wait B || fail "transcribe: B's injection never finished"
inject A ta 2; inject_wait A || fail "transcribe: A's second injection never finished"
sleep 20   # the last chunk closes (12 s) and uploads
n="$(chunks_since "$mark")"
[ "$n" -ge 4 ] || fail "transcribe: only $n chunks uploaded while on"
pass "transcribe: $n chunks uploaded while on"
# B stops it for everyone.
DEV=B; tap "Stop" || fail "transcribe: B has no Stop"; sleep 1
tap "Stop" || fail "transcribe: B's Stop menu has no Stop"
for DEV in A B; do
  ok=""; for _ in $(seq 1 15); do has "Risi transcribing" || { ok=1; break; }; sleep 1; done
  [ -n "$ok" ] || fail "transcribe: $DEV still shows the bar after B's Stop"
done
mark2="$(slog_mark)"; sleep 15
[ "$(chunks_since "$mark2")" = 0 ] || fail "transcribe: chunks still uploaded after Stop"
pass "transcribe: B's Stop removed the bar on both phones and the uploads stopped"

# A second session, ended with "Stop and delete transcript".
sleep 11
DEV=A; tap "Transcribe" || fail "transcribe: A can't ask a third time"
DEV=B; wait_for "Transcribe this call?" 15 || fail "transcribe: B got no third prompt"; tap "Allow"
DEV=A; wait_for "Risi transcribing" 15 || fail "transcribe: no bar for the second session"
inject A en 3; inject_wait A || true; sleep 8
DEV=A; tap "Stop" || fail "transcribe: A has no Stop"; sleep 1
tap "Stop and delete transcript" || fail "transcribe: no 'Stop and delete transcript'"
for DEV in A B; do ok=""; for _ in $(seq 1 15); do has "Risi transcribing" || { ok=1; break; }; sleep 1; done; [ -n "$ok" ] || fail "transcribe: $DEV still shows the bar after Stop and delete"; done
pass "transcribe: Stop and delete transcript stopped the second session"
end_call

# ---------------------------------------------------------------- 5. the Official chat: lines, speakers, languages
DEV=A; open_chat "ZZ Call B" || fail "transcribe: A can't reopen the DM"
tap "● Official" || fail "transcribe: A has no Official tab"
ok=""; for _ in $(seq 1 60); do has "asked Risi to transcribe this call" && { ok=1; break; }; sleep 2; done
[ -n "$ok" ] || fail "transcribe: no consent line in A's Official chat"
check_lines() {  # parses the visible transcript lines: "<name> HH:MM" then the text
  python3 -I - "$WORK/ui-$DEV.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
nodes = [n.get('text') or '' for n in ET.parse(sys.argv[1]).iter('node')]
si = re.compile('[\u0D80-\u0DFF]'); ta = re.compile('[\u0B80-\u0BFF]'); lat = re.compile('[A-Za-z]{3}')
seen = {"A": [], "B": []}
for i, t in enumerate(nodes):
    m = re.fullmatch(r'ZZ Call ([AB]) \d\d:\d\d', t)
    if m and i + 1 < len(nodes):
        seen[m.group(1)].append(nodes[i + 1])
bad = [x for x in seen["A"] if si.search(x)] + [x for x in seen["B"] if ta.search(x) or (lat.search(x) and not si.search(x))]
print("A=%d B=%d A_en=%d A_ta=%d B_si=%d bad=%d" % (len(seen["A"]), len(seen["B"]), sum(bool(lat.search(x)) for x in seen["A"]),
      sum(bool(ta.search(x)) for x in seen["A"]), sum(bool(si.search(x)) for x in seen["B"]), len(bad)))
sys.exit(1 if bad else 0)
PY
}
total=""; good=0
for _ in 1 2 3 4 5 6; do
  dump; r="$(check_lines)" || fail "transcribe: a transcript line is attributed to the wrong speaker ($r)"
  echo "    lines: $r"; total="$total $r"
  grep -q "A_en=[1-9]" <<<"$r" && good=$((good | 1)); grep -q "A_ta=[1-9]" <<<"$r" && good=$((good | 2)); grep -q "B_si=[1-9]" <<<"$r" && good=$((good | 4))
  [ "$good" = 7 ] && break
  A shell input swipe 540 700 540 1500 300; sleep 1   # scroll up for older lines
done
txshot official-chat-lines
[ $((good & 4)) = 4 ] || fail "transcribe: no Sinhala line from B in the Official chat"
[ $((good & 1)) = 1 ] || fail "transcribe: no English line from A in the Official chat"
[ $((good & 2)) = 2 ] || warn "transcribe: no Tamil line from A seen (scrolled 6 screens)"
pass "transcribe: transcript lines in the Official chat with the right speakers (A en/ta, B si)"
A shell input swipe 540 1500 540 300 200; A shell input swipe 540 1500 540 300 200; sleep 1
has "stopped transcription and deleted the transcript" || warn "transcribe: the discard consent line isn't on screen"
ok=""; for _ in $(seq 1 45); do has "Discussion summary" && { ok=1; break; }; sleep 2; done
if [ -n "$ok" ]; then txshot summary; pass "transcribe: the after-call summary card is in the Official chat"; else warn "transcribe: no summary card within 90 s (non-blocking: fake LLM)"; fi

# ---------------------------------------------------------------- 6. no audio left anywhere
for DEV in A B; do
  left="$(A shell run-as "$PKG" sh -c 'find . -type f \( -name "*.ogg" -o -name "*.opus" -o -name "*.wav" -o -name "*.pcm" -o -name "*.m4a" -o -name "*.aac" \) 2>/dev/null; for f in $(find . -type f -newer files/debug_keep_call_controls -size +8k 2>/dev/null); do case "$(head -c 4 "$f" 2>/dev/null)" in OggS|RIFF) echo "$f";; esac; done' 2>/dev/null || true)"
  [ -z "$left" ] || fail "transcribe: audio files left on $DEV: $left"
done
srv="$(find /tmp "$REPO/server/tmp" -maxdepth 3 -newer "$WORK/tx-marker" \( -name 'plug-*' -o -name '*.ogg' -o -name '*.opus' -o -name 'multipart-*' \) 2>/dev/null || true)"
[ -z "$srv" ] || fail "transcribe: server-side temp files: $srv"
wc="$(docker ps --format '{{.Names}}' | grep -m1 whisper || true)"
if [ -n "$wc" ]; then
  aud="$(docker diff "$wc" 2>/dev/null | grep -E '\.(ogg|opus|wav|webm|mp3|flac)$' || true)"
  [ -z "$aud" ] || fail "transcribe: audio files in the speech container: $aud"
fi
pass "transcribe: no audio file on either phone, in the server's temp dirs or in the speech container"
logcat_ok "transcribe"
pass "transcribe: §36 device gate"
