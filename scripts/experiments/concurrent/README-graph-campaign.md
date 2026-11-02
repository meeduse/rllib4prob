# RlLib4ProB — homogeneous graph-construction campaign

This is an **additive** experimental harness. It does not modify existing
RL algorithms, existing ProB wrappers, B specifications, or older campaigns.

## Comparisons

Six configurations per B machine:

- `PREPROCESS`: ProB native model checking; then **one Java API graph traversal** to obtain the complete explicit Java graph.
- `RECURSIVE`: the equivalent original state-only iterative DFS; then the same Java API graph traversal.
- `CONCURRENT`: 1, 2, 4 and 8 independent ProB workers producing the Java graph directly. Replicated-prefix depth `d=1`; reassignment disabled for the primary experiment.

Run the script from anywhere inside the checked-out project:

```sh
python3 scripts/experiments/concurrent/run_graph_campaign.py \
    --mode pilot --models frozenlake4 --workers 1,2
```

After the pilot has passed, try one medium B machine:

```sh
python3 scripts/experiments/concurrent/run_graph_campaign.py \
    --mode pilot --models tictactoe --workers 1,2,4,8
```

Only after pilots have succeeded (especially checks for unsupported B values, workspaces and available memory), run:

```sh
python3 scripts/experiments/concurrent/run_graph_campaign.py \
    --mode full --models all --workers 1,2,4,8 \
    --java-heap 4g --timeout-minutes 120
```

All benchmark IDs (10):
`tictactoe,frozenlake4,frozenlake10,taxi5,taxi10,taxi30,autov2,mountaincar,interlocking,puzzle`.

The full campaign involves **600 measured executions** (10 models × 6 configurations × 10 runs), plus one untimed warm-up per configuration and one reference graph per model. Each model gets a separate JVM invocation; only **one model campaign runs at a time**. Configuration order rotates each round to reduce fixed-order bias.

> Important: memory consumption for Interlocking and Puzzle with 8 workers may be large. Run a pilot with 1 and 2 workers on these machines before adding 4 and 8. An OOM, mismatch or timeout invalidates that model's campaign; the script stops rather than counting it as a success.

## Measurements (millisecond wall-clock)

| CSV field | Meaning |
|---|---|
| `loadWallMs` | Time from launcher invocation to `ProBWorkerSession.open()` returning; for CONCURRENT, until all worker sessions are loaded. Includes copying B files, API and engine initialization. It is **not a sum of worker times**. |
| `nativeExplorationMs` | Native model checking (`PREPROCESS`) or pre-existing DFS state discovery (`RECURSIVE`), including initial state setup. |
| `graphExtractionMs` | One additional traversal via `getOutTransitions` to construct canonical Java graph for `PREPROCESS` or `RECURSIVE`. |
| `prefixMs` | Concurrent replicated-prefix materialization and global commit after worker load. |
| `parallelExplorationMs` | Work after prefix until global Java graph is complete. |
| `graphReadyMs` | **Primary metric**: real time from starting the strategy until equivalent canonical Java graph is ready. Includes loading. |
| `cleanupMs` | Time after graph-ready to all worker ProB sessions closed. |
| `endToEndMs` | `graphReadyMs + cleanupMs`; secondary metric. |

Model-loading is timed separately from extraction; however, ProB administrative setup/initialization is triggered after `open` and thus belongs to the next phase (`nativeExplorationMs` or `prefixMs`). This definition is fixed across experiments and must be disclosed in the paper.

Each result is checked against one untimed, full, independent sequential canonical reference for that machine. It checks **exact graph equality** (full state and labeled-transition sets), not only counts. Existing audited state and transition counts are separately checked.

## Integrity / reproducibility

- For each model: `model.csv`, `model.log`, `model.csv.complete` (only after success).
- `manifest.json`: commit/branch, Java and Maven versions, OS, source machine hashes, full resource tree hashes, parameters and status.
- `summary_by_configuration.csv`: means/medians/standard deviations and phase means, **only if all requested models completed**.
- `.complete` at campaign root: every model completed successfully.
- Nonzero status, interruption, timeout, mismatch, incomplete run, or cleanup failure: **no success marker**, output kept for investigation, remaining models not started.
- `peak_process_tree_rss_kib_diagnostic` in manifest is a coarse sampled **RSS sum** across the launched Maven/Java/ProB process tree. This is not per-run memory, nor exact unique physical RAM. Do not use it as a rigorous memory result without a dedicated monitor.

The script calls `mvn test-compile` once, then launches a separate JVM via `mvn exec:exec` for each model. Maven process startup is outside the measured `graphReadyMs`. Do not confuse this with the user's wall-clock time running the entire campaign.

The original `analysis.zip` includes historical exploration times but **not** matching load, extraction and graph-ready durations. Keep it as prior results, not a substitute for the new uniform campaign.

## Before running a publication campaign

Commit the new harness on `feature/concurrent-exploration` to make the recorded `gitCommit` meaningful. Close CPU-intensive applications and record Mac model, processor, memory and power settings. Prefer a stable power source and ensure no concurrent jobs are launched in parallel with the campaign. Leave `--reassignment` off for the main study; analyze it separately.

**Known caveat:** exact canonical encoding currently covers a validated subset of B values; the new pilot intentionally fails rather than silently passing models that contain unsupported values. This is a correctness test, not a benchmark failure to ignore.
