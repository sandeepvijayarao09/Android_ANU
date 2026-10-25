#!/bin/zsh
# Spike S16 (ADR-121): makes "Hey Anu" and look-alike clips with macOS voices and pushes them to a device, for
# brain:wakeword's WakeEnrollmentEvalTest. Usage: scripts/wake-clips.sh <serial>
set -e
serial=${1:?Usage: scripts/wake-clips.sh <serial>}
out=$(mktemp -d)
# "Ahnoo" is how the voices say Anu as people do.
voices=("Samantha" "Daniel" "Karen" "Rishi" "Fred" "Albert" "Kathy" "Ralph" "Eddy (English (US))" "Flo (English (US))" "Reed (English (UK))" "Aman")
negatives=("Hey Andrew" "a new message arrived" "Hey, how are you doing today" "Can you pass me the menu" "Hey Siri" "I knew it, honey" "the weather is nice in the afternoon")
# Some voices now and then write a fraction of a clip, so one under 0.3 s is made again.
speak() {
  for attempt in 1 2 3; do
    say -v "$1" -r $2 -o $3 --data-format=LEI16@16000 "$4"
    [[ $(stat -f %z $3) -gt 10000 ]] && return
  done
  echo "$1 couldn't say \"$4\"" >&2
  exit 1
}
i=0
for v in $voices; do
  i=$((i+1))
  for r in 150 185 220; do
    speak "$v" $r $out/wake_${i}_${r}.wav "Hey Ahnoo"
  done
  n=0
  for text in $negatives; do
    n=$((n+1))
    speak "$v" 185 $out/neg_${i}_${n}.wav "$text"
  done
done
adb -s $serial shell mkdir -p /data/local/tmp/anu/wake
adb -s $serial push $out/. /data/local/tmp/anu/wake/ > /dev/null 2>&1
echo "Pushed $(ls $out | wc -l | tr -d ' ') clips to $serial:/data/local/tmp/anu/wake/"
rm -rf $out
