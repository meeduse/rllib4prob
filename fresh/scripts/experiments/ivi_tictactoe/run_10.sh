#!/usr/bin/env bash
# RLLib4ProB: 10 reproducible IVI runs, seed 0..9, no source changes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# This script is versioned under <repository>/scripts/experiments/ivi_tictactoe/.
# Always resolve the repository root from its own location, not the shell cwd.
PROJECT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
if [[ "${1:-}" == "--help" ]]; then
  echo "Usage: bash scripts/experiments/ivi_tictactoe/run_10.sh"
  echo "Launch from any directory; the project is determined from the script location."
  exit 0
fi
if [[ $# -ne 0 ]]; then
  echo "Usage: bash scripts/experiments/ivi_tictactoe/run_10.sh" >&2
  exit 2
fi
if [[ ! -f "$PROJECT/pom.xml" ]]; then
  echo "ERREUR : pom.xml introuvable dans $PROJECT" >&2
  exit 1
fi
for file in \
  "$PROJECT/src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java" \
  "$PROJECT/src/main/resources/TicTacToe/tictac.mch" \
  "$PROJECT/src/main/resources/TicTacToe/rewards.def"; do
  if [[ ! -f "$file" ]]; then
    echo "ERREUR : fichier absent : $file" >&2
    exit 1
  fi
done
if ! grep -q 'BFS_ACTIONS' "$PROJECT/src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java"; then
  echo 'ERREUR : la version IVI avec BFS_ACTIONS semble absente.' >&2
  exit 1
fi
if ! command -v python3 >/dev/null || ! command -v mvn >/dev/null || ! command -v java >/dev/null; then
  echo 'ERREUR : python3, mvn et java sont necessaires.' >&2
  exit 1
fi

cd "$PROJECT"
python3 - <<'PY'
from pathlib import Path
import re,sys
pom = Path('pom.xml').read_text()
match = re.search(r'<artifactId>de\.prob2\.kernel</artifactId>\s*<version>\s*([^<]+)\s*</version>', pom)
if not match:
    sys.exit('ERREUR : version explicite de de.prob2.kernel introuvable dans pom.xml')
version = match.group(1).strip()
print('ProB kernel declare :',version)
if version != '4.16.1':
    sys.exit('ERREUR : le protocole exige de.prob2.kernel 4.16.1')
PY

# Compile exactly once, then launch java DIRECTLY. Maven exec:java can override
# the requested main class and does not give a reliable JVM seed forwarding.
echo 'Compilation Maven et preparation du classpath...'
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/ivi-benchmark-classpath.txt
if [[ ! -s target/ivi-benchmark-classpath.txt ]]; then
  echo 'ERREUR : classpath Maven vide.' >&2
  exit 1
fi
CP="target/classes:target/test-classes:$(cat target/ivi-benchmark-classpath.txt)"

STAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_ROOT="$PROJECT/experiments"
mkdir -p "$OUTPUT_ROOT"
OUT="$(mktemp -d "$OUTPUT_ROOT/ivi_tictactoe_once_${STAMP}_XXXXXX")"
mkdir -p "$OUT/logs" "$OUT/inputs"
cp "$PROJECT/pom.xml" "$OUT/inputs/pom.xml"
for input in \
  src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java \
  src/main/java/fr/polytech/mnia/AgentFactory.java \
  src/main/java/fr/polytech/mnia/AgentParameterCatalog.java \
  src/main/java/fr/polytech/mnia/Environment/ModelBasedEnvironment.java \
  src/main/java/fr/polytech/mnia/Environment/TicTacToeEnvironment.java \
  src/main/java/fr/polytech/mnia/Environment/EnvironmentFactory.java \
  src/main/java/fr/polytech/mnia/Environment/Environment.java \
  src/main/java/fr/polytech/mnia/App.java \
  src/main/resources/TicTacToe/tictac.mch \
  src/main/resources/TicTacToe/rewards.def \
  config/agent-parameters.properties \
  src/main/resources/agent-parameters.properties; do
  if [[ -f "$input" ]]; then
    mkdir -p "$OUT/inputs/$(dirname "$input")"
    cp "$input" "$OUT/inputs/$input"
  fi
done
python3 - "$OUT/inputs" > "$OUT/input_sha256.txt" <<'PY_HASH'
import hashlib
from pathlib import Path
import sys
root = Path(sys.argv[1])
for p in sorted(root.rglob('*')):
    if p.is_file():
        print(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  inputs/{p.relative_to(root).as_posix()}")
PY_HASH
{
  echo "started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo 'algorithm=INCREMENTAL_VALUE_ITERATION'
  echo 'reward_strategy=ONCEANDFORALL'
  echo 'exploration=PREPROCESS'
  echo 'environment=tictactoe'
  echo 'ordering=BFS_ACTIONS'
  echo 'seeds=0..9'
  echo 'kernel=de.prob2.kernel:4.16.1'
  echo 'runtime_parameters=read_from_IVI_RESULT'
  if command -v git >/dev/null && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "git_commit=$(git rev-parse HEAD 2>/dev/null || echo NOT_COMMITTED)"
    if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
      echo 'git_tracked_worktree_dirty=true'
    else
      echo 'git_tracked_worktree_dirty=false'
    fi
  else
    echo 'git_commit=NO_GIT_REPOSITORY'
  fi
  echo "operating_system=$(uname -s)"
  echo "machine_architecture=$(uname -m)"
  java -version 2>&1
  mvn -version 2>&1
} > "$OUT/protocol.txt"

printf '\nRESULTATS dans : %s\n' "$OUT"
echo 'Chaque execution produit son propre log. Aucun fichier de production modifie.'

for seed in {0..9}; do
  LOG="$OUT/logs/seed_${seed}.log"
  printf '\n========== IVI seed=%d / 9 ==========\n' "$seed"
  set +e
  java \
    -Drllib4prob.ivi.seed="$seed" \
    -Drllib4prob.ivi.ordering=BFS_ACTIONS \
    -cp "$CP" \
    fr.polytech.mnia.App \
    INCREMENTAL_VALUE_ITERATION ONCEANDFORALL PREPROCESS tictactoe \
    2>&1 | tee "$LOG"
  RUN_CODE=${PIPESTATUS[0]}
  set -e
  printf 'RUN_EXIT_CODE=%s\n' "$RUN_CODE" >> "$LOG"
  python3 "$SCRIPT_DIR/summarize.py" "$OUT" --quiet
  if grep -q '^IVI_RESULT status=CONVERGED ' "$LOG"; then
    echo "Seed $seed : IVI a annonce CONVERGED (code $RUN_CODE), controle dans results.csv"
  else
    echo "ATTENTION : seed $seed n'a PAS de convergence confirmee (code $RUN_CODE)."
  fi
done

python3 "$SCRIPT_DIR/summarize.py" "$OUT"
printf '\nResultats conserves dans : %s\n' "$OUT"
echo 'Copier ici les lignes du tableau final ; ne pas annoncer de convergence pour les runs au plafond.'
