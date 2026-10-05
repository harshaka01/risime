#!/usr/bin/env bash
# Run ON spark2 only AFTER key login works:  sudo bash /tmp/spark2-harden-ssh.sh
set -euo pipefail
cat > /etc/ssh/sshd_config.d/99-risime-hardening.conf <<'CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
CONF
sshd -t
systemctl reload ssh 2>/dev/null || systemctl reload sshd
echo "SSH now key-only. Test a NEW login before closing this session."
