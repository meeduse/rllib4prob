#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$HERE/../../../src/main/java/fr/polytech/mnia/mbrl/offline/PrioritizedValueIterationV1.java" ]]; then
  SOURCE="$HERE/../../../src/main/java/fr/polytech/mnia/mbrl/offline/PrioritizedValueIterationV1.java"
else
  SOURCE="$HERE/../PrioritizedValueIterationV1.java"
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
find "$HERE/stubs" "$HERE/tests" -name '*.java' -print > "$OUT/files.txt"
printf '%s\n' "$SOURCE" >> "$OUT/files.txt"
javac -encoding UTF-8 --release 17 -d "$OUT/classes" @"$OUT/files.txt"
java -cp "$OUT/classes" fr.polytech.mnia.mbrl.offline.PVIStandaloneTests
