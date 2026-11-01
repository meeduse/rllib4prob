#!/usr/bin/env bash
# Empirical integration check: compare IVI V and Q over ProB-explored semantic B states.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HERE/../../.." && pwd)"
usage() {
  echo 'Usage: bash scripts/experiments/ivi_tictactoe/check_vq_explorations.sh REWARD [SEED]'
  echo '  REWARD = ONCEANDFORALL | ONTHEFLY | EMBEDDED'
  echo '  SEED = nonnegative integer (default 0)'
  echo '  Performs one PREPROCESS and one RECURSIVE learning run and checks every V/Q value.'
}
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
if [[ $# -lt 1 || $# -gt 2 ]]; then usage >&2; exit 2; fi
REWARD="$1"; SEED="${2:-0}"
case "$REWARD" in ONCEANDFORALL|ONTHEFLY|EMBEDDED) ;; *) usage >&2; exit 2;; esac
[[ "$SEED" =~ ^(0|[1-9][0-9]*)$ ]] || { echo "Invalid seed: $SEED" >&2; exit 2; }
[[ -f "$PROJECT/pom.xml" ]] || { echo "Missing pom.xml at $PROJECT" >&2; exit 1; }
for cmd in java mvn python3; do command -v "$cmd" >/dev/null || { echo "Missing: $cmd" >&2; exit 1; }; done
for f in tictac.mch tictacEmbedded.mch rewards.def; do
  [[ -f "$PROJECT/src/main/resources/TicTacToe/$f" ]] || { echo "Missing model $f" >&2; exit 1; }
done
[[ -f "$PROJECT/src/test/java/fr/polytech/mnia/Environment/TicTacToeIVIExplorationVQEquivalence.java" ]] || {
  echo 'Missing test class: install ivi_exploration_vq_patch first' >&2; exit 1;
}
python3 - "$PROJECT/pom.xml" <<'PY'
from pathlib import Path
import re,sys
pom=Path(sys.argv[1]).read_text()
assert re.search(r'<artifactId>de\.prob2\.kernel</artifactId>\s*<version>\s*4\.16\.1\s*</version>',pom), 'ProB kernel 4.16.1 required'
PY
cd "$PROJECT"
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/ivi-vq-classpath.txt
[[ -s target/ivi-vq-classpath.txt ]] || { echo 'Classpath missing' >&2; exit 1; }
mkdir -p experiments
REWARD_SLUG="$(printf '%s' "$REWARD" | tr '[:upper:]' '[:lower:]')"
OUT="$(mktemp -d "$PROJECT/experiments/ivi_tictactoe_exploration_vq_${REWARD_SLUG}_seed${SEED}_$(date +%Y%m%d-%H%M%S)_XXXXXX")"
printf 'Output: %s\n' "$OUT"
{
  echo "test=IVI_PREPROCESS_vs_RECURSIVE_VQ"
  echo "reward=$REWARD"
  echo "seed=$SEED"
  echo 'gamma=0.9'
  echo 'theta=0.01'
  echo 'maxIterations=100'
  echo 'updatesPerIteration=500'
  echo 'ordering=BFS_ACTIONS'
  echo "java=$(java -version 2>&1 | head -n 1)"
  echo "maven=$(mvn -v | head -n 1)"
  echo "git_commit=$(git rev-parse HEAD 2>/dev/null || echo unavailable)"
} > "$OUT/protocol.txt"
{
  for f in \
    pom.xml \
    src/test/java/fr/polytech/mnia/Environment/TicTacToeIVIExplorationVQEquivalence.java \
    src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java \
    src/main/java/fr/polytech/mnia/Environment/Environment.java \
    src/main/java/fr/polytech/mnia/Environment/ModelBasedEnvironment.java \
    src/main/java/fr/polytech/mnia/Environment/TicTacToeEnvironment.java \
    src/main/resources/TicTacToe/tictac.mch \
    src/main/resources/TicTacToe/tictacEmbedded.mch \
    src/main/resources/TicTacToe/rewards.def; do
    [[ -f "$f" ]] && shasum -a 256 "$f"
  done
} > "$OUT/input_sha256.txt"
CP="target/classes:target/test-classes:$(cat target/ivi-vq-classpath.txt)"
set +e
java -Drllib4prob.ivi.ordering=BFS_ACTIONS -cp "$CP" \
  fr.polytech.mnia.Environment.TicTacToeIVIExplorationVQEquivalence "$REWARD" "$SEED" \
  2>&1 | tee "$OUT/check.log"
STATUS=${PIPESTATUS[0]}
set -e
if (( STATUS != 0 )); then
  echo "ERROR: test failed with exit code $STATUS; see $OUT/check.log" >&2
  exit "$STATUS"
fi
if ! grep -q "^IVI_EXPLORATION_VQ_EQUIVALENCE PASS reward=$REWARD " "$OUT/check.log"; then
  echo "ERROR: no PASS marker in $OUT/check.log" >&2
  exit 1
fi
echo "PASS: matching V and Q for PREPROCESS vs RECURSIVE ($REWARD, seed=$SEED)"
echo "Evidence: $OUT"
