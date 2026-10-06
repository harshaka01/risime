# 015 — The spark2 → spark link: upload-only, and nothing sensitive crosses

**Status:** accepted 2026-10-06 (Harsha, amendments D and G).

## The link
- **Key:** `~/.ssh/spark1` on spark2 (ed25519, no passphrase, comment `risime-release@spark2`).
  The private key never leaves spark2.
- `Host spark1` in `~/.ssh/config`:
  - `IdentitiesOnly`, `BatchMode`, and no agent/X11/port forwarding;
  - the host key is pinned in `~/.ssh/known_hosts.spark1` with `StrictHostKeyChecking yes`.
  - spark's ed25519 host key as seen from spark2 is
    `SHA256:i9+taML/Qc9p3yDYo3VkhJK06za6ZtqeDxmFl5ArwZQ`. **Harsha should verify it on spark**
    with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.
- **Address:**
  - the plan was the LAN address `10.20.20.14` until the network team opens `203.189.69.77:22`;
  - on 2026-10-06, `10.20.20.14` was **not reachable** from spark2 (ARP incomplete, no ping, 22/80/443
    filtered), while **`203.189.69.77:22` was already open**. So `spark1` uses the public address.
  - spark2's outside IP is **`203.115.26.139`**; its LAN address is `10.20.20.15/26`.
- **On spark**, `~/.ssh/authorized_keys` of the account that owns `~/accounts/releases/risime/`
  restricts the key to rrsync, write-only, in that one directory:
  ```
  restrict,command="/usr/bin/rrsync -wo accounts/releases/risime" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAazfBeQXVvbymXgWbu8SUykNu3CDLipNmQHDCHpiYME risime-release@spark2
  ```
  - `restrict` turns off the pty and port, agent and X11 forwarding.
  - The forced command allows no shell and no other commands.
  - `rrsync -wo` allows uploads only (no reads or downloads). The path is relative to that
    account's home.
  - rrsync is `/usr/bin/rrsync`, shipped by the `rsync` package on Ubuntu 22.04+. On older
    releases it is `/usr/share/doc/rsync/scripts/rrsync` (gzipped) and must be installed by hand.
    It needs python3 on spark.

## What may cross (amendment G)
- **Allowed:**
  - spark2 → spark: the signed APKs, `version.json` and the static "Get RisiMe" `index.html`,
    by rsync;
  - spark2 → risicloud.ai: read-only HTTPS for OIDC discovery and JWKS;
  - phones → spark: the Keycloak sign-in and the APK/`version.json` downloads.
- **Never across:**
  - database access in either direction (no Postgres/Cassandra ports, no dumps; backups go to the
    separate off-box host of decision 010, never spark);
  - secrets and `.env` / `.env.prod` contents;
  - Keycloak admin credentials (RisiMe never calls the admin API; the client `risime` is created
    by the RisiCloud lead);
  - the release keystore (`~/risime-keys`).
- Scripts that use `spark1` call only `rsync` into that directory. Anything else is refused on
  spark anyway, by the forced command.
