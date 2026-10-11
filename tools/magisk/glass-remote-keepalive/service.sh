#!/system/bin/sh
# Keeps the Fire TV remote paired across reboots (P37, redone as P75).
#
# After a boot Fire OS's AmazonAutoPairService starts a ~60 s "auto pair delay" and, if no key event arrives
# from the (sleeping) remote before it ends, drops the remote's bond and shows its pairing dialog. One key
# event while the delay runs cancels it ("received key event, stopping auto pair delay"). The first version
# injected a key at a fixed boot+15 s; the service starts its delay later than that on some boots (22:04:18 vs
# a key at 22:04:03), so the key landed before the delay and did nothing, and the script itself died silently.
#
# This one is event-driven: it watches logcat for the delay's start, injects a key, and keeps retrying until
# the "stopping auto pair delay" line shows (or ~3 min pass). Every step goes to logcat (tag glass-keepalive)
# AND to /data/local/tmp/glass-keepalive.log (logcat's ring buffer forgets a boot quickly; the file keeps the
# last two boots), and a marker file is written as the very first act so "did it even run" has an answer.
#
# Plain /system/bin/sh (toybox or Magisk's busybox ash): absolute paths, no `set -u`-unsafe expansions,
# no bashisms. Every external is looked up once and the script says so when one is missing.
#
# Test hooks (never set on a device): KA_LOGCAT, KA_INPUT, KA_LOG_FILE, KA_POLL, KA_MAX_SECS, KA_NO_BOOT_WAIT.

MARK=/data/local/tmp/glass-keepalive.started
LOGFILE=${KA_LOG_FILE:-/data/local/tmp/glass-keepalive.log}
TAG=glass-keepalive
POLL=${KA_POLL:-1}
MAX_SECS=${KA_MAX_SECS:-210}
RETRY_EVERY=3

PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/magisk:$PATH
export PATH

# The very first thing: proof of life, before anything that could fail.
{ date > "$MARK"; } 2>/dev/null

[ -f "$LOGFILE" ] && [ "$(wc -c < "$LOGFILE" 2>/dev/null || echo 0)" -gt 65536 ] && mv -f "$LOGFILE" "$LOGFILE.old" 2>/dev/null

say() {
  /system/bin/log -t "$TAG" "$*" 2>/dev/null || log -t "$TAG" "$*" 2>/dev/null
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOGFILE" 2>/dev/null
}

find_bin() { # find_bin <override> <name>...: the first executable that exists, else nothing
  if [ -n "$1" ]; then echo "$1"; return; fi
  shift
  for n in "$@"; do
    for d in /system/bin /system/xbin /vendor/bin /data/adb/magisk; do
      [ -x "$d/$n" ] && { echo "$d/$n"; return; }
    done
  done
}

LOGCAT=$(find_bin "$KA_LOGCAT" logcat)
INPUT=$(find_bin "$KA_INPUT" input)
CMD=$(find_bin "" cmd)

say "start pid=$$ sh=$0 logcat=${LOGCAT:-MISSING} input=${INPUT:-MISSING} cmd=${CMD:-none}"
[ -n "$LOGCAT" ] || say "no logcat binary: will send keys on a timer only"

# Boot finished? (The delay can start before sys.boot_completed; this only keeps a very early run honest, and
# gives up waiting after 90 s so it never blocks the rest.)
if [ -z "$KA_NO_BOOT_WAIT" ]; then
  i=0
  while [ "$(/system/bin/getprop sys.boot_completed 2>/dev/null)" != 1 ] && [ $i -lt 90 ]; do
    sleep 1; i=$((i + 1))
  done
  say "boot_completed after ${i}s"
fi

send_key() { # one KEYCODE_WAKEUP (224); the first way that works wins
  if [ -n "$INPUT" ] && "$INPUT" keyevent 224 >/dev/null 2>&1; then say "key sent (input keyevent 224)"; return 0; fi
  if [ -n "$CMD" ] && "$CMD" input keyevent 224 >/dev/null 2>&1; then say "key sent (cmd input keyevent 224)"; return 0; fi
  say "key FAILED (input=${INPUT:-MISSING} cmd=${CMD:-none})"
  return 1
}

# The state of the auto-pair delay from what logcat still holds: "started", "stopped" or "unknown".
# Lines look like "... auto pair delay ..." when it begins and "... stopping auto pair delay" when it ends
# (key received or its own timeout). The last such line decides.
pair_state() {
  [ -n "$LOGCAT" ] || { echo unknown; return; }
  last=$("$LOGCAT" -d -v brief 2>/dev/null | grep -i 'auto pair delay' | tail -n 1)
  case "$last" in
    "") echo unknown ;;
    *[Ss]topping*) echo stopped ;;
    *) echo started ;;
  esac
}

start=$(date +%s)
sent=0
last_sent=0
seen_start=0
say "watching for the auto pair delay (up to ${MAX_SECS}s)"
while :; do
  now=$(date +%s)
  elapsed=$((now - start))
  [ $elapsed -ge $MAX_SECS ] && { say "gave up after ${elapsed}s (keys sent: $sent, delay seen: $seen_start)"; break; }
  state=$(pair_state)
  case "$state" in
    stopped)
      say "auto pair delay stopped after ${elapsed}s (keys sent: $sent): remote stays bonded"
      # A fresh stop line can be an old boot's: only a stop after our own key (or a start) counts.
      if [ $sent -gt 0 ] || [ $seen_start -gt 0 ]; then exit 0; fi
      ;;
    started)
      if [ $seen_start -eq 0 ]; then seen_start=1; say "auto pair delay running at ${elapsed}s"; fi
      if [ $((now - last_sent)) -ge $RETRY_EVERY ]; then
        send_key && sent=$((sent + 1))
        last_sent=$now
      fi
      ;;
    *)
      # No sign of the delay yet (service not up, or logcat has no such line): one key at 60 s, in case the
      # line text differs on this build, then every 20 s to the end, so a remote never goes unkeyed.
      if [ $elapsed -ge 60 ] && [ $((now - last_sent)) -ge 20 ]; then
        say "no auto pair delay line at ${elapsed}s: sending a blind key"
        send_key && sent=$((sent + 1))
        last_sent=$now
      fi
      ;;
  esac
  sleep "$POLL"
done
exit 0
