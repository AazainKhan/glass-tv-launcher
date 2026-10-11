#!/system/bin/sh
# Keeps the Fire TV remote paired across reboots and sleeps (P37, redone as P75 after reading
# com.amazon.service.AmazonAutoPairService in fosservices.jar).
#
# How Fire unpairs the remote: AmazonAutoPairService (in system_server) arms a 60 s timer plus a next-key listener
#  - once after boot ("Registering connectivity callback..", reason "host device reboot"), and
#  - on every remote disconnect ("onConnectStateChanged received..state:0", reason "remote disconnect").
# Any key event while it is armed logs "received key event, stopping auto pair delay" and cancels the timer. If it
# fires, with the screen on, nothing playing and no remote connected, Fire re-pairs the advertising remote: an
# unpair then a pair, and the pair fails (reason_smp_fail): the bond is gone. The old script sent one key at a
# fixed boot + 15 s, before the listener existed (22:04:03 against 22:04:18), so it did nothing.
#
# This watcher is persistent and event-driven: it follows AmazonAutoPairService's own log lines and sends ONE
# KEYCODE_WAKEUP right after each arming, checks that "stopping auto pair delay" followed, retries once, and
# logs FAILED otherwise. A disconnect while the screen is off is left alone (the timer bails by itself when the
# screen is off, and a WAKEUP would turn the TV on). It never exits. Plain /system/bin/sh, absolute paths.
#
# Evidence: /data/adb/glass-keepalive/started (first act of every run) and /data/adb/glass-keepalive/keepalive.log,
# plus logcat tag glass-keepalive.
#
# Test hooks (never set on a device): KA_LOGCAT, KA_INPUT, KA_DUMPSYS, KA_DIR, KA_ONCE (exit when the stream ends).

DIR=${KA_DIR:-/data/adb/glass-keepalive}
TAG=glass-keepalive
PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/magisk:$PATH
export PATH

mkdir -p "$DIR" 2>/dev/null
{ date > "$DIR/started"; } 2>/dev/null
LOGFILE=$DIR/keepalive.log
if [ -f "$LOGFILE" ] && [ "$(wc -c < "$LOGFILE" 2>/dev/null || echo 0)" -gt 65536 ]; then mv -f "$LOGFILE" "$LOGFILE.old" 2>/dev/null; fi

say() {
  /system/bin/log -t "$TAG" "$*" 2>/dev/null || log -t "$TAG" "$*" 2>/dev/null
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOGFILE" 2>/dev/null
}

find_bin() { # find_bin <override> <name>: the override, else the first executable of that name
  if [ -n "$1" ]; then echo "$1"; return; fi
  for d in /system/bin /system/xbin /vendor/bin /data/adb/magisk; do
    [ -x "$d/$2" ] && { echo "$d/$2"; return; }
  done
}

LOGCAT=$(find_bin "$KA_LOGCAT" logcat)
INPUT=$(find_bin "$KA_INPUT" input)
CMD=$(find_bin "" cmd)
DUMPSYS=$(find_bin "$KA_DUMPSYS" dumpsys)

say "start pid=$$ logcat=${LOGCAT:-MISSING} input=${INPUT:-MISSING} cmd=${CMD:-none} dumpsys=${DUMPSYS:-MISSING}"
if [ -z "$LOGCAT" ]; then say "no logcat binary: cannot watch, exiting"; exit 1; fi

awake() { [ -n "$DUMPSYS" ] && "$DUMPSYS" power 2>/dev/null | grep -q 'mWakefulness=Awake'; }

send_key() { # one KEYCODE_WAKEUP (224); the first way that works wins
  if [ -n "$INPUT" ] && "$INPUT" keyevent 224 >/dev/null 2>&1; then say "key sent (input keyevent 224)"; return 0; fi
  if [ -n "$CMD" ] && "$CMD" input keyevent 224 >/dev/null 2>&1; then say "key sent (cmd input keyevent 224)"; return 0; fi
  say "key FAILED to send (input=${INPUT:-MISSING} cmd=${CMD:-none})"
  return 1
}

stops() { # how many "stopping auto pair delay" lines logcat holds
  "$LOGCAT" -d -s AmazonAutoPairService:I 2>/dev/null | grep -c 'stopping auto pair delay'
}

# Sends a key for [$1] and confirms the timer stopped within 5 s; one retry; says FAILED otherwise.
cancel_delay() {
  before=$(stops)
  attempt=1
  while [ $attempt -le 2 ]; do
    send_key || { attempt=$((attempt + 1)); sleep 1; continue; }
    i=0
    while [ $i -lt 5 ]; do
      sleep 1
      i=$((i + 1))
      if [ "$(stops)" -gt "$before" ]; then say "$1: auto pair delay stopped ${i}s after key (attempt $attempt): remote stays bonded"; return 0; fi
    done
    say "$1: no 'stopping auto pair delay' within 5 s of key (attempt $attempt)"
    attempt=$((attempt + 1))
  done
  say "$1: FAILED to stop the auto pair delay"
  return 1
}

last_at=0
handle() { # handle <reason>: debounce, then act
  now=$(date +%s)
  if [ $((now - last_at)) -lt 3 ]; then return; fi
  last_at=$now
  case "$1" in
    boot)
      sleep 1
      cancel_delay "boot arming" ;;
    disconnect)
      if awake; then cancel_delay "remote disconnect"; else say "remote disconnect with the screen off: left alone"; fi ;;
  esac
}

# What was logged before this watcher started: an arming after the last stop is still live.
scan_existing() {
  last=$("$LOGCAT" -d -s AmazonAutoPairService:I 2>/dev/null | grep -E 'Registering connectivity callback|stopping auto pair delay' | tail -n 1)
  case "$last" in
    *Registering\ connectivity\ callback*) say "arming found in the buffer at start"; handle boot ;;
    "") say "no AmazonAutoPairService lines yet" ;;
    *) say "buffer's last event: delay already stopped" ;;
  esac
}

scan_existing
while :; do
  say "following AmazonAutoPairService"
  "$LOGCAT" -T 1 -s AmazonAutoPairService:I 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      *Registering\ connectivity\ callback*) say "armed: $line"; handle boot ;;
      *onConnectStateChanged\ received*state:0*) say "armed: $line"; handle disconnect ;;
    esac
  done
  [ -n "$KA_ONCE" ] && exit 0
  say "logcat stream ended; restarting in 2 s"
  sleep 2
done
