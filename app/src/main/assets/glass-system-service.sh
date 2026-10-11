#!/system/bin/sh
# service.sh of the Magisk module glass-system. When a refresh of the module's APK added a privileged permission
# (flag file `uninstall-updates`), Android only grants it from the system copy, and it won't re-parse that copy while a
# same-versionCode /data update shadows it. So once, after the first boot that has the refreshed copy:
# drop the /data update (app data is kept) and bring Glass home again.
MOD=/data/adb/modules/glass-system
FLAG="$MOD/uninstall-updates"
[ -f "$FLAG" ] || exit 0
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 20
rm -f "$FLAG"
cmd package uninstall-system-updates dev.glasslauncher
sleep 3
am start -a android.intent.action.MAIN -c android.intent.category.HOME -n dev.glasslauncher/.MainActivity >/dev/null 2>&1
exit 0
