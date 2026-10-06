# 045 — The upgrade gate runs unattended on spark2 in redroid

- **Status:** accepted (2026-10-06); active once the binder driver is loaded on spark2 (one sudo step below)
- **Decided by:** root session (follow-up from decision 044)

## Context
`scripts/upgrade-test` (the release gate since the nightly.10 data loss) needs an Android device.
Until now that meant Harsha's laptop emulator, because spark2 is aarch64. Options checked on spark2
(kernel 7.0.0-1019-nvidia, Ubuntu 24.04, no sudo):

| Option | Finding |
|---|---|
| Google Android Emulator for linux-aarch64 | Not published. `repository2-3.xml` (emulator 37.2/37.3) lists linux **x64** only (aarch64 only for macOS); `emulator-linux_aarch64-*.zip` returns 404. |
| Cuttlefish arm64 (crosvm/KVM) | Needs `/dev/kvm`: owned by `root:kvm` 0660 (ACL only for gdm); harsha is not in `kvm`. Also a heavy host install (debs, vhost devices, CI images). |
| QEMU TCG (no KVM) | No qemu-system on the host; booting goldfish images on upstream QEMU is fragile and slow. Rejected. |
| **redroid** (AOSP in Docker, `redroid/redroid:14.0.0_64only-latest`, arm64) | Docker works for harsha. Native arm64, so no KVM and no translation. adb over TCP, `adb root` (userdebug). Needs the kernel binder driver: `CONFIG_ANDROID_BINDER_IPC=m`, `CONFIG_ANDROID_BINDERFS=m`, `binder_linux.ko` present but **not loaded** (no `binder` in `/proc/filesystems`, no `/dev/binder`). ashmem is not needed by Android 14 (memfd). |
| arm64 adb | The SDK `platform-tools/adb` on spark2 is x86-64. Ubuntu's arm64 `adb` 34.0.4 (+ its private libs) unpacks into `~/.local/opt/adb-arm64` without root (`apt-get download` + `dpkg-deb -x`) and runs. |
| Laptop timer | Works, but still needs the laptop switched on. Kept only as the fallback (`--fetch-candidate`). |

The release APKs carry arm64-v8a, armeabi-v7a, x86 and x86_64 native libs (`libuniffi_risime.so` etc.),
so they install on an arm64-only redroid image.

## Decision
- spark2 runs the gate in **redroid**. `scripts/android-target start|stop|check|adb` manages a
  throwaway container `risime-redroid` (`--privileged`, published on **127.0.0.1:5655 only**,
  CLAUDE.md rule 6; no volume, so `/data` goes with the container) and the user-space arm64 adb.
- `scripts/upgrade-test --target auto|redroid|emulator|device`: `auto` uses an attached device if
  one exists, else redroid on spark2. With redroid the script starts the container, runs the test
  against `127.0.0.1:5655`, and removes the container on every exit. Room/DataStore checks run
  because redroid allows `adb root`.
- `scripts/nightly-release` runs `scripts/upgrade-test --target redroid` itself when
  `scripts/android-target check` passes. Otherwise it prints why and stages the candidate for the
  laptop (`scripts/upgrade-test --fetch-candidate`), as before.

## One-time sudo step (Harsha)
```
sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
echo binder_linux | sudo tee /etc/modules-load.d/redroid.conf
echo 'options binder_linux devices="binder,hwbinder,vndbinder"' | sudo tee /etc/modprobe.d/redroid.conf
```
This loads the in-tree binder driver now and on every boot. It adds no users or groups, and opens no ports.

## Consequences
- No human is needed for the gate once binder is loaded. The laptop emulator path keeps working.
- redroid is AOSP, not a Google phone image: no Play services, so FCM push isn't exercised. The test
  doesn't need push.
- `--privileged` containers are root-equivalent on the host. The image is the public redroid
  project's image, pinned by tag. Re-pin it, or pin it by digest, when it is updated.
