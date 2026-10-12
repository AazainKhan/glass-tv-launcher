#!/system/bin/sh
# service.sh of the Magisk module glass-system. When a refresh of the module's APK added a privileged permission
# (flag file `uninstall-updates`), Android only grants it from the system copy, and it won't re-parse that copy while a
# same-versionCode /data update shadows it. So once, after the first boot that has the refreshed copy:
# drop the /data update (app data is kept) and bring Glass home again.
MOD=/data/adb/modules/glass-system
FLAG="$MOD/uninstall-updates"
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
# Every boot: Android 11 drops BLE scan results for an app without location, and Glass's own remote pairing needs them.
# (A runtime permission is not a privileged one: the privapp allowlist doesn't grant it.)
pm grant dev.glasslauncher android.permission.ACCESS_FINE_LOCATION 2>/dev/null
[ -f "$FLAG" ] || exit 0
sleep 20
rm -f "$FLAG"
# Only when it can't roll Glass back: the active copy is the /data update, and it is the module's APK byte for byte, or
# has a lower versionCode than the module's (a stale flag with a newer build installed must not drop that build).
ACTIVE="$(pm path dev.glasslauncher | head -1 | sed 's/^package://' | tr -d '\r')"
case "$ACTIVE" in /data/*) ;; *) exit 0 ;; esac
if ! cmp -s "$ACTIVE" "$MOD/system/priv-app/GlassLauncher/GlassLauncher.apk"; then
  VC="$(dumpsys package dev.glasslauncher | awk '/^Hidden system packages:/{h=1} /versionCode=/{if(h){if(s=="")s=$0}else if(d=="")d=$0} END{print d; print s}' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p')"
  DATA_VC="$(echo "$VC" | sed -n 1p)"; SYS_VC="$(echo "$VC" | sed -n 2p)"
  if [ -z "$DATA_VC" ] || [ -z "$SYS_VC" ] || [ "$DATA_VC" -ge "$SYS_VC" ]; then
    log -t glass-system "keeping the /data update (versionCode $DATA_VC, module $SYS_VC; differs from the module copy)"
    exit 0
  fi
fi
cmd package uninstall-system-updates dev.glasslauncher
sleep 3
am start -a android.intent.action.MAIN -c android.intent.category.HOME -n dev.glasslauncher/.MainActivity >/dev/null 2>&1
exit 0
