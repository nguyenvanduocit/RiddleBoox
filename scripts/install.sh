#!/usr/bin/env bash
#
# Installs RiddleBoox on the one device plugged in over adb.
#
#   scripts/install.sh              build the debug APK, then install it
#   scripts/install.sh --release    build the release APK instead (signed with
#                                   the upload key in ~/.riddleboox/keystore)
#   scripts/install.sh path/to.apk  install that APK instead of building
#
# A copy installed from Google Play cannot be replaced by either build: Play
# re-signs the app with its own key, so adb refuses both the debug key and the
# upload key. The script says so and stops; uninstalling is a decision to make
# by hand because it deletes the diary's data on the device.
#
# Picks the single connected device, or the one named by ANDROID_SERIAL when
# several are attached. Applies the one-time BOOX setup from README ("Thiết
# lập máy BOOX") when it is missing, keeps the app's data across the install,
# and opens the diary page afterwards. Plain bash 3.2 (macOS's own) is enough.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="com.riddleboox.app"
VARIANT="debug"
APK=""
case "${1:-}" in
  "") ;;
  --release) VARIANT="release" ;;
  *) APK="$1" ;;
esac

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required (Android platform-tools)." >&2
  exit 1
fi

# --- one device ------------------------------------------------------------

DEVICES="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
UNAUTHORIZED="$(adb devices | awk 'NR > 1 && $2 == "unauthorized" { print $1 }')"
COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  if ! grep -qx "$ANDROID_SERIAL" <<<"$DEVICES"; then
    echo "ANDROID_SERIAL=$ANDROID_SERIAL is not attached (see 'adb devices')." >&2
    exit 1
  fi
  SERIAL="$ANDROID_SERIAL"
elif (( COUNT == 1 )); then
  SERIAL="$DEVICES"
elif (( COUNT == 0 )); then
  if [[ -n "$UNAUTHORIZED" ]]; then
    echo "Device ${UNAUTHORIZED%%$'\n'*} is attached but not authorized: accept the USB debugging prompt on its screen." >&2
  else
    echo "No device attached. Plug it in with USB debugging on, then check 'adb devices'." >&2
  fi
  exit 1
else
  echo "Several devices attached; pick one with ANDROID_SERIAL=<serial>:" >&2
  while IFS= read -r serial; do echo "  $serial" >&2; done <<<"$DEVICES"
  exit 1
fi

adb_dev() { adb -s "$SERIAL" "$@"; }

MODEL="$(adb_dev shell getprop ro.product.model | tr -d '\r')"
SDK="$(adb_dev shell getprop ro.build.version.sdk | tr -d '\r')"
echo "Device: $MODEL ($SERIAL, API $SDK)"

# --- the APK ---------------------------------------------------------------

if [[ -z "$APK" ]]; then
  TASK=":app:assembleDebug"
  [[ "$VARIANT" == "release" ]] && TASK=":app:assembleRelease"
  echo "Building $VARIANT APK…"
  (cd "$ROOT_DIR" && ./gradlew "$TASK" --console=plain -q)
  APK="$ROOT_DIR/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
  if [[ "$VARIANT" == "release" && ! -s "$APK" ]]; then
    echo "No signed release APK: the build found no keystore (see keystorePropertiesFile in app/build.gradle.kts)." >&2
    exit 1
  fi
fi

if [[ ! -s "$APK" ]]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

# --- BOOX setup: the pen writes no ink without it (README) ------------------

POLICY="$(adb_dev shell settings get global hidden_api_policy | tr -d '\r')"
if [[ "$POLICY" != "1" ]]; then
  adb_dev shell settings put global hidden_api_policy 1
  echo "Set hidden_api_policy=1 (was '$POLICY') — required once per BOOX for pen input."
fi

# --- install, keeping the app's data ---------------------------------------

echo "Installing $(basename "$APK")…"
if ! OUTPUT="$(adb_dev install -r -d "$APK" 2>&1)"; then
  echo "$OUTPUT" >&2
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$OUTPUT"; then
    cat >&2 <<EOF

The copy on the device was signed with a different key. A copy installed from
Google Play carries Play's own signing key, which neither the debug build nor
the upload-key release build can replace. To go ahead, uninstall it first —
that deletes the diary's data on the device (conversations, memories, agents,
settings). Settings → "back up all data" exports a readable text copy first;
the app has no way to import it back.

  adb -s $SERIAL uninstall $PACKAGE
EOF
  fi
  exit 1
fi

INSTALLED="$(adb_dev shell dumpsys package "$PACKAGE" | grep -m1 'versionName=' | sed 's/^ *//' | tr -d '\r')"
echo "Installed: $INSTALLED"

# BOOX "auto-freezes" third-party apps once they leave the foreground, which
# disables the package (enabled=3) and makes am start report that the activity
# does not exist. Enabling is a no-op when the app is not frozen.
adb_dev shell pm enable "$PACKAGE" >/dev/null
adb_dev shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
echo "Opened $PACKAGE on $MODEL."
