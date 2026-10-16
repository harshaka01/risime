# shellcheck shell=bash
# Sourced by scripts/call-device-test (CALLTEST_EXTRA) for scripts/call-summary-test (P0 2026-10-14: the after-call
# summary of an Official call). Every helper of call-device-test is available (A, DEV, tap, has, wait_for, dump, pass,
# fail, official_on, open_chat, answer_intent, media_ok, wait_ring, logcat_ok, no_leftovers, $WORK, $PORT, $SUF,
# $REPO, $M, $PKG, $CLS, $LLM_PORT). The wrapper turns "CALLTEST " into "CSTEST " on output.
#
# The temp server runs the pilot's configuration: RISI_CALL_TRANSCRIBE=on RISI_LEDGER=on RISI_ACTION_ITEMS=canary
# RISI_DEV_USERS=<A> (A is a dev user, so every A–B call goes the §38 action-items way; the old gate lacked
# ACTION_ITEMS and missed that a call with zero items posted nothing). The fake LLM is restarted for each call with a
# scripted reply carrying that call's tag (cs1/cs2/cs3), so a card is unmistakably that call's.

CSSHOTS="$REPO/docs/status/screens"; mkdir -p "$CSSHOTS"
CS_SUMMARY_S="${CS_SUMMARY_S:-30}"
csshot() { A exec-out screencap -p > "$CSSHOTS/66-call-summary-$1.png" 2>/dev/null || true; }
CHUNK_RE='POST /api/v1/calls/[0-9a-f-]+/transcribe/chunks'
chunks_since() { tail -n +"$(( $1 + 1 ))" "$WORK/server.log" | grep -cE "$CHUNK_RE" || true; }
slog_mark() { wc -l < "$WORK/server.log"; }
llm_count() { [ -f "$WORK/llm-requests.jsonl" ] && wc -l < "$WORK/llm-requests.jsonl" || echo 0; }
TIMINGS=""

# ---------------------------------------------------------------- 1. the server: the pilot's Risi configuration
cat > "$WORK/cs-ids.exs" <<'EXS'
alias RisiMe.{Repo}
alias RisiMe.Accounts.User
for p <- ["+94770000971", "+94770000972"], do: IO.puts("csid: " <> Repo.get_by!(User, phone: p).id)
EXS
mapfile -t CSIDS < <(cd server && $M mix run "$WORK/cs-ids.exs" 2>/dev/null | sed -n 's/^csid: //p')
[ "${#CSIDS[@]}" = 2 ] || fail "summary: user ids"
WKEY="$(grep -E '^WHISPER_API_KEY=' "$REPO/.env" 2>/dev/null | tail -1 | cut -d= -f2-)"
[ -n "$WKEY" ] || fail "summary: WHISPER_API_KEY missing from .env"
umask 077
{ echo "RISI_CALL_TRANSCRIBE=on"; echo "RISI_LEDGER=on"; echo "RISI_ACTION_ITEMS=canary"; echo "RISI_DEV_USERS=${CSIDS[0]}"
  echo "WHISPER_API_KEY=$WKEY"; echo "RISI_SPEECH_URL=http://127.0.0.1:8200"
  # P0 2026-10-15: CS_SECTION_S-second sections, so these one-minute calls take the long-call (map-reduce) path that a
  # 30-min call takes with the pilot's 600-s sections. CS_SECTION_S=0 keeps the pilot's sections (one per call here).
  [ "${CS_SECTION_S:-20}" != 0 ] && echo "RISI_CALL_SECTION_S=${CS_SECTION_S:-20}"; } >> "$WORK/risi.env"
umask 022
unset WKEY

# The fake LLM, scripted per call: action_items -> one item (or none), discussion_summary -> two key points.
llm_script() {  # <tag> <items 0|1>
  local tag="$1" items="[]"
  [ "$2" = 1 ] && items='[{"kind":"task","text":"Send the revised quote '"$tag"'i","owner":null,"counterparts":[],"due_local":null,"due_text":"Friday","source":["m1"],"lang":"en","confidence":0.9}]'
  cat > "$WORK/cs-llm-$tag.json" <<JSON
{"v": 1, "rules": [
  {"name": "actions", "task": "action_items", "actions": [{"summary": "${tag}s The call covered the office meeting and the quote.", "key_points": ["${tag}p The office meeting and the quote were discussed."], "items": $items}]},
  {"name": "reduce", "task": "call_summary", "actions": [{"key_points": ["${tag}r The whole call: the office meeting and the revised quote."], "summary": "${tag}r Office meeting and the revised quote."}]},
  {"name": "discussion", "task": "discussion_summary", "actions": [{"key_points": ["${tag}k The team meets at the office tomorrow at ten.", "${tag}k The revised quote goes out on Friday."], "summary": "${tag}d Office meeting and the revised quote.", "items": []}]}
]}
JSON
  tmux kill-session -t "risime-calltest-llm$SUF" 2>/dev/null || true
  for _ in $(seq 1 20); do ss -ltnH "sport = :$LLM_PORT" | grep -q . || break; sleep 0.5; done
  tmux new -d -s "risime-calltest-llm$SUF" "python3 -I '$REPO/scripts/fake-llm' --port $LLM_PORT --record '$WORK/llm-requests.jsonl' --script '$WORK/cs-llm-$tag.json' >> '$WORK/fake-llm.out' 2>&1"
  for _ in $(seq 1 20); do curl -sf -o /dev/null "http://127.0.0.1:$LLM_PORT/health" && return 0; sleep 0.5; done
  fail "summary: the scripted fake LLM didn't start ($(tail -2 "$WORK/fake-llm.out" | tr '\n' ' '))"
}
llm_script cs0 0

echo "==> restarting the temp server: RISI_CALL_TRANSCRIBE=on RISI_LEDGER=on RISI_ACTION_ITEMS=canary RISI_DEV_USERS=<A>"
tmux kill-session -t "risime-calltest$SUF" 2>/dev/null || true
for _ in $(seq 1 30); do ss -ltnH "sport = :$PORT" | grep -q . || break; sleep 1; done
tmux new -d -s "risime-calltest$SUF" "cd '$REPO/server' && set -a && . '$WORK/risi.env' && set +a && MIX_BUILD_PATH='$MIX_BUILD_PATH' RISIME_CODE_RELOADER=false RISIME_DEV_DB=$RISIME_DEV_DB RISIME_DEV_KEYSPACE=$RISIME_DEV_KEYSPACE ERL_EPMD_ADDRESS=127.0.0.1 \
  PORT=$PORT DEV_LOCAL_AUTH=true OIDC_ENABLED=false OTP_DEV_LOG=true PHONE_VERIFICATION=off FCM_ENABLED=false LOG_FORMAT=text \
  ATTESTATION_KEY_FILE='$WORK/attest.jwk' RISIME_AUTH_LOG='$WORK/auth.log' $M mix phx.server >> '$WORK/server.log' 2>&1"
for _ in $(seq 1 120); do curl -sf -o /dev/null "http://127.0.0.1:$PORT/health" && break; sleep 1; done
ok=""; for _ in $(seq 1 60); do curl -sf "http://127.0.0.1:$PORT/api/v1/auth/config" | grep -q '"call_transcribe":"on"' && { ok=1; break; }; sleep 2; done
[ -n "$ok" ] || fail "summary: /auth/config never said call_transcribe: on (speech service healthy? Risi up?)"
cat > "$WORK/cs-flags.exs" <<'EXS'
[a, b] = System.argv()
IO.puts("csflags: a=#{RisiMe.Agent.ActionItems.on_for?([a])} b=#{RisiMe.Agent.ActionItems.on_for?([b])}")
EXS
fl="$(cd server && set -a && . "$WORK/risi.env" && set +a && $M mix run --no-start "$WORK/cs-flags.exs" "${CSIDS[0]}" "${CSIDS[1]}" 2>/dev/null | grep '^csflags:' || true)"
echo "    $fl"
[ "$fl" = "csflags: a=true b=false" ] || echo "CALLTEST WARN summary: couldn't confirm the action-items canary from a mix run (${fl:-no output}); the cards below decide"
pass "summary: the temp server runs call_transcribe on, ledger on, action items canary (A a dev user)"

# ---------------------------------------------------------------- 2. clips, served to the phones (memory only on the phone)
mkdir -p "$WORK/clips"
dec() { gst-launch-1.0 -q filesrc location="$1" ! oggdemux ! opusdec ! audioconvert ! audioresample ! "audio/x-raw,format=S16LE,rate=16000,channels=1" ! wavenc ! filesink location="$2"; }
dec "$REPO/android/testdata/transcribe/en-tts.ogg" "$WORK/clips/en.wav" || fail "summary: decode en"
dec "$REPO/infra/whisper/testdata/en-jfk.ogg" "$WORK/clips/jfk.wav" || fail "summary: decode jfk"
tmux kill-session -t "risime-calltest-clips$SUF" 2>/dev/null || true
tmux new -d -s "risime-calltest-clips$SUF" "python3 -I -m http.server $CS_CLIP_PORT --bind 127.0.0.1 --directory '$WORK/clips' > '$WORK/clips.out' 2>&1"
trap 'tmux kill-session -t "risime-calltest-clips$SUF" 2>/dev/null || true; cleanup' EXIT
for _ in $(seq 1 20); do curl -sf -o /dev/null "http://127.0.0.1:$CS_CLIP_PORT/en.wav" && break; sleep 0.5; done
for DEV in A B; do A reverse "tcp:$CS_CLIP_PORT" "tcp:$CS_CLIP_PORT" >/dev/null; done
inj_count() { A logcat -d 2>/dev/null | grep -cE "RisiMe debug: mic inject (done|failed|interrupted)" || true; }
declare -A INJ_BEFORE
inject() {  # <A|B> <clip> [repeat]
  DEV=$1; INJ_BEFORE[$1]="$(inj_count)"
  A shell am broadcast -a ai.risicloud.risime.debug.MIC_INJECT -p "$PKG" --es url "http://127.0.0.1:$CS_CLIP_PORT/$2.wav" --ei repeat "${3:-1}" --ei gap_ms 800 >/dev/null
}
inject_wait() {  # <A|B>: until that phone's injection is done (at most 150 s)
  DEV=$1; for _ in $(seq 1 150); do [ "$(inj_count)" -gt "${INJ_BEFORE[$1]:-0}" ] && return 0; sleep 1; done; return 1
}

for DEV in A B; do
  ok=""; for _ in $(seq 1 60); do A logcat -d 2>/dev/null | grep -q "call_transcribe: server switch on" && { ok=1; break; }; sleep 2; done
  [ -n "$ok" ] || { A shell am force-stop "$PKG"; A shell am start -W -n "$PKG/$CLS.MainActivity" >/dev/null; sleep 8; }
done
DEV=A; official_on "ZZ Call B" || fail "summary: A can't start Official for the DM with B"
DEV=B; official_on "ZZ Call A" || fail "summary: B can't open the DM's Official tab"
pass "summary: Official is on for the A–B chat"

# ---------------------------------------------------------------- helpers
start_call() {  # <private|official>
  local tabtext="🔒 Private" m ok
  [ "$1" = official ] && tabtext="● Official"
  for DEV in A B; do A shell cmd notification cancel_all >/dev/null 2>&1 || true; done
  DEV=B; open_chat "ZZ Call A" || fail "summary: B can't open the DM"
  DEV=A; open_chat "ZZ Call B" || fail "summary: A can't open the DM"
  tap "$tabtext" || fail "summary: A has no \"$tabtext\" tab"; sleep 2
  ok=""; for _ in $(seq 1 20); do tap "Voice call" && { ok=1; break; }; sleep 2; done
  [ -n "$ok" ] || fail "summary: the $1 tab's Voice call button never became enabled"
  DEV=B; wait_ring 30 || fail "summary ($1): B never rang"
  answer_intent || fail "summary ($1): B can't answer"
  m=""; for _ in $(seq 1 30); do DEV=A; m="$(media_ok)"; [ -n "$m" ] && { DEV=B; [ -n "$(media_ok)" ] && break; }; m=""; sleep 1; done
  [ -n "$m" ] || fail "summary ($1): media never connected"
  CALL_T0="$(date +%s)"
  sleep 2
}
END_TS=0
end_call() {
  DEV=A; tap "End call" || fail "summary: A has no End call"
  END_TS="$(date +%s)"
  DEV=B; for _ in $(seq 1 20); do has "End call" || break; sleep 1; done
  if has "End call"; then fail "summary: B still in the call"; fi
}
transcribe_on() {  # A asks, B allows; both show the red bar
  DEV=A; wait_for 'text="Transcribe"' 20 || fail "$1: no Transcribe button on A's Official call"
  DEV=B; has 'text="Transcribe"' || fail "$1: no Transcribe button on B's Official call"
  DEV=A; tap "Transcribe" || fail "$1: A can't tap Transcribe"
  DEV=B; wait_for "Transcribe this call?" 15 || fail "$1: B never got the consent prompt"
  tap "Allow" || fail "$1: B can't Allow"
  for DEV in A B; do wait_for "Risi transcribing" 15 || fail "$1: $DEV shows no red bar after Allow"; done
  TX_T0="$(date +%s)"
}
# The phone shows its Official tab (the call screen closed back to the chat, else open it).
to_official() {
  local peer="ZZ Call B"; [ "$DEV" = B ] && peer="ZZ Call A"
  dump
  if ! grep -qF 'Risi is listening' "$WORK/ui-$DEV.xml"; then
    grep -qF 'text="● Official"' "$WORK/ui-$DEV.xml" || open_chat "$peer" || return 1
    tap "● Official" || return 1; sleep 1
  fi
}
to_bottom() { A shell input swipe 540 1500 540 400 120 >/dev/null 2>&1 || true; }
# The newest summary card of this call (by its tag) on both phones, timed from End; blocking after $CS_SUMMARY_S s
# (polls on to 120 s so the report says how late it was).
await_card() {  # <label> <regex of the call's tagged text>
  local label="$1" re="$2" seenA="" seenB="" now d
  for DEV in A B; do to_official || fail "$label: $DEV can't open the Official tab after the call"; done
  while :; do
    now="$(date +%s)"
    for DEV in A B; do
      if [ "$DEV" = A ] && [ -n "$seenA" ]; then continue; fi
      if [ "$DEV" = B ] && [ -n "$seenB" ]; then continue; fi
      to_bottom; dump
      if grep -qE "$re" "$WORK/ui-$DEV.xml"; then
        d=$(( $(date +%s) - END_TS ))
        if [ "$DEV" = A ]; then seenA="$d"; else seenB="$d"; fi
      fi
    done
    [ -n "$seenA" ] && [ -n "$seenB" ] && break
    [ $(( now - END_TS )) -ge 120 ] && break
    sleep 1
  done
  TIMINGS="$TIMINGS $label:A=${seenA:-none}s,B=${seenB:-none}s"
  echo "    $label: End -> summary card: A ${seenA:-never} s, B ${seenB:-never} s (limit $CS_SUMMARY_S s)"
  local notice; notice="$(grep -ohE "(too little speech|only heard one person|couldn.t (summarise|transcribe))[^\"]*" "$WORK/ui-A.xml" "$WORK/ui-B.xml" 2>/dev/null | sort -u | tr '\n' ' ' || true)"
  [ -n "$notice" ] && echo "    Risi notices on screen: $notice"
  [ -n "$seenA" ] && [ -n "$seenB" ] || { DEV=A; csshot "missing-card"; fail "$label: no summary card on $( [ -z "$seenA" ] && echo A ) $( [ -z "$seenB" ] && echo B ) within 120 s of End (fake LLM requests: $(llm_count); server: $(grep -E 'call transcribe|action items|Risi.*summar' "$WORK/server.log" | tail -3 | tr '\n' ' '))"; }
  [ "$seenA" -le "$CS_SUMMARY_S" ] && [ "$seenB" -le "$CS_SUMMARY_S" ] || fail "$label: the summary card came after $CS_SUMMARY_S s (A ${seenA} s, B ${seenB} s)"
  pass "$label: the summary card is in the Official chat on both phones (A ${seenA} s, B ${seenB} s after End)"
}
# The call's transcript lines from both speakers are in the Official chat ($1 label, $2 regex of the clip's words).
lines_check() {
  local label="$1" words="$2" r good=0
  DEV=A
  for _ in $(seq 1 24); do   # half-screen drags, no fling: a long bubble's header and text are seen together
    dump
    r="$(python3 -I - "$WORK/ui-$DEV.xml" "$words" <<'PY'
import re, sys, xml.etree.ElementTree as ET
nodes = [n.get('text') or '' for n in ET.parse(sys.argv[1]).iter('node')]
w = re.compile(sys.argv[2], re.I)
seen = {"A": 0, "B": 0}
for i, t in enumerate(nodes):
    m = re.fullmatch(r'(ZZ Call ([AB])|You) \d\d:\d\d', t)   # on A's phone A's own lines say "You"
    if m and i + 1 < len(nodes) and w.search(nodes[i + 1]):
        seen[m.group(2) or "A"] += 1
print("A=%d B=%d" % (seen["A"], seen["B"]))
PY
)"
    grep -q "A=[1-9]" <<<"$r" && good=$((good | 1)); grep -q "B=[1-9]" <<<"$r" && good=$((good | 2))
    [ "$good" = 3 ] && break
    grep -qF "You started this chat" "$WORK/ui-$DEV.xml" && break   # the top
    A shell input swipe 540 800 540 1300 900; sleep 1   # older lines (slow drag)
  done
  [ "$good" = 3 ] || fail "$label: the live transcript lines of both speakers aren't in A's Official chat (seen: A=$((good & 1)) B=$(( (good & 2) / 2 )))"
  pass "$label: the live transcript lines of A and B are in the Official chat"
  for _ in 1 2 3 4 5 6 7 8 9 10; do to_bottom; done
}

# ---------------------------------------------------------------- 3. Private call: no button, nothing uploaded, nothing posted
mark="$(slog_mark)"; n0="$(llm_count)"
start_call private
for DEV in A B; do
  dump; grep -qF 'text="Transcribe"' "$WORK/ui-$DEV.xml" && fail "private: $DEV shows Transcribe on a Private call"
done
DEV=A; csshot private-call
inject A en 2; inject B en 2; inject_wait A || true; inject_wait B || true
sleep 3
end_call
no_leftovers "private"
sleep 20
[ "$(chunks_since "$mark")" = 0 ] || fail "private: chunks uploaded during a Private call"
tail -n +"$(( mark + 1 ))" "$WORK/server.log" | grep -qE 'POST /api/v1/calls/[0-9a-f-]+/transcribe' && fail "private: a transcribe request reached the server"
[ "$(llm_count)" = "$n0" ] || fail "private: the model was called after a Private call ($n0 -> $(llm_count) requests)"
DEV=A; to_official || fail "private: A can't open the Official tab"
dump
grep -qE 'too little speech|only heard one person|couldn.t summarise|Discussion summary|Action items' "$WORK/ui-$DEV.xml" && fail "private: Risi posted something into Official after a Private call"
pass "private: no Transcribe button, nothing uploaded (server log), no model call, nothing posted"
logcat_ok "private"

# ---------------------------------------------------------------- 4. Official calls: summary card within $CS_SUMMARY_S s
official_run() {  # <tag> <items 0|1> <clip> <reps> <words regex> <label>
  local tag="$1" items="$2" clip="$3" reps="$4" words="$5" label="$6" mark n
  llm_script "$tag" "$items"
  for DEV in A B; do A logcat -c; done
  start_call official
  transcribe_on "$label"
  DEV=A; csshot "$tag-bar"
  mark="$(slog_mark)"
  inject A "$clip" "$reps"; inject_wait A || fail "$label: A's injection never finished"
  inject B "$clip" "$reps"; inject_wait B || fail "$label: B's injection never finished"
  sleep 16   # the last chunk closes (12 s) and uploads
  n="$(chunks_since "$mark")"
  [ "$n" -ge 4 ] || fail "$label: only $n chunks uploaded while on"
  echo "    $label: call $(( $(date +%s) - CALL_T0 )) s, $n chunks"
  end_call
  no_leftovers "$label"
}

# Architect 2026-10-16 14:05: the Official action-items card is one line ("Risi · 1 action item ›") until tapped.
expand_items() {
  local t; dump
  t="$(grep -oE 'text="Risi · ([0-9]+ action items?|No open action items|Couldn.t hear the action items clearly) ›"' "$WORK/ui-$DEV.xml" | head -1 | sed 's/^text="//; s/"$//')"
  [ -n "$t" ] && { tap "$t" >/dev/null || true; sleep 1; }
  return 0
}
# 4a. One action item: the action_items card with the item, on both phones.
official_run cs1 1 en 4 'office|quote|friday|meet|revised' "official (1 item)"
await_card "official (1 item)" 'cs1i|cs1s|cs1k|cs1d|cs1p|cs1r'
# P0 2026-10-15: a call with an action item gets its discussion summary too, not only the items. The phones render the
# Official discussion_card's `summary` line only (not `key_points`), so the server writes the key points into that
# line ("<summary> — point; point", ≤ 280); they also go to each participant's Risi chat as the §27.3 copy (this gate's
# phones have no Risi chat entry, so that copy is not checked here).
for DEV in A B; do
  ok=""; for _ in 1 2 3 4 5 6 7 8; do to_bottom; done
  for _ in $(seq 1 12); do dump; grep -qE 'cs1p The office|cs1r The whole call' "$WORK/ui-$DEV.xml" && { ok=1; break; }; A shell input swipe 540 800 540 1200 900; sleep 1; done
  [ -n "$ok" ] || { csshot "1-item-no-keypoints-$DEV"; fail "official (1 item): $DEV shows the items but not the call's key points on the summary card"; }
  csshot "1-item-keypoints-$DEV"
  if [ "${CS_SECTION_S:-20}" != 0 ] && [ "$DEV" = A ]; then
    grep -qF 'cs1r The whole call' "$WORK/ui-$DEV.xml" || echo "CALLTEST WARN official (1 item): the reduced key point (cs1r) isn't on screen; a section's own point is"
  fi
  for _ in 1 2 3 4 5 6 7 8; do to_bottom; done   # the newest posts for the item check below
done
pass "official (1 item): the summary card with the call's key points is in Official next to the action items on both phones"
if [ "${CS_SECTION_S:-20}" != 0 ]; then
  na="$(grep -cE '"name": ?"action_items"' "$WORK/llm-requests.jsonl" || true)"; nr="$(grep -cE '"name": ?"call_summary"' "$WORK/llm-requests.jsonl" || true)"
  [ "$na" -ge 2 ] && [ "$nr" -ge 1 ] || fail "official (1 item): the call wasn't summarised in sections (${CS_SECTION_S:-20}-s sections: $na action_items requests, $nr reduce requests)"
  pass "official (1 item): long-call path: $na section extractions and $nr reduce for one card (sections of ${CS_SECTION_S:-20} s)"
fi
for DEV in A B; do
  ok=""; for _ in 1 2 3 4 5 6; do expand_items; dump; grep -qF 'Send the revised quote cs1i' "$WORK/ui-$DEV.xml" && { ok=1; break; }; A shell input swipe 540 900 540 1300 300; sleep 1; done
  [ -n "$ok" ] || fail "official (1 item): $DEV's card doesn't show the action item"
  csshot "1-item-$DEV"
done
DEV=A; grep -qF 'Action items' "$WORK/ui-$DEV.xml" || echo "CALLTEST WARN official (1 item): the item is shown but the 'Risi · Action items' header is off screen"
pass "official (1 item): the action item is on the card on both phones"
lines_check "official (1 item)" 'office|quote|friday|meet|revised'

# 4b. Zero action items: still a summary card (the P0 regression).
official_run cs2 0 jfk 2 'country|ask|fellow|americans|what' "official (0 items)"
await_card "official (0 items)" 'cs2k|cs2d|cs2s|cs2r'
for DEV in A B; do csshot "0-items-$DEV"; done
DEV=A; dump; grep -qE 'cs2[dkr]' "$WORK/ui-$DEV.xml" && kind="the §27 discussion card" || kind="an action_items card with no items"
pass "official (0 items): zero action items still give the summary card on both phones ($kind)"
lines_check "official (0 items)" 'country|ask|fellow|americans|what'

# ---------------------------------------------------------------- 5. Stop mid-call, the call goes on, then End
llm_script cs3 1
for DEV in A B; do A logcat -c; done
start_call official
transcribe_on "stopped"
mark="$(slog_mark)"
inject A en 4; inject B en 4   # both talk at once: ~23 s each
inject_wait A || fail "stopped: A's injection never finished"; inject_wait B || fail "stopped: B's injection never finished"
sleep 14
n="$(chunks_since "$mark")"; [ "$n" -ge 2 ] || fail "stopped: only $n chunks uploaded while on"
echo "    stopped: transcription off at $(( $(date +%s) - TX_T0 )) s ($n chunks)"
DEV=A; tap "Stop" || fail "stopped: A has no Stop"; sleep 1
tap "Stop" || fail "stopped: A's Stop menu has no Stop"
for DEV in A B; do
  ok=""; for _ in $(seq 1 15); do has "Risi transcribing" || { ok=1; break; }; sleep 1; done
  [ -n "$ok" ] || fail "stopped: $DEV still shows the bar after Stop"
  has "End call" || fail "stopped: $DEV's call ended with the transcription"
done
mark2="$(slog_mark)"
inject A en 2; inject_wait A || true; sleep 14
[ "$(chunks_since "$mark2")" = 0 ] || fail "stopped: chunks still uploaded after Stop"
pass "stopped: Stop removed the bar on both phones, the call went on, nothing uploaded after it"
DEV=A; csshot stopped-call
end_call
no_leftovers "stopped"
await_card "stopped" 'cs3i|cs3s|cs3k|cs3d|cs3p|cs3r'
for DEV in A B; do
  ok=""; for _ in $(seq 1 20); do to_bottom; dump; grep -qF 'transcription was turned off after that' "$WORK/ui-$DEV.xml" && { ok=1; break; }; sleep 1; done
  [ -n "$ok" ] || { for _ in 1 2 3; do A shell input swipe 540 900 540 1300 300; sleep 1; dump; grep -qF 'transcription was turned off after that' "$WORK/ui-$DEV.xml" && { ok=1; break; }; done; }
  [ -n "$ok" ] || fail "stopped: no \"Summary of the first … min; transcription was turned off after that.\" on $DEV"
  grep -qE 'Summary of the first [0-9]+ min' "$WORK/ui-$DEV.xml" || echo "CALLTEST WARN stopped: $DEV's line doesn't start \"Summary of the first N min\""
  csshot "stopped-$DEV"
done
pass "stopped: the summary card plus Risi's \"Summary of the first … min; transcription was turned off after that.\" on both phones"

logcat_ok "call summary"
echo "    timings (End -> card):$TIMINGS"
pass "summary: call-summary gate"
