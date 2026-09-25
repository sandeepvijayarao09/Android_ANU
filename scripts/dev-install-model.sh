#!/usr/bin/env bash
# Development only: copies a model file you already have into the app's private model folder, so no
# download is needed. The file is streamed straight into place, so it's never stored twice on the device.
#
#   scripts/dev-install-model.sh <serial> <path/to/gemma-4-E4B-it.litertlm> [package]
#
# The file name must match the catalog entry (ModelCatalog), and the file should match its pinned
# SHA-256. The in-app downloader checks the hash; this script checks it on the Mac before copying.
# The package must be a debuggable build (run-as).
set -euo pipefail

serial="$1"
model="$2"
package="${3:-io.github.sandeepvijayarao09.anu.personal}"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
name="$(basename "$model")"

# Use the path as given: the Hugging Face cache names its snapshot files after the model, but they are
# symlinks to blobs named by hash, so don't resolve them first.
catalog="$(dirname "$0")/../brain/litertlm/src/main/kotlin/io/github/sandeepvijayarao09/anu/brain/litertlm/ModelCatalog.kt"
expected="$(grep "\"$name\"" "$catalog" | grep -o '[0-9a-f]\{64\}' | head -1 || true)"
[ -n "$expected" ] || { echo "$name is not in ModelCatalog; pass the file under its catalog name" >&2; exit 1; }
actual="$(shasum -a 256 "$model" | cut -c1-64)"
[ "$actual" = "$expected" ] || { echo "SHA-256 mismatch for $name" >&2; exit 1; }

"$adb" -s "$serial" shell run-as "$package" mkdir -p no_backup/models
"$adb" -s "$serial" shell "run-as $package sh -c 'cat > no_backup/models/$name.part'" < "$model"
"$adb" -s "$serial" shell run-as "$package" mv "no_backup/models/$name.part" "no_backup/models/$name"
"$adb" -s "$serial" shell run-as "$package" ls -l no_backup/models
