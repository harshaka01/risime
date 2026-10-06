> **Obsolete (2026-10-06, decision 023):** Tailscale is dropped. RisiMe is served from spark2 at
> https://risime.risicloud.ai via Caddy. This page is kept only as history.

# Dev server on the tailnet (spark2)

**Goal:** team phones on Harsha's tailnet reach the dev server at
`https://spark2.<tailnet>.ts.net`. Phoenix stays bound to `127.0.0.1:4000`. `tailscale serve`
terminates HTTPS on the tailnet interface and proxies to loopback. This is not Funnel, so nothing
is exposed to the public internet.

## 1. Install and log in (once, needs sudo, Harsha)
```bash
curl -fsSL https://tailscale.com/install.sh | sudo sh
sudo tailscale up --hostname=spark2 --operator=harsha
```
`tailscale up` prints a login URL. Open it and approve the machine.
`--operator=harsha` lets `harsha` run `tailscale serve` without sudo afterwards.

## 2. Tailnet settings (admin console, once)
In https://login.tailscale.com/admin/dns, enable **MagicDNS** and **HTTPS Certificates**.

## 3. Expose the dev server (as harsha, no sudo)
```bash
tailscale serve --bg --https=443 http://127.0.0.1:4000
tailscale serve status        # shows https://spark2.<tailnet>.ts.net/ -> http://127.0.0.1:4000
```
The config persists across reboots. To remove it: `tailscale serve --https=443 off`.

## 4. Check
From any tailnet device (for example the laptop with Tailscale running):
```bash
curl -i https://spark2.<tailnet>.ts.net/api/v1/me      # expect 401 {"error":{"code":"unauthorized",…}}
```
If it hangs, ufw on spark2 is probably dropping tailnet traffic to the serve port. Allow it on
the tailscale interface only:
```bash
sudo ufw allow in on tailscale0 to any port 443 proto tcp
```

## 5. Phones
- Install the Tailscale app and log in to the same tailnet.
- In RisiMe: Settings (or the Server link on the login screen) → server URL
  `https://spark2.<tailnet>.ts.net`.
- WebSockets go through `tailscale serve` unchanged (`wss://…/socket/websocket`).
- The laptop `spark2-tunnel` (`http://10.0.2.2:4400` in the emulator) keeps working as before.

## Notes
- Still the dev server, dev data and the dev OTP log: same caveats as `docs/status/server.md`.
- The server binds only to 127.0.0.1 (golden rule 6), so `tailscale serve` is the only way in
  from the tailnet.
