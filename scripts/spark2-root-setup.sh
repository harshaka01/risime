#!/usr/bin/env bash
# Run ON spark2:  sudo bash ~/development/risime/scripts/spark2-root-setup.sh
set -euo pipefail
U="${SUDO_USER:?Run with sudo from the harsha account}"

echo "==> Packages"
apt-get update
apt-get install -y build-essential autoconf m4 libncurses-dev libssl-dev unzip curl git \
  tmux inotify-tools jq ufw fail2ban ca-certificates openssl

echo "==> Docker"
command -v docker >/dev/null || { echo "Docker missing: install Docker Engine first (DGX OS normally ships it)."; exit 1; }
docker compose version >/dev/null 2>&1 || apt-get install -y docker-compose-plugin
usermod -aG docker "$U"      # takes effect on your next login

echo "==> Git origin"
install -d -o "$U" -g "$U" /srv/git /srv/git/locks
[ -d /srv/git/risime.git ] || sudo -u "$U" git init --bare -b main /srv/git/risime.git

echo "==> Firewall (SSH only) + fail2ban"
ufw allow OpenSSH
ufw --force enable
systemctl enable --now fail2ban

echo "Done. Log out of spark2 and back in so the docker group applies."
