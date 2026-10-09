#!/system/bin/sh
# Frees the remote's Settings button for Glass TV Launcher. Fire OS catches it by its kernel key code
# (249) before apps see it, so the kernel keymap is changed to emit 185 instead, a code the key
# layout doesn't name: it reaches Glass as KEYCODE_UNKNOWN with scan code 185.
# The remote gets a fresh keymap whenever it reconnects (after every sleep), so this re-applies on
# each new input device rather than polling.
MODDIR=${0%/*}
BB=/data/adb/magisk/busybox  # its inotifyd takes the :n (created) mask; toybox's rejects it
apply() { "$MODDIR/glass-keymap" remap "Fire TV Remote" 249 185 >/dev/null 2>&1; }
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
apply
while true; do
  "$BB" inotifyd - /dev/input:n | while read -r _; do sleep 1; apply; done
  sleep 5  # inotifyd exited; start watching again
done
