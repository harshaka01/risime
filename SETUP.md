# RisiMe — Setup Guide (status as of today)

## Layout
- **spark2** (DGX Spark, ARM64, `ssh spark2` = 203.115.26.139, hostname `gx10-e23a`): all
  development runs here as `harsha` in `/home/harsha/development/risime`.
- **Laptop** (`/home/harsha/development/risime`): the Git mirror, emulator, USB phone and tunnel.
  Laptop CC can reach both `spark` and `spark2`.
- **spark** (`ssh spark`): not used by RisiMe.

## Done
spark2 has its system packages, firewall, fail2ban, Docker group, Git origin, Erlang/Elixir/
Phoenix, Java/Gradle, Android SDK, arm64 aapt2 and the Gradle override in place. SSH keys work
from the laptop to both Sparks.

## Next
1. **Lock down spark2 SSH** (key-only login; your key already works):
   ```bash
   ssh -t spark2 'printf "PasswordAuthentication no\nKbdInteractiveAuthentication no\nPermitRootLogin no\n" | sudo tee /etc/ssh/sshd_config.d/99-risime-hardening.conf && sudo sshd -t && sudo systemctl reload ssh'
   ssh spark2 'echo still ok'     # test in a NEW terminal before closing the old one
   ```
2. **Laptop sudo setup** (emulator/KVM + USB adb), then log out and back in:
   ```bash
   cd ~/development/risime && sudo bash scripts/laptop-root-setup.sh
   ```
3. **Bootstrap with laptop CC:**
   ```bash
   cd ~/development/risime && claude
   ```
   Say: **"Read prompts/00-bootstrap-from-laptop.md and execute it."**
4. **Log in to Claude Code on spark2 once** (the bootstrap installs it):
   ```bash
   ssh spark2
   claude          # follow the login link, then exit
   ```
5. **Start the project on spark2:**
   ```bash
   ssh spark2
   tmux new -s cc-root
   cd ~/development/risime && claude    # "Read prompts/00-root.md and execute it."
   ```
   Root then tells you when to open windows 1 (server) and 2 (android) with `Ctrl+B` then `C`.
   - Detach: `Ctrl+B` then `D`. Reattach: `ssh spark2`, then `tmux attach -t cc-root`.

## Testing two people chatting (laptop)
```bash
ssh -N spark2-tunnel &
cd ~/development/risime && git pull && scripts/install-apk
```
- The tunnel forwards laptop port **4400** to spark2's `127.0.0.1:4000` (`LocalForward 4400
  127.0.0.1:4000`), because laptop port 4000 is taken by another project.
- **Emulator** (`emulator -avd risime_a`): server URL `http://10.0.2.2:4400` (the debug default)
- **USB phone**: run `adb reverse tcp:4000 tcp:4400`, then use server URL `http://127.0.0.1:4000`
- Login codes appear in the server log on spark2: `tmux attach -t risime-server`

## Important
- Release 0.1 is **not end-to-end encrypted yet** (that comes in 0.3). Send test messages only.
- Create a Firebase project before Release 0.2 (push notifications).
