#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root, or invoke with an absolute path.
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

REPETITIONS="${1:-2}"
WORKERS="${2:-1,2,4}"
PREFIX_DEPTH="${3:-1}"
REASSIGN="${4:-false}"
STAMP="$(date '+%Y%m%d-%H%M%S')"
OUT="results/concurrent/tictactoe-${STAMP}.csv"
mkdir -p results/concurrent

mvn test-compile
mvn exec:exec \
  -Dexec.executable=java \
  "-Dexec.args=-cp %classpath fr.polytech.mnia.exploration.TicTacToePerformanceBenchmark ${REPETITIONS} ${WORKERS} ${PREFIX_DEPTH} ${REASSIGN} ${OUT}" \
  -Dexec.classpathScope=test

# Save metadata only after successful full execution.
{
  echo "gitCommit=$(git rev-parse HEAD)"
  echo "gitBranch=$(git branch --show-current)"
  echo "javaVersion=$(java -version 2>&1 | head -n 1)"
  echo "mavenVersion=$(mvn -v | sed -n '1p')"
  echo "repetitions=${REPETITIONS}"
  echo "workers=${WORKERS}"
  echo "cutDepth=${PREFIX_DEPTH}"
  echo "reassignment=${REASSIGN}"
  echo "benchmarkCsv=${OUT}"
} > "${OUT%.csv}.metadata.txt"

echo "PASS: ${OUT}"
