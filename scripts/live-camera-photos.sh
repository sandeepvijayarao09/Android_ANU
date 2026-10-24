#!/usr/bin/env bash
# Development only (ADR-111): fetches eight COCO 2017 validation photos for the live camera's evals and pushes them
# to a device: six with drinks (coffee, beer, wine, bottles) and two without, for LiveSearchEvalTest, and the wine
# dinner (405691) for S14DetectionTest. They're Flickr photos under their own licences, so they're never committed:
# only their ids are.
#
#   scripts/live-camera-photos.sh <serial>
set -euo pipefail

serial="$1"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
ids=(000000378605 000000323151 000000346638 000000340697 000000405691 000000102707 000000106235 000000029596)
dir="$(mktemp -d)"
for id in "${ids[@]}"; do curl -sf -o "$dir/$id.jpg" "http://images.cocodataset.org/val2017/$id.jpg"; done
"$adb" -s "$serial" shell mkdir -p /data/local/tmp/anu/photos/live
"$adb" -s "$serial" push "$dir"/*.jpg /data/local/tmp/anu/photos/live/ >/dev/null
"$adb" -s "$serial" shell ls /data/local/tmp/anu/photos/live
rm -rf "$dir"
