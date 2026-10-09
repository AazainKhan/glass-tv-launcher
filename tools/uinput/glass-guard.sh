# Runs on the device before a scripted Select (scripts/key): exit 3 when that Select could control the user's
# real devices (Amazon Smart Home or Alexa in front, or Control Center showing its Alexa page), else 0.
f=$(dumpsys window displays 2>/dev/null | grep -m1 mCurrentFocus)
case "$f" in *smarthomemapviewapp*|*com.amazon.vizzini*) echo "key: refused Select: $f controls real devices" >&2; exit 3;; esac
t=$(am broadcast -n dev.glasslauncher/.system.DebugDumpReceiver --ez home true 2>/dev/null)
case "$t" in *'Ask Alexa'*) echo "key: refused Select on Control Center's Alexa page (it controls real devices)" >&2; exit 3;; esac
exit 0
