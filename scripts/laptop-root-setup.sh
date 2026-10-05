#!/usr/bin/env bash
# Run on the laptop:  sudo bash scripts/laptop-root-setup.sh   (then log out and back in)
set -euo pipefail
U="${SUDO_USER:?Run with sudo}"
apt-get update
apt-get install -y build-essential autoconf m4 libncurses-dev libssl-dev unzip curl git \
  tmux jq inotify-tools openssl cpu-checker
apt-get install -y android-sdk-platform-tools-common || echo "WARN: udev rules package not found; USB adb may need manual udev rules"
groupadd -f plugdev
usermod -aG kvm,plugdev "$U"
kvm-ok || echo "WARN: KVM not available; the emulator will be slow. Enable VT-x in BIOS."
echo "Done. Log out and back in."
