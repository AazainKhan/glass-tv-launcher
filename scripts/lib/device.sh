# Sourced by the device scripts. Picks the device and honours the stick lock.
#
# Device: ANDROID_SERIAL if set, else the first non-emulator device (the Fire TV stick), so
# scripts keep working with an emulator attached. Point at the emulator with
# ANDROID_SERIAL=emulator-5554 (or `scripts/emulator` prints the export line).
#
# Lock: scripts/stick-lock lets one session claim a device. Input scripts refuse to run while
# someone else holds it (TV_OWNER names you; TV_FORCE=1 overrides, e.g. the human's live view).
if [ -z "${ANDROID_SERIAL:-}" ]; then
  ANDROID_SERIAL="$( (adb devices || true) | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
  [ -n "$ANDROID_SERIAL" ] || ANDROID_SERIAL="$( (adb devices || true) | awk 'NR>1 && $2=="device" {print $1; exit}')"
  export ANDROID_SERIAL
fi
TV_LOCK_DIR="${TV_LOCK_DIR:-${HOME}/.cache/glass-tv}"

lock_owner() {  # prints the current holder of this device's lock, if any and unexpired
  local f="$TV_LOCK_DIR/$ANDROID_SERIAL.lock"
  [ -f "$f" ] || return 0
  local owner until; read -r until owner < "$f"
  [ "$(date +%s)" -lt "$until" ] && echo "$owner"
  return 0
}

require_device_access() {  # call before sending input
  [ -n "${ANDROID_SERIAL:-}" ] || { echo "$(basename "$0"): no device attached (adb devices shows none)." >&2; exit 3; }
  local owner; owner="$(lock_owner)"
  if [ -n "$owner" ] && [ "$owner" != "${TV_OWNER:-}" ] && [ "${TV_FORCE:-0}" != 1 ]; then
    echo "$ANDROID_SERIAL is locked by \"$owner\" (scripts/stick-lock status). Set TV_OWNER=\"$owner\" if that's you, or TV_FORCE=1." >&2
    exit 3
  fi
}

# Scripts that CHANGE the device (install, module or allowlist writes) need an explicit opt-in on top of that: the caller
# passes --device (which exports GLASS_DEVICE=1) and holds the stick lock under its own TV_OWNER. An emulator needs only the
# opt-in. This keeps a build run by an agent or a hook from ever writing to the stick by default.
require_device_write() {
  if [ "${GLASS_DEVICE:-0}" != 1 ]; then
    echo "$(basename "$0"): this changes the device ($ANDROID_SERIAL); pass --device to allow it." >&2; exit 3
  fi
  case "$ANDROID_SERIAL" in emulator-*) return 0 ;; esac
  local owner; owner="$(lock_owner)"
  if [ -z "$owner" ] || [ "$owner" != "${TV_OWNER:-}" ]; then
    echo "$(basename "$0"): writing to $ANDROID_SERIAL needs the stick lock held by you (scripts/stick-lock take \"<who>\"; export TV_OWNER=\"<who>\")." >&2; exit 3
  fi
}

# For long stick jobs (bench, perf-gate, clip): re-run the calling script under a self-renewing lock,
# so it can't outlive its lock or collide with another session. No-op when already held or on an emulator.
hold_stick() {  # usage: hold_stick "$0" "$@"
  [ -n "${STICK_HELD:-}" ] && return 0
  case "$ANDROID_SERIAL" in emulator-*) return 0 ;; esac
  local who="${TV_OWNER:-$(basename "$1")-$$}"
  STICK_HELD=1 exec "$(dirname "$1")/stick-lock" hold "$who" -- "$@"
}
