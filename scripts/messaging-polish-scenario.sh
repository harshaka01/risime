# shellcheck shell=bash
# Sourced by scripts/call-device-test (CALLTEST_EXTRA) for scripts/messaging-polish-test (PART 11: swipe-to-reply on
# §33.9 and v1.44 §39 link previews). Every helper of call-device-test is available (A, DEV, tap, has, wait_for, dump,
# pass, fail, official_on, open_chat, back_to_list, type_into, hide_keyboard, logcat_ok, $WORK, $PORT, $SUF, $REPO,
# $M, $PKG, $CLS). Names: "ZZ Call A" / "ZZ Call B". No URL ever goes to the server in plaintext: the checks at the
# end search its log and its whole database.

MPSHOTS="$REPO/docs/status/screens"; mkdir -p "$MPSHOTS"
warn() { echo "CALLTEST WARN $*"; }
mshot() { A exec-out screencap -p > "$MPSHOTS/69-$1.png" 2>/dev/null || true; }
GROUP="ZZ Polish group"
OG_TITLE="MP Test Page"

# ---------------------------------------------------------------- 1. the local Open Graph page (127.0.0.1 only)
mkdir -p "$WORK/og"
cat > "$WORK/og/page.html" <<HTML
<!doctype html><html><head><meta charset="utf-8"><title>fallback</title>
<meta property="og:title" content="$OG_TITLE">
<meta property="og:description" content="A local Open Graph page for the messaging gate">
<meta property="og:site_name" content="RisiMe gate">
<meta property="og:image" content="https://example.org/og.jpg">
</head><body>gate</body></html>
HTML
python3 -I -c "
from PIL import Image, ImageDraw
im = Image.new('RGB', (600, 315), (30, 110, 200)); d = ImageDraw.Draw(im); d.rectangle([40, 40, 300, 275], fill=(250, 250, 250))
im.save('$WORK/og/og.jpg', 'JPEG', quality=80)" || fail "polish: make the OG image"
# Every path serves the page (the router keeps the path), og.jpg the image; requests are logged (the switch-off check).
cat > "$WORK/og-server.py" <<'PY'
import http.server, sys, os
root = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        sys.stderr.write("OGREQ %s\n" % self.path); sys.stderr.flush()
        name, ctype = ("og.jpg", "image/jpeg") if self.path.startswith("/og.jpg") else ("page.html", "text/html; charset=utf-8")
        body = open(os.path.join(root, name), "rb").read()
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        self.send_header("Set-Cookie", "gate=1"); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
tmux kill-session -t "risime-calltest-og$SUF" 2>/dev/null || true
tmux new -d -s "risime-calltest-og$SUF" "python3 -I '$WORK/og-server.py' $MP_OG_PORT '$WORK/og' 2> '$WORK/og.log'"
trap 'tmux kill-session -t "risime-calltest-og$SUF" 2>/dev/null || true; cleanup' EXIT
for _ in $(seq 1 20); do curl -sf -o /dev/null "http://127.0.0.1:$MP_OG_PORT/x" && break; sleep 0.5; done
curl -sf -o /dev/null "http://127.0.0.1:$MP_OG_PORT/x" || fail "polish: the OG page didn't start"
: > "$WORK/og.log"
for DEV in A B; do
  A reverse "tcp:$MP_OG_PORT" "tcp:$MP_OG_PORT" >/dev/null
  A logcat -c || true
  A shell am broadcast -a ai.risicloud.risime.debug.LINK_PREVIEW_TEST -p "$PKG" --es host example.org --es origin "http://127.0.0.1:$MP_OG_PORT" >/dev/null
  ok=""; for _ in $(seq 1 10); do A logcat -d 2>/dev/null | grep -qF "RisiMe debug: link preview route armed" && { ok=1; break; }; sleep 1; done
  [ -n "$ok" ] || fail "polish: $DEV's link-preview test route was not armed (debug build?)"
done
pass "polish: example.org is routed to the local OG page on both phones (debug-only route)"

# ---------------------------------------------------------------- helpers
# Centre "x y" (and the left edge) of the LAST node whose text or content-desc matches the regex $1 (after a dump).
node_box() {
  python3 -I - "$WORK/ui-$DEV.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[2]); hit = None
try:
    nodes = list(ET.parse(sys.argv[1]).iter('node'))
except ET.ParseError:
    nodes = []
for n in nodes:
    if pat.search(n.get('content-desc') or '') or pat.search(n.get('text') or ''):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds'))); hit = (x1, (y1 + y2) // 2, x2)
if hit: print(*hit)
PY
}
count_nodes() { python3 -I - "$WORK/ui-$DEV.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[2])
try:
    nodes = list(ET.parse(sys.argv[1]).iter('node'))
except ET.ParseError:
    nodes = []
print(sum(1 for n in nodes if pat.search(n.get('content-desc') or '') or pat.search(n.get('text') or '')))
PY
}
wait_node() {  # <regex> [seconds]
  for _ in $(seq 1 "${2:-30}"); do dump; [ "$(count_nodes "$1")" -gt 0 ] && return 0; sleep 1; done
  echo "    [$DEV] timeout waiting for /$1/"; return 1
}
composer() { dump; grep -qE '(text|content-desc)="(Encrypted message|Message)"' "$WORK/ui-$DEV.xml"; }
send_text() {  # <text> (no shell-special characters)
  { type_into "Encrypted message" "$1" || type_into "Message" "$1"; } || return 1
  sleep 0.5; tap "Send"
}
# Swipe right on the bubble whose content-desc matches $1: the bubble follows, the reply bar shows.
swipe_reply() {  # <regex of the bubble>
  hide_keyboard; dump
  local b x y x2; b="$(node_box "$1")"; [ -n "$b" ] || { echo "    [$DEV] no bubble /$1/"; return 1; }
  read -r x y x2 <<<"$b"
  local sx=$(( x + 30 )); local ex=$(( sx + 420 ))
  A shell input swipe "$sx" "$y" "$ex" "$y" 450
  wait_node '^Reply to ' 8
}
# The group's chat on its Private tab, composer ready.
group_private() {
  open_chat "$GROUP" || return 1
  for _ in $(seq 1 10); do
    dump; grep -qF 'Risi listens in Official' "$WORK/ui-$DEV.xml" && { tap "OK" || true; sleep 1; }
    has 'Risi is listening' && { tap "🔒 Private" || true; sleep 2; continue; }
    composer && return 0
    sleep 1
  done
  return 1
}
dm_private() {
  open_chat "$1" || return 1
  for _ in $(seq 1 10); do
    has 'Risi is listening' && { tap "🔒 Private" || true; sleep 2; continue; }
    composer && return 0
    sleep 1
  done
  return 1
}

# ---------------------------------------------------------------- 2. 1:1 Private: swipe to reply
DEV=A; dm_private "ZZ Call B" || fail "polish: A can't open the 1:1 (composer)"
send_text "mp-hello-1" || fail "polish: A can't send"
DEV=B; dm_private "ZZ Call A" || fail "polish: B can't open the 1:1"
wait_node 'mp-hello-1' 60 || fail "polish: B never got mp-hello-1"
swipe_reply 'mp-hello-1' || fail "polish: B's swipe right on A's message showed no reply bar"
mshot dm-swipe-reply-bar
send_text "mp-reply-1" || fail "polish: B can't send the reply"
DEV=A; wait_node 'Replying to You: mp-hello-1.*mp-reply-1|mp-reply-1.*Replying to You' 60 || fail "polish: A never saw B's swipe-reply quote (1:1)"
mshot dm-reply-quote
pass "polish: 1:1 Private: swipe right → quote bar → the reply quotes the message on the other phone"

# ---------------------------------------------------------------- 3. 1:1 Private: a link with its preview card
DEV=A; dm_private "ZZ Call B" || fail "polish: A back to the 1:1"
send_text "mp-link-1 https://example.org/agenda" || fail "polish: A can't send the link"
wait_node "Link preview: $OG_TITLE" 30 || fail "polish: no preview card on the sender"
mshot dm-link-card-sender
grep -q 'OGREQ /agenda' "$WORK/og.log" || fail "polish: the sender never fetched the page"
grep -q 'OGREQ /og.jpg' "$WORK/og.log" || warn "polish: the sender didn't fetch the og:image"
DEV=B; wait_node "Link preview: $OG_TITLE" 60 || fail "polish: no preview card on the receiver"
mshot dm-link-card-receiver
[ "$(grep -c 'OGREQ /agenda' "$WORK/og.log")" = 1 ] || fail "polish: the page was fetched more than once (a receiver fetched?)"
pass "polish: 1:1 Private: the link's preview card on both phones (fetched once, by the sender)"

# ---------------------------------------------------------------- 4. a group: swipe to reply and a link
DEV=A; back_to_list || true
for _ in $(seq 1 30); do
  dump
  if grep -qE '(text|content-desc)="New"' "$WORK/ui-$DEV.xml" && ! grep -qE '(text|content-desc)="New group"' "$WORK/ui-$DEV.xml"; then tap "New" || true; sleep 2; fi
  tap "New group" && break; sleep 2; back_to_list || true
done
wait_for "Add members" 15 || fail "polish: no New group"
sleep 2; tap "ZZ Call B" && tap "Next" && sleep 1 && type_into "Group name" "$GROUP" && hide_keyboard && tap "Create" || fail "polish: group creation"
ok=""; for _ in $(seq 1 120); do
  dump; grep -qF 'Risi listens in Official' "$WORK/ui-$DEV.xml" && { tap "OK" || true; }
  grep -qF 'Group info' "$WORK/ui-$DEV.xml" && { A shell input keyevent KEYCODE_BACK; sleep 1; continue; }
  group_private >/dev/null 2>&1 && { ok=1; break; }
  sleep 1
done
[ -n "$ok" ] || fail "polish: the group's composer never showed on A"
DEV=B; group_private || fail "polish: B can't open the group (Welcome?)"
DEV=A; group_private || fail "polish: A back to the group"
ok=""; for i in 1 2 3; do send_text "mp-group-$i" && { ok=$i; break; }; sleep 3; done
[ -n "$ok" ] || fail "polish: A can't send in the group"
DEV=B; wait_node "mp-group-$ok" 90 || fail "polish: B never got A's group message"
swipe_reply "mp-group-$ok" || fail "polish: B's swipe right in the group showed no reply bar"
send_text "mp-group-reply" || fail "polish: B can't send the group reply"
DEV=A; wait_node "Replying to You: mp-group-$ok.*mp-group-reply|mp-group-reply.*Replying to You" 60 || fail "polish: A never saw the group swipe-reply quote"
mshot group-reply-quote
send_text "mp-group-link https://example.org/group" || fail "polish: A can't send the group link"
wait_node "Link preview: $OG_TITLE.*mp-group-link|mp-group-link.*Link preview" 30 || wait_node "Link preview: $OG_TITLE" 5 || fail "polish: no card on the sender (group)"
DEV=B; wait_node "Link preview: $OG_TITLE" 60 || fail "polish: no card on the receiver (group)"
mshot group-link-card
pass "polish: group Private: swipe-reply quote and the link card on both phones"

# ---------------------------------------------------------------- 5. 1:1 Official: swipe to reply (no URL in Official)
DEV=A; official_on "ZZ Call B" || fail "polish: A can't open the 1:1's Official tab"
DEV=B; official_on "ZZ Call A" || fail "polish: B can't open the 1:1's Official tab"
DEV=A; send_text "mp-official-1" || fail "polish: A can't send in Official"
DEV=B; wait_node 'mp-official-1' 60 || fail "polish: B never got the Official message"
swipe_reply 'mp-official-1' || fail "polish: B's swipe right in Official showed no reply bar"
send_text "mp-official-reply" || fail "polish: B can't send the Official reply"
DEV=A; wait_node 'Replying to You: mp-official-1.*mp-official-reply|mp-official-reply.*Replying to You' 60 || fail "polish: A never saw the Official swipe-reply quote"
mshot official-reply-quote
pass "polish: 1:1 Official: swipe-reply quote on the other phone"

# ---------------------------------------------------------------- 6. the switch off: no fetch, no card
DEV=A; back_to_list || fail "polish: A back to the list"
tap "More options" && wait_for 'text="Settings"' 10 && tap "Settings" || fail "polish: A can't open Settings"
sleep 2
s="$(A shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"; W="${s%x*}"; H="${s#*x}"
ok=""
for _ in $(seq 1 14); do
  dump; b="$(node_box '^Link previews')"
  if [ -n "$b" ]; then
    read -r x y _x2 <<<"$b"
    if [ "$y" -ge $((H * 18 / 100)) ] && [ "$y" -le $((H * 78 / 100)) ]; then A shell input tap "$x" "$y"; ok=1; break; fi
  fi
  A shell input swipe $((W / 2)) $((H * 70 / 100)) $((W / 2)) $((H * 45 / 100)) 1200; sleep 0.8
done
[ -n "$ok" ] || fail "polish: no 'Link previews' row in Settings"
ok=""; for _ in $(seq 1 10); do A logcat -d 2>/dev/null | grep -qF "link previews: off" && { ok=1; break; }; sleep 1; done
[ -n "$ok" ] || fail "polish: the switch didn't turn off"
mshot settings-link-previews-off
reqs="$(grep -c '^OGREQ' "$WORK/og.log" || true)"
DEV=A; dm_private "ZZ Call B" || fail "polish: A back to the 1:1 (switch off)"
send_text "mp-off-1 https://example.org/off" || fail "polish: A can't send with the switch off"
wait_node 'mp-off-1.*(Sent|Delivered|Read)' 40 || fail "polish: the switch-off message never went out"
DEV=B; wait_node 'mp-off-1' 60 || fail "polish: B never got the switch-off message"
sleep 3
for DEV in A B; do
  dump; [ "$(count_nodes 'Link preview: [^,]*, [^,]*, mp-off-1|Link preview.*mp-off-1')" = 0 ] || fail "polish: $DEV shows a card with the switch off"
done
mshot dm-no-card
[ "$(grep -c '^OGREQ' "$WORK/og.log" || true)" = "$reqs" ] || fail "polish: the phone fetched with the switch off"
pass "polish: Link previews off: no fetch, no card on either phone"

# ---------------------------------------------------------------- 7. the server never saw a URL
grep -aqiE 'example\.org|og\.jpg|MP Test Page' "$WORK/server.log" && fail "polish: URL or preview text in the server log"
cat > "$WORK/mp-db.exs" <<'EXS'
needles = ["example.org", "MP Test Page", "og.jpg"] |> Enum.flat_map(&[&1, Base.encode16(&1, case: :lower)])
hit = fn text -> Enum.find(needles, &String.contains?(text, &1)) end
{:ok, _} = Application.ensure_all_started(:ecto_sql)
{:ok, _} = Application.ensure_all_started(:postgrex)
{:ok, _} = RisiMe.Repo.start_link(pool_size: 2)
%{rows: t} = RisiMe.Repo.query!("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1")
pg =
  for [table] <- t, reduce: 0 do
    n ->
      %{rows: rows} = RisiMe.Repo.query!(~s(SELECT t::text FROM "#{table}" t))
      for [r] <- rows, x = hit.(r), do: IO.puts("mpdb: FOUND #{x} in postgres #{table}")
      n + length(rows)
  end
{:ok, _} = Application.ensure_all_started(:xandra)
ks = System.fetch_env!("RISIME_DEV_KEYSPACE")
String.starts_with?(ks, "risime_calltest") || raise "refusing #{ks}"
[node | _] = Application.fetch_env!(:risime, :cassandra)[:nodes]
{:ok, conn} = Xandra.start_link(nodes: [node])
{:ok, page} = Xandra.execute(conn, "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?", [{"text", ks}])
cass =
  for %{"table_name" => table} <- Enum.to_list(page), reduce: 0 do
    n ->
      {:ok, p} = Xandra.execute(conn, "SELECT * FROM #{ks}.#{table}", [], page_size: 5_000)
      rows = Enum.to_list(p)
      for r <- rows, x = hit.(inspect(r, limit: :infinity, printable_limit: :infinity)), do: IO.puts("mpdb: FOUND #{x} in cassandra #{table}")
      n + length(rows)
  end
IO.puts("mpdb: rows postgres=#{pg} cassandra=#{cass}")
EXS
dbout="$(cd server && $M mix run --no-start "$WORK/mp-db.exs" 2>&1 | grep '^mpdb:' || true)"
echo "$dbout" | sed 's/^/    /'
grep -q '^mpdb: rows' <<<"$dbout" || fail "polish: the database check didn't run"
grep -q '^mpdb: FOUND' <<<"$dbout" && fail "polish: URL or preview text in the server database"
cr="$(sed -nE 's/^mpdb: rows postgres=[0-9]+ cassandra=([0-9]+)/\1/p' <<<"$dbout")"
[ "${cr:-0}" -gt 0 ] || fail "polish: positive control: no Cassandra rows were read"
pass "polish: no URL or preview text in the server log, Postgres or Cassandra (ciphertext only)"
logcat_ok "polish"
pass "polish: PART 11 device gate"
