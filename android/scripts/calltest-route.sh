# Android-owned extra steps for scripts/call-device-test (P0 audio routing). Sourced at the end with
# every helper available:
#   CALLTEST_EXTRA=android/scripts/calltest-route.sh scripts/call-device-test
#
# Call R1: a voice call, A taps the route button ("Audio output") until the route line says
# current=SPEAKER (one tap from the earpiece, two from the speaker: speaker → earpiece → speaker),
# the user's pick is logged, and audio still flows both ways.
# Call R2: a video call (B → A, answered in the call screen) starts on the speaker (route_ok) with
# audio both ways (audio_ok), as calls 9, 12 and 13 do.

# route_cur: A's (DEV's) latest `calls: route current=<KIND>`.
route_cur() { A logcat -d 2>/dev/null | grep -E 'calls: route current=' | tail -1 | sed -nE 's/.*current=([A-Z_]+).*/\1/p'; }

route_tap_to_speaker() {  # $1 label
  local cur before i
  DEV=A
  for _ in $(seq 1 10); do cur="$(route_cur)"; [ -n "$cur" ] && break; sleep 1; done
  [ -n "$cur" ] || fail "$1: A has no route line"
  before="$cur"
  for i in 1 2; do
    tap "Audio output" || fail "$1: A has no route button (Audio output)"
    for _ in $(seq 1 8); do sleep 1; cur="$(route_cur)"; [ "$cur" != "$before" ] && break; done
    A logcat -d 2>/dev/null | grep -qE 'calls: route decision \(user\)' || fail "$1: the tap logged no user route decision"
    echo "    [A] tap $i: route $before → $cur"
    [ "$cur" = SPEAKER ] && break
    before="$cur"
  done
  [ "$cur" = SPEAKER ] || fail "$1: the route button never reached the speaker (current=$cur; $(A logcat -d 2>/dev/null | grep -E 'calls: route (set|decision)' | tail -3 | tr '\n' ' '))"
  pass "$1: the route button moved A to the speaker (current=SPEAKER)"
}

DEV=A; open_chat "ZZ Call B" || fail "A: DM"; DEV=B; open_chat "ZZ Call A" || fail "B: DM"
call_once "call R1 (voice, route tap)"
route_tap_to_speaker "call R1"
audio_ok "call R1 (after the route tap)"
hang_up_a "call R1"

DEV=A; open_chat "ZZ Call B" || fail "A: DM"; DEV=B; open_chat "ZZ Call A" || fail "B: DM"
video_simple "call R2 (video starts on the speaker)" B A screen
