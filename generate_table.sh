#!/usr/bin/env bash

RESULTS_FILE=${1:-results.csv}

if [ ! -f "$RESULTS_FILE" ]; then
  echo "ERROR: results file '$RESULTS_FILE' not found." >&2
  exit 1
fi

##############################################
# 1) TABLE 1 : EXPLORATION TIMES (MOYENNES)
##############################################

cat <<'EOF'
\begin{table}[t]
  \centering
  \caption{Average exploration times (in seconds) per B specification and exploration strategy.}
  \label{tab:exploration-times}
  \begin{tabular}{l rr}
    \hline
    Specification & Pre-proc & Recursive \\
    \hline
EOF

awk -F',' '
BEGIN {
  # On agrège seulement par (spec, exploration), pas par algo
}

NR == 1 { next }  # skip header

NR > 1 {
  algo   = $1
  reward = $2
  expl   = $3
  t_exp  = $5

  if (t_exp == "" || t_exp ~ /^[[:space:]]*$/) next

  # Déterminer la spécification B à partir de la reward
  # - EMBEDDED  => tictac_rewarded.mch
  # - sinon     => tictac.mch
  spec = (reward == "EMBEDDED") ? "REWARDED" : "PLAIN"

  key = spec "|" expl

  sum_exp[key]   += t_exp
  count_exp[key] += 1
}

END {
  # Speclogiques : PLAIN => tictac.mch, REWARDED => tictac_rewarded.mch
  specs[1] = "PLAIN"
  specs[2] = "REWARDED"

  spec_label["PLAIN"]    = "\\texttt{tictac.mch}"
  spec_label["REWARDED"] = "\\texttt{tictac\\_rewarded.mch}"

  for (i = 1; i <= 2; i++) {
    sp = specs[i]
    label = spec_label[sp]

    # Moyenne PREPROCESS
    key = sp "|PREPROCESS"
    pre = (key in sum_exp && count_exp[key] > 0) ? sum_exp[key] / count_exp[key] : ""

    # Moyenne RECURSIVE
    key = sp "|RECURSIVE"
    rec = (key in sum_exp && count_exp[key] > 0) ? sum_exp[key] / count_exp[key] : ""

    printf("    %s & %s & %s \\\\\n", label, pre, rec)
  }
}
' "$RESULTS_FILE"

cat <<'EOF'
    \hline
  \end{tabular}
\end{table}

EOF

##############################################
# 2) TABLE 2 : LEARNING TIMES (MOYENNES)
##############################################

cat <<'EOF'
\begin{table}[t]
  \centering
  \caption{Average learning time per algorithm and reward strategy (over PREPROCESS and RECURSIVE exploration).}
  \label{tab:learning-times}
  \begin{tabular}{l rrr}
    \hline
    Algorithm & On-The-Fly & Once-and-for-all & Embedded \\
    \hline
EOF

awk -F',' '
BEGIN {
  # Ordre des algorithmes
  algo_order[1] = "VALUE_ITERATION"
  algo_order[2] = "POLICY_ITERATION"
  algo_order[3] = "MODIFIED_POLICY_ITERATION"
  algo_order[4] = "INCREMENTAL_VALUE_ITERATION"
  algo_order[5] = "BACKWARD_INDUCTION"
  algo_order[6] = "PRIORITIZED_VALUE_ITERATION"

  # Labels affichés
  algo_disp["VALUE_ITERATION"]             = "Value Iteration (VI)"
  algo_disp["POLICY_ITERATION"]            = "Policy Iteration (PI)"
  algo_disp["MODIFIED_POLICY_ITERATION"]   = "Modified PI"
  algo_disp["INCREMENTAL_VALUE_ITERATION"] = "Incremental VI"
  algo_disp["BACKWARD_INDUCTION"]          = "Backward Induction"
  algo_disp["PRIORITIZED_VALUE_ITERATION"] = "Prioritized VI"
}

NR == 1 { next }  # skip header

NR > 1 {
  algo    = $1
  reward  = $2
  expl    = $3
  t_learn = $6

  if (t_learn == "" || t_learn ~ /^[[:space:]]*$/) next

  key = algo "|" reward

  sum_learn[key]   += t_learn
  count_learn[key] += 1
}

END {
  rewards[1] = "ONTHEFLY"
  rewards[2] = "ONCEANDFORALL"
  rewards[3] = "EMBEDDED"

  for (i = 1; i <= 6; i++) {
    algo  = algo_order[i]
    label = algo_disp[algo]

    # Moyennes pour les 3 rewards
    for (j = 1; j <= 3; j++) {
      r = rewards[j]
      key = algo "|" r
      if (key in sum_learn && count_learn[key] > 0) {
        avg[j] = sum_learn[key] / count_learn[key]
      } else {
        avg[j] = ""
      }
    }

    gsub("_", "\\\\_", label)

    printf("    %s & %s & %s & %s \\\\\n",
           label, avg[1], avg[2], avg[3])
  }
}
' "$RESULTS_FILE"

cat <<'EOF'
    \hline
  \end{tabular}
\end{table}
EOF