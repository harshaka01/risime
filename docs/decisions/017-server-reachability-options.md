# 017 — How phones reach the RisiMe server (options; **Harsha decides**)

**Status: decided 2026-10-06. Harsha chose option 2b.** Caddy on spark serves
`https://risicloud.ai/risime/` by proxying over the tailnet to spark2's `tailscale serve`. spark2
stays bound to 127.0.0.1, and the broken LAN path doesn't matter. The config is prepared in
`infra/risicloud/` but **not switched on** until the RisiCloud lead adds the route and spark joins
the tailnet. Until then, Harsha tests with option 1 (Tailscale on the phone).

Today the server binds `127.0.0.1:4000` on spark2 (golden rule 6). Phones reach it only through
the laptop tunnel / `adb reverse`, which doesn't work for testers away from the laptop.

| | Option | Phones need | Changes on spark2 | Pros | Cons |
|---|---|---|---|---|---|
| 1 | **Tailscale serve** `https://spark2.<tailnet>.ts.net` | The Tailscale app, joined to Harsha's tailnet | Install Tailscale (sudo); `tailscale serve` → 127.0.0.1:4000 | No public exposure; the bind stays 127.0.0.1; real TLS; ready in minutes | Every tester joins the tailnet (onboarding friction, per-device admin); a different host from risicloud.ai |
| 2 | **Caddy on spark** proxies `https://risicloud.ai/risime/` → spark2 over the LAN, `10.20.20.15:4000` | Nothing | Bind the server to the LAN interface (an explicit exception to rule 6); ufw allows 4000 **only from 10.20.20.14** (sudo) | Same domain and TLS as Keycloak and the APK page; zero tester setup; one place for WebSocket/HTTP policy | **The LAN path is down today:** 10.20.20.14 is unreachable from spark2 (ARP incomplete). It needs the network team. spark becomes part of RisiMe's data path. |
| 2b | Like 2, but Caddy → spark2 **over the tailnet** (spark and spark2 both on the tailnet; spark2 keeps 127.0.0.1 plus `tailscale serve`) | Nothing | Tailscale on both servers | Same as 2, without rebinding or ufw changes on spark2, and independent of the broken LAN | Tailscale on spark (RisiCloud's call) |
| 3 | **A separate domain on spark2**, such as `risime.codegen.co.uk`, with its own TLS (Caddy on spark2) | Nothing | A public 443 listener on spark2, DNS and certificates | Independent of spark | Exposes spark2, which hosts the DBs, to the internet; more to harden and patch |

The path prefix works with the current clients: the Android `ApiClient` and the socket URL append
their paths to the configured base URL, so `https://risicloud.ai/risime` works as is.
Caddy needs `handle_path /risime/*` plus `reverse_proxy`; WebSocket upgrades pass through by
default.

**Recommendation: option 2.** If the LAN path can't be fixed quickly, choose **2b**.
- Testers like books@codegen.co.uk install from risicloud.ai and sign in through risicloud.ai.
  With option 2 they need no other setup, which is what the "proof of done" requires.
- Option 1 forces each tester onto the tailnet.
- Option 3 widens spark2's public attack surface for no extra benefit.
- 2b keeps spark2's bind rule intact and doesn't depend on the LAN.
- Either way, **keep option 1 for Harsha's own testing.**
