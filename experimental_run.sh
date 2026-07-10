#!/usr/bin/env bash

# -------------------------
# Platform tools
# -------------------------
TIMEOUT_CMD=""
STDBUF_CMD=""

if command -v timeout >/dev/null 2>&1; then
  TIMEOUT_CMD="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT_CMD="gtimeout"
else
  echo "ERROR: neither timeout nor gtimeout found." >&2
  echo "On macOS, install it with: brew install coreutils" >&2
  exit 1
fi

if command -v stdbuf >/dev/null 2>&1; then
  STDBUF_CMD="stdbuf"
elif command -v gstdbuf >/dev/null 2>&1; then
  STDBUF_CMD="gstdbuf"
else
  STDBUF_CMD=""
fi

set -euo pipefail

# -------------------------
# Configuration
# -------------------------
MAX_JOBS=6
NUM_RUNS=3
SAVE_LOGS=1
TIMEOUT_SECONDS=600
OUTPUT_ROOT="experiments"
SESSION_ID="$(date +%Y%m%d_%H%M%S)"
SESSION_DIR=""
RESULTS_FILE=""
MANIFEST_FILE=""

usage() {
  cat <<'EOF'
Usage: ./experimental_run.sh [options]

Options:
  --runs N         Number of runs per configuration (default: 3)
  --max-jobs N     Maximum number of concurrent jobs (default: 6)
  --output-root D   Base directory for organized outputs (default: experiments)
  --logs           Keep raw logs (default)
  --no-logs        Do not keep raw logs, only metrics
  --envs LIST      Comma-separated list of environments to run
  --help           Show this help message
  --timeout N      Maximum time per experiment in seconds (default: 600)
EOF
}

set_env_config() {
  local env="$1"

  case "$env" in
    autoV2)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        ONCEANDFORALL
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    autoV2embedded)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        EMBEDDED
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    Frozen_lake)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        ONCEANDFORALL
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    Frozen_lakeembedded)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        EMBEDDED
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    Interlocking)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        ONCEANDFORALL
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    Interlockingembedded)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        EMBEDDED
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    Puzzle)
       ALGORITHMS=(
         VALUE_ITERATION
         POLICY_ITERATION
         MODIFIED_POLICY_ITERATION
         INCREMENTAL_VALUE_ITERATION
         BACKWARD_INDUCTION
         PRIORITIZED_VALUE_ITERATION
       )
       REWARD_STRATEGIES=(
         ONCEANDFORALL
       )
       EXPLORATIONS=(PREPROCESS RECURSIVE)
       ;;
    Puzzleembedded)
       ALGORITHMS=(
         VALUE_ITERATION
         POLICY_ITERATION
         MODIFIED_POLICY_ITERATION
         INCREMENTAL_VALUE_ITERATION
         BACKWARD_INDUCTION
         PRIORITIZED_VALUE_ITERATION
       )
       REWARD_STRATEGIES=(
         EMBEDDED
       )
       EXPLORATIONS=(PREPROCESS RECURSIVE)
       ;;
    Taxi-driver)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        ONCEANDFORALL
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    Taxi-driverembedded)
      ALGORITHMS=(
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        PRIORITIZED_VALUE_ITERATION
        VALUE_ITERATION
        POLICY_ITERATION
        BACKWARD_INDUCTION
      )
      REWARD_STRATEGIES=(
        EMBEDDED
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    #tictactoe)
    #  ALGORITHMS=(
    #    VALUE_ITERATION
    #    POLICY_ITERATION
    #    MODIFIED_POLICY_ITERATION
    #    INCREMENTAL_VALUE_ITERATION
    #    BACKWARD_INDUCTION
    #    PRIORITIZED_VALUE_ITERATION
    #  )
    #  REWARD_STRATEGIES=(
    #    ONCEANDFORALL
    #    EMBEDDED
    #  )
    #  EXPLORATIONS=(PREPROCESS RECURSIVE)
    #  ;;
    Mountain_Car)
      ALGORITHMS=(
        VALUE_ITERATION
        POLICY_ITERATION
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        BACKWARD_INDUCTION
        PRIORITIZED_VALUE_ITERATION
      )
      REWARD_STRATEGIES=(
        ONCEANDFORALL
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    Mountain_Carembedded)
      ALGORITHMS=(
        VALUE_ITERATION
        POLICY_ITERATION
        MODIFIED_POLICY_ITERATION
        INCREMENTAL_VALUE_ITERATION
        BACKWARD_INDUCTION
        PRIORITIZED_VALUE_ITERATION
      )
      REWARD_STRATEGIES=(
        EMBEDDED
      )
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
    *)
      ALGORITHMS=(VALUE_ITERATION)
      REWARD_STRATEGIES=(ONCEANDFORALL EMBEDDED)
      EXPLORATIONS=(PREPROCESS RECURSIVE)
      ;;
  esac
}

extract_metric() {
  local pattern="$1"
  local field_spec="$2"
  local file="$3"

  if grep -q "$pattern" "$file"; then
    grep "$pattern" "$file" | awk "$field_spec" | tail -n 1
  fi
}

run_experiment() {
  local run_id="$1"
  local algo="$2"
  local reward="$3"
  local expl="$4"
  local env="$5"

  local run_dir="${SESSION_DIR}/runs/${env}/${algo}/${reward}/${expl}/run_${run_id}"
  local metrics_file="${run_dir}/metrics.csv"
  local raw_output_file="${run_dir}/stdout.log"
  local log_file="${SESSION_DIR}/logs/${env}/${algo}/${reward}/${expl}/run_${run_id}.log"
  local status="SUCCESS"
  local exit_code=0
  local states=""
  local t_explore=""
  local t_learn=""
  local iterations=""

  mkdir -p "$run_dir"
  if [[ "$SAVE_LOGS" -eq 1 ]]; then
    mkdir -p "$(dirname "$log_file")"
  fi

  echo "[START] run=${run_id} ${algo} | reward=${reward} | exploration=${expl} | env=${env}"

set +e

if [[ -n "$STDBUF_CMD" ]]; then
  "$TIMEOUT_CMD" --signal=SIGTERM --kill-after=30s "${TIMEOUT_SECONDS}s" \
    "$STDBUF_CMD" -oL -eL mvn -q exec:java -Dexec.args="$algo $reward $expl $env" \
    > "$raw_output_file" 2>&1
else
  "$TIMEOUT_CMD" --signal=SIGTERM --kill-after=30s "${TIMEOUT_SECONDS}s" \
    mvn -q exec:java -Dexec.args="$algo $reward $expl $env" \
    > "$raw_output_file" 2>&1
fi

exit_code=$?
set -e

  if [[ "$exit_code" -eq 124 ]]; then
    status="TIMEOUT"
  fi

  if [[ "$SAVE_LOGS" -eq 1 ]]; then
    cp "$raw_output_file" "$log_file"
  fi

  states="$(extract_metric "End of exploration" '{print $4}' "$raw_output_file")"
  t_explore="$(extract_metric "End of exploration" '{print $(NF-1)}' "$raw_output_file")"
  t_learn="$(extract_metric "Execution time" '{print $(NF-1)}' "$raw_output_file")"

  if grep -q "Total updates performed" "$raw_output_file"; then
    iterations="$(grep "Total updates performed" "$raw_output_file" | awk '{print $4}' | tail -n 1)"
  elif grep -q "^Iteration:" "$raw_output_file"; then
    iterations="$(grep "^Iteration:" "$raw_output_file" | tail -n 1 | awk '{print $2}')"
  elif grep -q "^Horizon step:" "$raw_output_file"; then
    iterations="$(grep "^Horizon step:" "$raw_output_file" | tail -n 1 | awk '{print $3}')"
  fi

  if [[ "$exit_code" -ne 0 && "$exit_code" -ne 124 ]]; then
    status="FAILED"
  fi

  cat > "$metrics_file" <<EOF
run,algorithm,reward,exploration,environment,states,t_explore_sec,t_learn_sec,iterations_or_updates,status,exit_code,log_file
${run_id},${algo},${reward},${expl},${env},${states},${t_explore},${t_learn},${iterations},${status},${exit_code},${log_file}
EOF

  if [[ "$SAVE_LOGS" -ne 1 ]]; then
    rm -f "$raw_output_file"
  fi

  echo "[DONE]  run=${run_id} ${algo} | env=${env} | status=${status}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs)
      NUM_RUNS="$2"
      shift 2
      ;;
    --max-jobs)
      MAX_JOBS="$2"
      shift 2
      ;;
    --output-root)
      OUTPUT_ROOT="$2"
      shift 2
      ;;
    --logs)
      SAVE_LOGS=1
      shift
      ;;
    --no-logs)
      SAVE_LOGS=0
      shift
      ;;
    --envs)
      IFS=',' read -r -a ENVIRONMENTS <<< "$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "${ENVIRONMENTS:-}" ]]; then
  ENVIRONMENTS=(
    Frozen_lake
    Frozen_lakeembedded
    autoV2
    autoV2embedded
    Interlocking
    Interlockingembedded
    Puzzle
    puzzleembedded
    Taxi-driver
    Taxi-driverembedded
    tictactoe
    Mountain_Car
    Mountain_Carembedded
  )
fi

SESSION_DIR="${OUTPUT_ROOT}/${SESSION_ID}"
RESULTS_FILE="${SESSION_DIR}/results.csv"
MANIFEST_FILE="${SESSION_DIR}/session.txt"

mkdir -p "$SESSION_DIR" "$SESSION_DIR/runs"
if [[ "$SAVE_LOGS" -eq 1 ]]; then
  mkdir -p "$SESSION_DIR/logs"
fi

echo "run,algorithm,reward,exploration,environment,states,t_explore_sec,t_learn_sec,iterations_or_updates,status,exit_code,log_file" > "$RESULTS_FILE"
{
  echo "session_id=${SESSION_ID}"
  echo "output_root=${OUTPUT_ROOT}"
  echo "max_jobs=${MAX_JOBS}"
  echo "runs_per_configuration=${NUM_RUNS}"
  echo "save_logs=${SAVE_LOGS}"
  echo "environments=${ENVIRONMENTS[*]}"
  echo "started_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "timeout_seconds=${TIMEOUT_SECONDS}"
} > "$MANIFEST_FILE"

echo "Compilation préalable du projet..."
mvn -q -DskipTests compile
echo "Compilation terminée."
echo "Session de résultats: $SESSION_DIR"
echo "============================================================"

for env in "${ENVIRONMENTS[@]}"; do
  set_env_config "$env"

  for algo in "${ALGORITHMS[@]}"; do
    for reward in "${REWARD_STRATEGIES[@]}"; do
      for expl in "${EXPLORATIONS[@]}"; do
        for run_id in $(seq 1 "$NUM_RUNS"); do
          run_experiment "$run_id" "$algo" "$reward" "$expl" "$env" &

          while (( $(jobs -r -p | wc -l) >= MAX_JOBS )); do
            wait -n
          done
        done
      done
    done
  done
done

wait

echo "Assemblage des résultats..."
{
  echo "run,algorithm,reward,exploration,environment,states,t_explore_sec,t_learn_sec,iterations_or_updates,status,exit_code,log_file"
  find "$SESSION_DIR/runs" -name metrics.csv | sort | while read -r f; do
    tail -n +2 "$f"
  done
} > "$RESULTS_FILE.tmp"
mv "$RESULTS_FILE.tmp" "$RESULTS_FILE"

echo
if [[ "$SAVE_LOGS" -eq 1 ]]; then
  echo "Tous les essais sont terminés. Logs conservés."
else
  echo "Tous les essais sont terminés. Logs désactivés."
fi
echo "Résultats agrégés dans: $RESULTS_FILE"
echo "Arborescence complète dans: $SESSION_DIR"
