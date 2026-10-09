#!/usr/bin/env bash
# Builds tiny code-free TV apps for the emulator, so the home screen has enough apps for the e2e
# suite (a full six-app tray plus a grid). Each is one LEANBACK_LAUNCHER activity backed by the
# framework's own android.app.Activity, so launching one opens a blank screen. Cached in $2.
#   scripts/lib/filler-apks.sh <sdk dir> <out dir>    prints the APK paths
set -euo pipefail
SDK="$1"; OUT="$2"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | grep -v rc | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | grep -E '^android-[0-9]+$' | sort -V | tail -1)/android.jar"
NAMES=(Aurora Beacon Cinder Drift Ember Fjord Grove Harbor)
mkdir -p "$OUT"
for i in "${!NAMES[@]}"; do
  name="${NAMES[$i]}"; pkg="dev.glasslauncher.e2e.filler$((i + 1))"; apk="$OUT/$pkg.apk"
  if [ ! -f "$apk" ]; then
    work="$(mktemp -d)"
    cat >"$work/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg" android:versionCode="1" android:versionName="1">
  <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="31"/>
  <uses-feature android:name="android.software.leanback" android:required="false"/>
  <application android:label="$name" android:hasCode="false">
    <activity android:name="android.app.Activity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
EOF
    "$BT/aapt2" link -o "$work/unsigned.apk" -I "$JAR" --manifest "$work/AndroidManifest.xml"
    "$BT/apksigner" sign --v4-signing-enabled false --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$apk" "$work/unsigned.apk" 2>/dev/null
    rm -rf "$work"
  fi
  echo "$apk"
done
