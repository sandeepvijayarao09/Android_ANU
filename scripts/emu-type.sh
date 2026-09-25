#!/usr/bin/env bash
# Development only: types a message into the focused text field on an emulator, one word at a time,
# waiting for each word to appear. Fast `adb shell input text` loses characters with Gboard.
#
#   scripts/emu-type.sh <serial> "Schedule a design review with Maya tomorrow"
set -uo pipefail
serial="$1"; message="$2"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

field() {
  "$adb" -s "$serial" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$adb" -s "$serial" shell cat /sdcard/ui.xml | grep -o '<node[^>]*EditText[^>]*>' | grep -o 'text="[^"]*"' | head -1 | sed 's/^text="//; s/"$//'
}

# Clear the field first.
"$adb" -s "$serial" shell input keyevent KEYCODE_MOVE_END
"$adb" -s "$serial" shell input keyevent $(printf '67 %.0s' $(seq 1 80)) >/dev/null

typed=""
for word in $message; do
  want="${typed:+$typed }$word"
  for attempt in 1 2 3; do
    "$adb" -s "$serial" shell input text "$( [ -n "$typed" ] && printf '%%s')$word"
    for i in 1 2 3 4 5 6; do sleep 0.5; [ "$(field)" = "$want" ] && break 2; done
    now="$(field)"; extra=$(( ${#now} - ${#typed} ))
    [ "$extra" -gt 0 ] && "$adb" -s "$serial" shell input keyevent $(printf '67 %.0s' $(seq 1 "$extra")) >/dev/null
  done
  typed="$want"
done
[ "$(field)" = "$message" ] || { echo "typing failed: field has \"$(field)\"" >&2; exit 1; }
