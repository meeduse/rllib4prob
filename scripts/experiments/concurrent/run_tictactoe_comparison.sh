#!/usr/bin/env bash
set -euo pipefail

# Independent PREPROCESS / RECURSIVE / CONCURRENT graph comparison.
# Positional arguments: repetitions, worker list, prefix depth, stealing, warmups.
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

REPETITIONS="${1:-1}"
WORKERS="${2:-1,2}"
DEPTH="${3:-1}"
REASSIGN="${4:-false}"
WARMUPS="${5:-0}"
STAMP="$(date '+%Y%m%d-%H%M%S')"
OUT="results/concurrent/comparison-tictactoe-${STAMP}-$$.csv"
mkdir -p results/concurrent

mvn test-compile
mvn exec:exec \
  -Dexec.executable=java \
  "-Dexec.args=-cp %classpath fr.polytech.mnia.exploration.TicTacToeStrategyComparisonBenchmark ${REPETITIONS} ${WORKERS} ${DEPTH} ${REASSIGN} ${OUT} ${WARMUPS}" \
  -Dexec.classpathScope=test

# The benchmark itself creates the success marker only after ALL comparisons.
test -f "${OUT}.complete"
{
  printf 'gitCommit=%s\n' "$(git rev-parse HEAD)"
  printf 'gitBranch=%s\n' "$(git branch --show-current)"
  printf 'javaVersion=%s\n' "$(java -version 2>&1 | head -n 1)"
  printf 'mavenVersion=%s\n' "$(mvn -v | head -n 1)"
  printf 'repetitions=%s\n' "$REPETITIONS"
  printf 'workers=%s\n' "$WORKERS"
  printf 'cutDepth=%s\n' "$DEPTH"
  printf 'reassignment=%s\n' "$REASSIGN"
  printf 'warmupsPerConfiguration=%s\n' "$WARMUPS"
  printf 'benchmarkCsv=%s\n' "$OUT"
} > "${OUT%.csv}.metadata.txt"
echo "PASS: $OUT"
