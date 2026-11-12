#!/usr/bin/env bash
# Development only: sends each message in a checks file to Anu's Talk page on a phone, through the debug build's
# DebugChat receiver (no typing), waits for the turn to end, reads the answer off the screen and checks it.
#
#   scripts/phone-checks.sh <serial> scripts/phone-checks.txt
#
# Each line of the checks file is: message | pattern the answer must match | pattern it must not (both extended
# regular expressions, case-insensitive, either may be empty; a pattern can't hold "|", which splits the line).
# Lines starting with # are skipped. Approval sheets are declined; keep writes that ask out of the file.
# It prints one line per check and a summary. Use made-up data only: the answers are printed.
set -uo pipefail
serial="$1"; checks="$2"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
package="${PACKAGE:-io.github.sandeepvijayarao09.anu.personal}"

screen() {
  "$adb" -s "$serial" shell uiautomator dump /sdcard/anu-checks.xml >/dev/null 2>&1
  "$adb" -s "$serial" shell cat /sdcard/anu-checks.xml 2>/dev/null | tr '>' '\n' | grep -oE "(text|content-desc)=(\"[^\"]+\"|'[^']+')" |
    sed -E "s/^(text|content-desc)=[\"']//; s/[\"']\$//" | sed "s/&apos;/'/g; s/&quot;/\"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g; s/&#10;/ /g"
}

# The answer to [1]: what follows the last copy of the message that is the user's bubble (an action card's title can
# repeat it, and is followed by its "· on device" line), up to the next "On device · …" caption. Action cards come with
# it. A message that isn't on screen gives nothing, never the turn before.
answer() {
  screen | awk -v m="$1" '
    { line[NR] = $0 }
    END {
      for (i = NR; i > 0; i--) if (line[i] == m && line[i + 1] !~ / · on device$/) break
      if (i == 0) { print "(message not on screen)"; exit }
      for (j = i + 1; j <= NR; j++) {
        if (line[j] ~ /^On device · / || line[j] == "Ask Anu") break
        if (line[j] != m && line[j] !~ /^(Retry with|Stop|Send)/) out = out (out ? " / " : "") line[j]
      }
      print out
    }'
}

# Taps the lowest node whose text is exactly [1].
tap() {
  "$adb" -s "$serial" shell uiautomator dump /sdcard/anu-checks.xml >/dev/null 2>&1
  local b; b=$("$adb" -s "$serial" shell cat /sdcard/anu-checks.xml | tr '>' '\n' | sed "s/&apos;/'/g" | grep -F "text=\"$1\"" | tail -1 | grep -oE 'bounds="[^"]+"' | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $b; [ -n "${4:-}" ] && "$adb" -s "$serial" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# An approval sheet is open: say what it asked, and decline it, so nothing leaves the phone or changes.
declined() {
  local shown; shown="$(screen)"
  echo "$shown" | grep -q "Nothing happens until you tap" || return 1
  asked="$(echo "$shown" | grep -m1 -vE '^(Anu|On device|Close sheet|Drag handle|Talk|Memory|Settings|[0-9]+ sent today|Everything stayed on this phone)$' | head -1)"
  # The decline button: "Don't", "Don't allow", "Don't save", "Don't add"…
  local label; label="$(echo "$shown" | grep -m1 -E "^Don.t( [a-z]+)?$")"
  [ -n "$label" ] && tap "$label"
  return 0
}

busy() { screen | grep -qE "^(Stop|Catching up on this chat…|Working…|Reading…|Thinking…|Getting the model ready.*)$"; }

pass=0; fail=0
# The file is read on its own descriptor: adb shell would read the rest of it from standard input.
exec 3<"$checks"
while IFS= read -r -u 3 line || [ -n "$line" ]; do
  [[ -z "${line// }" || "$line" == \#* ]] && continue
  IFS='|' read -r message expect forbid <<<"$line"
  message="$(echo "$message" | sed 's/^ *//; s/ *$//')"; expect="$(echo "${expect:-}" | sed 's/^ *//; s/ *$//')"; forbid="$(echo "${forbid:-}" | sed 's/^ *//; s/ *$//')"
  # Anu in front, on Talk, with no keyboard over the bar.
  "$adb" -s "$serial" shell am start -n "$package/io.github.sandeepvijayarao09.anu.MainActivity" >/dev/null 2>&1
  tap "Talk"; sleep 1
  started=$(date +%s); sheets=""
  quoted="'$(printf '%s' "$message" | sed "s/'/'\\\\''/g")'"
  "$adb" -s "$serial" shell am broadcast -n "$package/io.github.sandeepvijayarao09.anu.DebugChat" --es send "$quoted" >/dev/null
  sleep 3
  quiet=0
  while [ $(( $(date +%s) - started )) -lt "${TIMEOUT:-300}" ]; do
    if declined; then sheets="${sheets:+$sheets; }asked: $asked"; quiet=0; sleep 2; continue; fi
    if busy; then quiet=0; else quiet=$((quiet + 1)); [ $quiet -ge 3 ] && break; fi
    sleep 2
  done
  took=$(( $(date +%s) - started ))
  # A turn that opened another app (Clock, a settings page) left Anu: bring it back to read the answer.
  "$adb" -s "$serial" shell am start -n "$package/io.github.sandeepvijayarao09.anu.MainActivity" >/dev/null 2>&1; sleep 1
  # The chat may not have scrolled to the newest turn: swipe to the bottom first.
  for k in 1 2 3; do "$adb" -s "$serial" shell input swipe 540 1500 540 600 150; done; sleep 1
  said="$(answer "$message")"; [ -n "$sheets" ] && said="($sheets, declined) $said"
  ok=1
  [ -n "$expect" ] && ! echo "$said" | grep -qiE "$expect" && ok=0
  [ -n "$forbid" ] && echo "$said" | grep -qiE "$forbid" && ok=0
  if [ $ok = 1 ]; then pass=$((pass + 1)); verdict="PASS"; else fail=$((fail + 1)); verdict="FAIL"; fi
  printf '%s %4ss  %s\n        → %s\n' "$verdict" "$took" "$message" "${said:-(nothing)}"
done
echo "$pass passed, $fail failed"
