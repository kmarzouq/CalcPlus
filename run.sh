#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run.sh — build CalcPlus and run it on an Android emulator or a plugged-in
# device. Written for the WSL2 + ~/android-toolchain setup in the README.
#
#   ./run.sh                  build, start the emulator (windowed), install, launch
#   ./run.sh --headless       no emulator window (pair with --screenshot / --logcat)
#   ./run.sh --no-build       skip Gradle, just (re)install the last build
#   ./run.sh --release        build & install the release APK instead of debug
#   ./run.sh --clean          wipe the app's data first
#   ./run.sh --logcat         follow the app's log after launching (Ctrl-C to stop)
#   ./run.sh --screenshot [f] grab a PNG (default: ./calcplus.png) and exit
#   ./run.sh --stop           shut the emulator down
#
# A phone connected over adb is always preferred over the emulator.
# ---------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLCHAIN="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"
AVD="${CALCPLUS_AVD:-calc36}"
SYSIMG="system-images;android-36;google_apis;x86_64"
PKG_DEBUG="io.github.kmarzouq.calcplus.debug"
PKG_RELEASE="io.github.kmarzouq.calcplus"
MAIN_CLASS="io.github.kmarzouq.calcplus.MainActivity"
EMU_LOG="${TMPDIR:-/tmp}/calcplus-emu.log"
GPU="${CALCPLUS_GPU:-swiftshader_indirect}"
BOOT_TIMEOUT="${CALCPLUS_BOOT_TIMEOUT:-300}"   # seconds

HEADLESS=0 BUILD=1 RELEASE=0 LOGCAT=0 SHOT="" STOP=0 CLEAN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --headless)    HEADLESS=1 ;;
    --no-build)    BUILD=0 ;;
    --release)     RELEASE=1 ;;
    --clean)       CLEAN=1 ;;
    --logcat)      LOGCAT=1 ;;
    --screenshot)  case "${2:-}" in ""|-*) SHOT="$REPO/calcplus.png" ;; *) SHOT="$2"; shift ;; esac ;;
    --stop|--kill) STOP=1 ;;
    -h|--help)     sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

step() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

# --- toolchain env -------------------------------------------------------
[ -f "$TOOLCHAIN/env.sh" ] && source "$TOOLCHAIN/env.sh"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$TOOLCHAIN/sdk}}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
CMDTOOLS="$ANDROID_HOME/cmdline-tools/latest/bin"

[ -x "$ADB" ] || die "adb not found at $ADB
  \$ANDROID_HOME is '$ANDROID_HOME'. Run the toolchain bootstrap (see README) or
  set ANDROID_HOME / ANDROID_TOOLCHAIN."
"$ADB" start-server >/dev/null 2>&1 || true

# --- --stop -------------------------------------------------------------
if [ "$STOP" = 1 ]; then
  "$ADB" devices | awk 'NR>1 && $1 ~ /^emulator-/{print $1}' | while read -r e; do
    "$ADB" -s "$e" emu kill >/dev/null 2>&1 || true
  done
  pkill -9 -f 'qemu-system-x86_64' 2>/dev/null || true
  step "Emulator stopped."
  exit 0
fi

# --- pick a target -----------------------------------------------------
# any adb line whose state is exactly "device"
online_device() { "$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}'; }
# any emulator line at all (device / offline / booting)
any_emulator()  { "$ADB" devices | awk 'NR>1 && $1 ~ /^emulator-/{print $1; exit}'; }

TARGET="$(online_device || true)"

if [ -z "$TARGET" ]; then
  [ -x "$EMULATOR" ] || die "No device connected and the emulator is not installed:
  sdkmanager \"emulator\" \"$SYSIMG\""

  if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD"; then
    step "Creating AVD '$AVD' ($SYSIMG)…"
    "$CMDTOOLS/sdkmanager" "$SYSIMG" >/dev/null || die "sdkmanager could not fetch $SYSIMG"
    echo no | "$CMDTOOLS/avdmanager" create avd -n "$AVD" -k "$SYSIMG" -d pixel_7 --force \
      || die "avdmanager could not create the AVD"
  fi

  # An emulator already running but not "device" yet? just wait on it.
  if [ -z "$(any_emulator || true)" ]; then
    EMU_ARGS=(-avd "$AVD" -gpu "$GPU" -no-boot-anim -no-snapshot)
    [ "$HEADLESS" = 1 ] && EMU_ARGS+=(-no-window -no-audio)
    if [ "$HEADLESS" = 0 ] && [ ! -d /mnt/wslg ] && [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
      warn "No GUI display (WSLg / X / Wayland) detected — running headless."
      EMU_ARGS+=(-no-window -no-audio); HEADLESS=1
    fi

    : > "$EMU_LOG"
    step "Starting emulator ($AVD)  [log: $EMU_LOG]"
    if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
      setsid "$EMULATOR" "${EMU_ARGS[@]}" >>"$EMU_LOG" 2>&1 &
    elif getent group kvm 2>/dev/null | grep -qE "(:|,)$USER(,|$)"; then
      warn "kvm access isn't active in this shell — relaunching via newgrp."
      echo "     Run 'wsl --shutdown' from Windows PowerShell once to make this instant."
      _boot="$(mktemp)"; printf 'exec %q %s\n' "$EMULATOR" "${EMU_ARGS[*]}" > "$_boot"
      setsid newgrp kvm < "$_boot" >>"$EMU_LOG" 2>&1 &
      ( sleep 20; rm -f "$_boot" ) &
    else
      warn "You are not in the 'kvm' group — the emulator will be extremely slow."
      echo "     Fix once:  sudo usermod -aG kvm \$USER   then  wsl --shutdown  (Windows)."
      setsid "$EMULATOR" "${EMU_ARGS[@]}" -accel off >>"$EMU_LOG" 2>&1 &
    fi
    disown 2>/dev/null || true
  else
    step "An emulator is already running — waiting for it."
  fi

  # --- wait for boot, with a real timeout ---
  deadline=$(( $(date +%s) + BOOT_TIMEOUT ))
  printf '   booting'
  while :; do
    e="$(any_emulator || true)"
    if [ -n "$e" ] && [ "$("$ADB" -s "$e" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
      TARGET="$e"; break
    fi
    if ! pgrep -f 'qemu-system-x86_64' >/dev/null 2>&1 && [ -z "$e" ]; then
      printf '\n'
      warn "The emulator process exited. Last lines of $EMU_LOG:"
      tail -n 20 "$EMU_LOG" >&2
      die "Emulator failed to start. Common fixes: 'wsl --shutdown' for KVM, or set CALCPLUS_GPU=swiftshader_indirect, or run with --headless."
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      printf '\n'
      warn "Timed out after ${BOOT_TIMEOUT}s. Last lines of $EMU_LOG:"
      tail -n 20 "$EMU_LOG" >&2
      die "Emulator did not finish booting. Try 'wsl --shutdown', or raise CALCPLUS_BOOT_TIMEOUT."
    fi
    printf .; sleep 3
  done
  printf ' ready\n'
  for s in window transition animator; do
    "$ADB" -s "$TARGET" shell settings put global "${s}_animation_scale" 0 2>/dev/null || true
  done
fi

step "Target: $TARGET"
ADBT=("$ADB" -s "$TARGET")

# --- build ------------------------------------------------------------
if [ "$RELEASE" = 1 ]; then
  PKG="$PKG_RELEASE"; GRADLE_TASK=":app:assembleRelease"
  APK="$REPO/android/app/build/outputs/apk/release/app-release.apk"
  [ -f "$APK" ] || APK="$REPO/android/app/build/outputs/apk/release/app-release-unsigned.apk"
else
  PKG="$PKG_DEBUG"; GRADLE_TASK=":app:assembleDebug"
  APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
fi

if [ "$BUILD" = 1 ]; then
  command -v cargo >/dev/null 2>&1 || die "cargo not on PATH — 'source $TOOLCHAIN/env.sh' or install Rust (README)."
  step "Building $GRADLE_TASK  (Rust core + Kotlin)…"
  ( cd "$REPO/android" && ./gradlew "$GRADLE_TASK" --console=plain ) \
    || die "Gradle build failed (see the output above)."
fi
[ -f "$APK" ] || die "APK not found: $APK
  Build it first — run without --no-build."

# --- install + launch ----------------------------------------------
[ "$CLEAN" = 1 ] && { step "Clearing app data…"; "${ADBT[@]}" shell pm clear "$PKG" >/dev/null 2>&1 || true; }
step "Installing $(basename "$APK")…"
"${ADBT[@]}" install -r "$APK" >/dev/null || die "adb install failed."
step "Launching…"
"${ADBT[@]}" shell am start -n "$PKG/$MAIN_CLASS" >/dev/null || die "Could not launch the activity."

if [ -n "$SHOT" ]; then
  sleep 2
  "${ADBT[@]}" exec-out screencap -p > "$SHOT" && step "Screenshot saved: $SHOT"
  exit 0
fi
if [ "$LOGCAT" = 1 ]; then
  PID="$("${ADBT[@]}" shell pidof "$PKG" | tr -d '\r')"
  step "logcat for pid ${PID:-?} (Ctrl-C to stop)…"
  exec "${ADBT[@]}" logcat ${PID:+--pid="$PID"}
fi

step "Running on $TARGET."
[ "$HEADLESS" = 1 ] && echo "   headless — ./run.sh --no-build --screenshot   or   --logcat"
echo "   emulator log: $EMU_LOG   ·   stop it: ./run.sh --stop"
