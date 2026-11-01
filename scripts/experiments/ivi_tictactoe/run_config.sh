#!/usr/bin/env bash
# Run one IVI/TicTacToe condition over an explicit sequence of seeds.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
usage() {
  echo 'Usage: bash scripts/experiments/ivi_tictactoe/run_config.sh REWARD EXPLORATION [FIRST_SEED] [COUNT]'
  echo '  REWARD       = ONCEANDFORALL | ONTHEFLY | EMBEDDED'
  echo '  EXPLORATION  = PREPROCESS | RECURSIVE'
  echo '  FIRST_SEED   = nonnegative integer (default 0)'
  echo '  COUNT        = positive integer (default 10)'
  echo 'Example: bash scripts/experiments/ivi_tictactoe/run_config.sh EMBEDDED PREPROCESS 0 10'
}
if [[ "${1:-}" == '--help' || "${1:-}" == '-h' ]]; then usage; exit 0; fi
if [[ $# -lt 2 || $# -gt 4 ]]; then usage >&2; exit 2; fi
REWARD="$1"; EXPLORATION="$2"; FIRST_SEED="${3:-0}"; COUNT="${4:-10}"
case "$REWARD" in ONCEANDFORALL|ONTHEFLY|EMBEDDED) ;; *) usage >&2; exit 2;; esac
case "$EXPLORATION" in PREPROCESS|RECURSIVE) ;; *) usage >&2; exit 2;; esac
if ! [[ "$FIRST_SEED" =~ ^(0|[1-9][0-9]*)$ && "$COUNT" =~ ^[1-9][0-9]*$ ]]; then
  echo 'ERREUR: FIRST_SEED doit etre >= 0 et COUNT >= 1 (entiers sans signe).' >&2; exit 2
fi
if (( COUNT > 1000 || FIRST_SEED > 1000000000 )); then
  echo 'ERREUR: nombre de repetitions ou graine hors limite de securite.' >&2; exit 2
fi
if [[ ! -f "$PROJECT/pom.xml" ]]; then echo "ERREUR: pom.xml absent de $PROJECT" >&2; exit 1; fi
for tool in python3 mvn java; do
  command -v "$tool" >/dev/null || { echo "ERREUR: $tool absent" >&2; exit 1; }
done
ENV_NAME='tictactoe'; B_FILE='tictac.mch'
if [[ "$REWARD" == 'EMBEDDED' ]]; then ENV_NAME='tictacEmbedded'; B_FILE='tictacEmbedded.mch'; fi
for input in \
 "$PROJECT/src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java" \
 "$PROJECT/src/main/java/fr/polytech/mnia/Environment/EnvironmentFactory.java" \
 "$PROJECT/src/main/resources/TicTacToe/$B_FILE" \
 "$PROJECT/src/main/resources/TicTacToe/rewards.def"; do
  [[ -f "$input" ]] || { echo "ERREUR: fichier absent $input" >&2; exit 1; }
done
# Ensure that the expected factory routing exists (avoid silent default fallback).
python3 - "$PROJECT" "$ENV_NAME" "$B_FILE" <<'PY'
from pathlib import Path
import sys,re
root, env, bfile = sys.argv[1:]
s = (Path(root)/'src/main/java/fr/polytech/mnia/Environment/EnvironmentFactory.java').read_text()
if env == 'tictacEmbedded':
    ok = bool(re.search(r'case\s+[^\n]*"tictacembedded"[^\n]*->[^\n]*tictacEmbedded\.mch', s, re.I))
else:
    ok = bool(re.search(r'(case\s+[^\n]*"tictactoe"[^\n]*->|default\s*->)[^\n]*tictac\.mch',s,re.I))
if not ok:
    sys.exit('ERREUR: EnvironmentFactory ne montre pas un routage explicite attendu vers '+bfile)
pom=(Path(root)/'pom.xml').read_text()
m=re.search(r'<artifactId>de\.prob2\.kernel</artifactId>\s*<version>\s*([^<]+)</version>',pom)
if not m or m.group(1).strip()!='4.16.1':
    sys.exit('ERREUR: de.prob2.kernel:4.16.1 non declare comme attendu')
print('Routage valide:',env,'->',bfile,'; ProB kernel: 4.16.1')
PY
cd "$PROJECT"
if ! grep -q 'BFS_ACTIONS' src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java; then
  echo 'ERREUR: IVI ne prend pas en charge BFS_ACTIONS.' >&2; exit 1
fi
# Compile ONCE; run Java directly to forward the actual process seed.
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/ivi-benchmark-classpath.txt
[[ -s target/ivi-benchmark-classpath.txt ]] || { echo 'ERREUR: classpath vide' >&2; exit 1; }
CP="target/classes:target/test-classes:$(cat target/ivi-benchmark-classpath.txt)"
mkdir -p experiments
STAMP="$(date +%Y%m%d-%H%M%S)"
REWARD_SLUG="$(printf '%s' "$REWARD" | tr '[:upper:]' '[:lower:]')"
EXPL_SLUG="$(printf '%s' "$EXPLORATION" | tr '[:upper:]' '[:lower:]')"
OUT="$(mktemp -d "$PROJECT/experiments/ivi_tictactoe_${REWARD_SLUG}_${EXPL_SLUG}_${STAMP}_XXXXXX")"
mkdir -p "$OUT/logs" "$OUT/inputs"
# Freeze relevant source files for auditability; do not modify sources.
for input in \
 pom.xml \
 src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java \
 src/main/java/fr/polytech/mnia/Agent.java \
 src/main/java/fr/polytech/mnia/AgentFactory.java \
 src/main/java/fr/polytech/mnia/AgentParameterCatalog.java \
 src/main/java/fr/polytech/mnia/App.java \
 src/main/java/fr/polytech/mnia/Environment/Environment.java \
 src/main/java/fr/polytech/mnia/Environment/EnvironmentFactory.java \
 src/main/java/fr/polytech/mnia/Environment/ModelBasedEnvironment.java \
 src/main/java/fr/polytech/mnia/Environment/TicTacToeEnvironment.java \
 src/main/resources/TicTacToe/tictac.mch \
 src/main/resources/TicTacToe/tictacEmbedded.mch \
 src/main/resources/TicTacToe/rewards.def \
 config/agent-parameters.properties \
 src/main/resources/agent-parameters.properties; do
  if [[ -f "$input" ]]; then
    mkdir -p "$OUT/inputs/$(dirname "$input")"
    cp "$input" "$OUT/inputs/$input"
  fi
done
python3 - "$OUT/inputs" > "$OUT/input_sha256.txt" <<'PY_HASH'
import hashlib, sys
from pathlib import Path
root=Path(sys.argv[1]);
for p in sorted(root.rglob('*')):
    if p.is_file(): print(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  inputs/{p.relative_to(root).as_posix()}')
PY_HASH
{
  echo "started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo 'algorithm=INCREMENTAL_VALUE_ITERATION'
  echo "reward_strategy=$REWARD"
  echo "exploration=$EXPLORATION"
  echo "environment=$ENV_NAME"
  echo "b_machine=$B_FILE"
  echo 'ordering=BFS_ACTIONS'
  echo "first_seed=$FIRST_SEED"
  echo "run_count=$COUNT"
  echo 'kernel=de.prob2.kernel:4.16.1'
  echo 'expected_gamma=0.9'
  echo 'expected_theta=0.01'
  echo 'expected_max_iterations=100'
  echo 'expected_updates_per_iteration=500'
  echo 'expected_states=5478'
  echo 'expected_process=separate_java_jvm_per_seed'
  if command -v git >/dev/null && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "git_commit=$(git rev-parse HEAD 2>/dev/null || echo UNKNOWN)"
    if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
      echo 'git_tracked_worktree_dirty=true'
    else
      echo 'git_tracked_worktree_dirty=false'
    fi
  else echo 'git_commit=NO_GIT_REPOSITORY'; fi
  echo "operating_system=$(uname -s)"
  echo "architecture=$(uname -m)"
  java -version 2>&1
  mvn -version 2>&1
} > "$OUT/protocol.txt"
echo "Output directory: $OUT"
for (( i=0; i<COUNT; i++ )); do
  seed=$(( FIRST_SEED + i ))
  LOG="$OUT/logs/seed_${seed}.log"
  printf '\n============ %s %s seed=%d (%d/%d) ============\n' "$REWARD" "$EXPLORATION" "$seed" "$((i+1))" "$COUNT"
  set +e
  java \
    -Drllib4prob.ivi.seed="$seed" \
    -Drllib4prob.ivi.ordering=BFS_ACTIONS \
    -cp "$CP" \
    fr.polytech.mnia.App \
    INCREMENTAL_VALUE_ITERATION "$REWARD" "$EXPLORATION" "$ENV_NAME" \
    2>&1 | tee "$LOG"
  RUN_CODE=${PIPESTATUS[0]}
  set -e
  printf 'RUN_EXIT_CODE=%s\n' "$RUN_CODE" >> "$LOG"
  python3 "$SCRIPT_DIR/summarize_config.py" "$OUT" --quiet
  if grep -q '^IVI_RESULT status=CONVERGED ' "$LOG"; then
    echo "seed=$seed: IVI claimed CONVERGED; check CSV for residual and consistency"
  else
    echo "seed=$seed: NOT confirmed converged (exit code $RUN_CODE)"
  fi
done
python3 "$SCRIPT_DIR/summarize_config.py" "$OUT"
echo "All raw logs and results are in: $OUT"
