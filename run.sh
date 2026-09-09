#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run.sh — build CalcPlus and run it on an Android emulator or a plugged-in
# device. Written for the WSL2 + ~/android-toolchain setup in the README.
#
#   ./run.sh                 build, start the emulator (windowed), install, launch
#   ./run.sh --headless      no emulator window (use with --screenshot / --logcat)
#   ./run.sh --no-build      skip the Gradle build, just (re)install what's there
#   ./run.sh --release       build & install the release APK instead of debug
#   ./run.sh --logcat        follow the app's log after launching (Ctrl-C to stop)
#   ./run.sh --screenshot [f] grab a PNG (default: ./calcplus.png) and exit
#   ./run.sh --stop          shut the emulator down
#
# A physical device connected over adb is always preferred over the emulator.
# ---------------------------------------------------------------------------
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLCHAIN="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"
AVD="${CALCPLUS_AVD:-calc36}"
SYSIMG="system-images;android-36;google_apis;x86_64"
PKG_DEBUG="io.github.kmarzouq.calcplus.debug"
PKG_RELEASE="io.github.kmarzouq.calcplus"
MAIN_CLASS="io.github.kmarzouq.calcplus.MainActivity"
EMU_LOG="/tmp/calcplus-emu.log"
GPU="${CALCPLUS_GPU:-swiftshader_indirect}"

HEADLESS=0 BUILD=1 RELEASE=0 LOGCAT=0 SHOT="" STOP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --headless)   HEADLESS=1 ;;
    --no-build)   BUILD=0 ;;
    --release)    RELEASE=1 ;;
    --logcat)     LOGCAT=1 ;;
    --screenshot) case "${2:-}" in ""|-*) SHOT="$REPO/calcplus.png" ;; *) SHOT="$2"; shift ;; esac ;;
    --stop|--kill) STOP=1 ;;
    -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

# --- toolchain env --------------------------------------------------------
[ -f "$TOOLCHAIN/env.sh" ] && source "$TOOLCHAIN/env.sh"
: "${ANDROID_HOME:=$TOOLCHAIN/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

step() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[ -x "$ADB" ] || die "adb not found at $ADB — check \$ANDROID_HOME / run the toolchain bootstrap (README)."
"$ADB" start-server >/dev/null 2>&1 || true

# --- --stop --------------------------------------------------------------
if [ "$STOP" = 1 ]; then
  "$ADB" -e emu kill 2>/dev/null || true
  pkill -9 -f 'qemu-system-x86_64' 2>/dev/null || true
  step "Emulator stopped."
  exit 0
fi

# --- find a target: real device beats emulator -------------------------
first_device() { "$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}'; }
TARGET="$(first_device || true)"

if [ -z "$TARGET" ]; then
  [ -x "$EMULATOR" ] || die "No device connected and the emulator isn't installed.
  sdkmanager \"emulator\" \"$SYSIMG\""
  if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD"; then
    step "Creating AVD '$AVD' ($SYSIMG)…"
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "$SYSIMG" >/dev/null
    echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$SYSIMG" -d pixel_7 --force
  fi

  EMU_ARGS=(-avd "$AVD" -gpu "$GPU" -no-boot-anim -no-snapshot)
  [ "$HEADLESS" = 1 ] && EMU_ARGS+=(-no-window -no-audio)
  if [ "$HEADLESS" = 0 ] && [ ! -d /mnt/wslg ] && [ -z "${DISPLAY:-}" ]; then
    step "No GUI display (WSLg) detected — falling back to --headless."
    EMU_ARGS+=(-no-window -no-audio); HEADLESS=1
  fi

  step "Starting emulator ($AVD)…"
  if [ -w /dev/kvm ]; then
    setsid "$EMULATOR" "${EMU_ARGS[@]}" >"$EMU_LOG" 2>&1 &
    disown 2>/dev/null || true
  elif getent group kvm 2>/dev/null | grep -qE "[:,]$USER(,|$)"; then
    # Member of the kvm group but this login shell hasn't picked it up
    # (needs `wsl --shutdown` once). Re-exec the emulator under the group.
    echo "   (kvm access not active in this shell — relaunching via newgrp;"
    echo "    do 'wsl --shutdown' from Windows once to make this instant)"
    setsid newgrp kvm >"$EMU_LOG" 2>&1 <<EOF &
exec "$EMULATOR" ${EMU_ARGS[@]}
EOF
    disown 2>/dev/null || true
  else
    step "!! You are not in the 'kvm' group — the emulator will be painfully slow."
    echo "   Fix once:  sudo usermod -aG kvm \$USER"
    echo "   then from Windows PowerShell:  wsl --shutdown   and reopen this terminal."
    setsid "$EMULATOR" "${EMU_ARGS[@]}" -accel off >"$EMU_LOG" 2>&1 &
    disown 2>/dev/null || true
  fi

  printf '   booting'
  for _ in $(seq 1 150); do "$ADB" devices | grep -q '^emulator-' && break; printf .; sleep 2; done
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do printf .; sleep 2; done
  printf ' ready\n'
  # calmer emulator: no animations
  for s in window transition animator; do "$ADB" shell settings put global ${s}_animation_scale 0 2>/dev/null || true; done
  TARGET="$(first_device)"
fi
step "Target: $TARGET"

# --- build -------------------------------------------------------------
if [ "$RELEASE" = 1 ]; then
  PKG="$PKG_RELEASE"
  APK="$REPO/android/app/build/outputs/apk/release/app-release.apk"
  [ -f "$APK" ] || APK="$REPO/android/app/build/outputs/apk/release/app-release-unsigned.apk"
  GRADLE_TASK=":app:assembleRelease"
else
  PKG="$PKG_DEBUG"
  APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
  GRADLE_TASK=":app:assembleDebug"
fi

if [ "$BUILD" = 1 ]; then
  command -v cargo >/dev/null || die "cargo not on PATH — source $TOOLCHAIN/env.sh or install Rust (README)."
  step "Building $GRADLE_TASK (Rust core + Kotlin)…"
  ( cd "$REPO/android" && ./gradlew "$GRADLE_TASK" -q --console=plain )
fi
[ -f "$APK" ] || die "APK not found: $APK  (drop --no-build to build it)"

# --- install + launch ------------------------------------------------
step "Installing $(basename "$APK")…"
"$ADB" -s "$TARGET" install -r "$APK" >/dev/null
step "Launching $PKG/$MAIN_CLASS …"
"$ADB" -s "$TARGET" shell am start -n "$PKG/$MAIN_CLASS" >/dev/null

if [ -n "$SHOT" ]; then
  sleep 2
  "$ADB" -s "$TARGET" exec-out screencap -p > "$SHOT"
  step "Screenshot: $SHOT"
  exit 0
fi

if [ "$LOGCAT" = 1 ]; then
  PID="$("$ADB" -s "$TARGET" shell pidof "$PKG" | tr -d '\r')"
  step "logcat for pid $PID (Ctrl-C to stop)…"
  exec "$ADB" -s "$TARGET" logcat --pid="$PID"
fi

step "Running on $TARGET."
if [ "$HEADLESS" = 1 ]; then
  echo "   headless — grab the screen with:  ./run.sh --no-build --screenshot"
  echo "   or follow logs with:               ./run.sh --no-build --logcat"
fi
echo "   emulator log: $EMU_LOG   ·   stop it with: ./run.sh --stop"
