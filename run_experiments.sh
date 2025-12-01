#!/usr/bin/env bash

set -e

# -------------------------
# Configuration
# -------------------------

ALGORITHMS=(
  VALUE_ITERATION
  POLICY_ITERATION
  MODIFIED_POLICY_ITERATION
  INCREMENTAL_VALUE_ITERATION
  BACKWARD_INDUCTION
  PRIORITIZED_VALUE_ITERATION
)

REWARD_STRATEGIES=(
  ONTHEFLY
  ONCEANDFORALL
  EMBEDDED
)

EXPLORATIONS=(
  PREPROCESS
  RECURSIVE
  # NONE est exclu pour l'instant comme demandé
)

mkdir -p logs

RESULTS_FILE="results.csv"
echo "algorithm,reward,exploration,states,t_explore_sec,t_learn_sec,iterations_or_updates" > "$RESULTS_FILE"

for algo in "${ALGORITHMS[@]}"; do
  for reward in "${REWARD_STRATEGIES[@]}"; do
    for expl in "${EXPLORATIONS[@]}"; do

      LOG_FILE="logs/${algo}_${reward}_${expl}.log"

      echo "============================================================"
      echo "Running $algo | reward=$reward | exploration=$expl"
      echo "Log: $LOG_FILE"
      echo "============================================================"

      # Lancement Maven
      mvn -q exec:java -Dexec.args="$algo $reward $expl" > "$LOG_FILE"

      # -------------------------
      # Extraction des métriques
      # -------------------------

      # 1) Nombre d'états et temps d'exploration
      # Ligne typique :
      # End of exploration 5478 | Exploration time: 32.047282167 seconds
      states=$(grep "End of exploration" "$LOG_FILE" | awk '{print $4}')
      t_explore=$(grep "End of exploration" "$LOG_FILE" | awk '{print $(NF-1)}')

      # 2) Temps d'apprentissage
      # Lignes possibles :
      # "Execution time: 14.999114583 seconds"
      # "Execution time (Incremental VI): 1.234 seconds"
      # "Execution time (Prioritized VI): 0.987 seconds"
      t_learn=$(grep "Execution time" "$LOG_FILE" | awk '{print $(NF-1)}')

      # 3) Nombre d'itérations ou d'updates
      iterations=""

      # a) Prioritized VI : "Total updates performed: N"
      if grep -q "Total updates performed" "$LOG_FILE"; then
        iterations=$(grep "Total updates performed" "$LOG_FILE" | awk '{print $4}')

      # b) VI / PI / MPI / Incremental VI : "Iteration: k | ..."
      elif grep -q "^Iteration:" "$LOG_FILE"; then
        # On prend la dernière ligne "Iteration:"
        iterations=$(grep "^Iteration:" "$LOG_FILE" | tail -n 1 | awk '{print $2}')

      # c) Backward Induction : "Horizon step: h | max delta: ..."
      elif grep -q "^Horizon step:" "$LOG_FILE"; then
        iterations=$(grep "^Horizon step:" "$LOG_FILE" | tail -n 1 | awk '{print $3}')
      fi

      # -------------------------
      # Ajout dans le CSV
      # -------------------------
      echo "${algo},${reward},${expl},${states},${t_explore},${t_learn},${iterations}" >> "$RESULTS_FILE"

    done
  done
done

echo
echo "Tous les essais sont terminés."
echo "Résultats agrégés dans: $RESULTS_FILE"
echo "Logs détaillés dans: logs/"