#!/usr/bin/env bash
set -euo pipefail
THIS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$THIS/../../../src/main/java/fr/polytech/mnia/mbrl/offline/ValueIteration.java" ]; then
  SOURCE="$THIS/../../../src/main/java/fr/polytech/mnia/mbrl/offline/ValueIteration.java"
else
  SOURCE="$THIS/../ValueIteration.java"
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
find "$THIS/stubs" "$THIS/tests" -name '*.java' -print > "$OUT/files.txt"
printf '%s\n' "$SOURCE" >> "$OUT/files.txt"
javac -encoding UTF-8 --release 17 -d "$OUT/classes" @"$OUT/files.txt"
java -cp "$OUT/classes" fr.polytech.mnia.mbrl.offline.VIStandaloneTests
