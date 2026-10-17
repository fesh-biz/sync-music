#!/usr/bin/env bash
# Starts an Android emulator on a GitHub Linux runner and waits for it to boot.
# Everything is logged to e2e-out/ so failures can be diagnosed from the ci-logs branch.
set -u
OUT=e2e-out
mkdir -p "$OUT"
DIAG="$OUT/emulator-diag.txt"
API=33
IMAGE="system-images;android-${API};google_apis;x86_64"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
TOOLS="$SDK/cmdline-tools/latest/bin"
[ -x "$TOOLS/sdkmanager" ] || TOOLS="$(dirname "$(command -v sdkmanager)")"

{
  echo "== kvm"; ls -l /dev/kvm; grep -Eo 'vmx|svm' /proc/cpuinfo | sort | uniq -c
  echo "== cpu/mem"; nproc; free -g; df -h / | tail -1
  echo "== sdk: $SDK tools: $TOOLS"
} > "$DIAG" 2>&1

yes | "$TOOLS/sdkmanager" --licenses > /dev/null 2>&1
"$TOOLS/sdkmanager" --install "emulator" "platform-tools" "$IMAGE" >> "$DIAG" 2>&1
echo "== accel-check" >> "$DIAG"
"$SDK/emulator/emulator" -accel-check >> "$DIAG" 2>&1

echo no | "$TOOLS/avdmanager" create avd -n e2e -k "$IMAGE" -d pixel_6 --force >> "$DIAG" 2>&1
CFG="$HOME/.android/avd/e2e.avd/config.ini"
{
  echo "hw.ramSize=4096"
  echo "disk.dataPartition.size=6000M"
  echo "hw.cpu.ncore=4"
  echo "hw.keyboard=yes"
} >> "$CFG"

export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
adb start-server >> "$DIAG" 2>&1
nohup "$SDK/emulator/emulator" -avd e2e -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim \
  -camera-back none -camera-front none -no-snapshot -accel on > "$OUT/emulator.log" 2>&1 &
echo $! > "$OUT/emulator.pid"

start=$(date +%s)
booted=0
while [ $(( $(date +%s) - start )) -lt 900 ]; do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then booted=1; break; fi
  if ! kill -0 "$(cat "$OUT/emulator.pid")" 2>/dev/null; then echo "emulator process exited" >> "$DIAG"; break; fi
  sleep 5
done
echo "== booted=$booted after $(( $(date +%s) - start )) s" >> "$DIAG"
adb devices >> "$DIAG" 2>&1
[ "$booted" = "1" ] || exit 1
# Let the system settle (package manager, Wi-Fi).
sleep 20
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82 >/dev/null 2>&1 || true
exit 0
