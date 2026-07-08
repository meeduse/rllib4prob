#!/usr/bin/env python3

import sys
import pandas as pd

if len(sys.argv) < 2:
    print("Usage: python analyze_results.py experiments/<session>/results.csv")
    sys.exit(1)

input_file = sys.argv[1]

cols = [
    "run",
    "algorithm",
    "reward",
    "exploration",
    "environment",
    "states",
    "t_explore_sec",
    "t_learn_sec",
    "iterations_or_updates",
    "status",
    "exit_code",
    "log_file",
]

df = pd.read_csv(
    input_file,
    sep="\t",
    names=cols,
    header=None,
    engine="python"
)

# Supprimer toutes les lignes d'en-tête répétées
df = df[df["run"].astype(str) != "run"]
df = df[df["algorithm"].astype(str) != "algorithm"]

# Garder seulement les lignes expérimentales
df = df[pd.to_numeric(df["run"], errors="coerce").notna()]

# Conversion numérique
for col in [
    "run",
    "states",
    "t_explore_sec",
    "t_learn_sec",
    "iterations_or_updates",
    "exit_code",
]:
    df[col] = pd.to_numeric(df[col], errors="coerce")

# Résumé par configuration complète
summary_by_configuration = (
    df.groupby(["environment", "algorithm", "reward", "exploration"])
    .agg(
        runs=("run", "count"),
        successes=("status", lambda x: (x == "SUCCESS").sum()),
        failures=("status", lambda x: (x == "FAILED").sum()),
        timeouts=("status", lambda x: (x == "TIMEOUT").sum()),
        success_rate=("status", lambda x: (x == "SUCCESS").mean()),
        timeout_rate=("status", lambda x: (x == "TIMEOUT").mean()),

        states_mean=("states", "mean"),
        states_std=("states", "std"),

        exploration_time_mean=("t_explore_sec", "mean"),
        exploration_time_std=("t_explore_sec", "std"),

        learning_time_mean=("t_learn_sec", "mean"),
        learning_time_std=("t_learn_sec", "std"),
        learning_time_min=("t_learn_sec", "min"),
        learning_time_max=("t_learn_sec", "max"),

        iterations_mean=("iterations_or_updates", "mean"),
        iterations_std=("iterations_or_updates", "std"),
    )
    .reset_index()
    .round(4)
)

summary_by_configuration.to_csv("summary_by_configuration.csv", index=False)

# Résumé par environnement
summary_by_environment = (
    df.groupby("environment")
    .agg(
        runs=("run", "count"),
        successes=("status", lambda x: (x == "SUCCESS").sum()),
        failures=("status", lambda x: (x == "FAILED").sum()),
        timeouts=("status", lambda x: (x == "TIMEOUT").sum()),
        success_rate=("status", lambda x: (x == "SUCCESS").mean()),
        timeout_rate=("status", lambda x: (x == "TIMEOUT").mean()),
        states_mean=("states", "mean"),
        exploration_time_mean=("t_explore_sec", "mean"),
        learning_time_mean=("t_learn_sec", "mean"),
        iterations_mean=("iterations_or_updates", "mean"),
    )
    .reset_index()
    .round(4)
)

summary_by_environment.to_csv("summary_by_environment.csv", index=False)

# Résumé par environnement + algorithme
summary_by_env_algorithm = (
    df.groupby(["environment", "algorithm"])
    .agg(
        runs=("run", "count"),
        successes=("status", lambda x: (x == "SUCCESS").sum()),
        failures=("status", lambda x: (x == "FAILED").sum()),
        timeouts=("status", lambda x: (x == "TIMEOUT").sum()),
        success_rate=("status", lambda x: (x == "SUCCESS").mean()),
        timeout_rate=("status", lambda x: (x == "TIMEOUT").mean()),
        states_mean=("states", "mean"),
        exploration_time_mean=("t_explore_sec", "mean"),
        learning_time_mean=("t_learn_sec", "mean"),
        learning_time_std=("t_learn_sec", "std"),
        iterations_mean=("iterations_or_updates", "mean"),
    )
    .reset_index()
    .round(4)
)

summary_by_env_algorithm.to_csv("summary_by_env_algorithm.csv", index=False)

# Résumé par environnement + stratégie de reward
summary_by_env_reward = (
    df.groupby(["environment", "reward"])
    .agg(
        runs=("run", "count"),
        successes=("status", lambda x: (x == "SUCCESS").sum()),
        timeouts=("status", lambda x: (x == "TIMEOUT").sum()),
        success_rate=("status", lambda x: (x == "SUCCESS").mean()),
        learning_time_mean=("t_learn_sec", "mean"),
        learning_time_std=("t_learn_sec", "std"),
    )
    .reset_index()
    .round(4)
)

summary_by_env_reward.to_csv("summary_by_env_reward.csv", index=False)

# Top configurations par environnement
best_by_environment = (
    summary_by_configuration
    .sort_values(
        ["environment", "success_rate", "learning_time_mean"],
        ascending=[True, False, True]
    )
    .groupby("environment")
    .head(5)
)

best_by_environment.to_csv("best_configurations_by_environment.csv", index=False)

print("Fichiers générés :")
print("- summary_by_configuration.csv")
print("- summary_by_environment.csv")
print("- summary_by_env_algorithm.csv")
print("- summary_by_env_reward.csv")
print("- best_configurations_by_environment.csv")