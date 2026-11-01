# Computing revision — reproducible experiments for six offline algorithms

Versioned source under `fresh/scripts/experiments/offline/`. This supplements,
**does not replace**, the previously validated `ivi_tictactoe` scripts and
existing `fresh/experiments/` data. It makes **no Java or B specification changes**.

## Algorithms and end conditions

| Abbreviation | `--algorithm` | Successful end condition |
|---|---|---|
| VI | `VALUE_ITERATION` | `CONVERGED` + verified Bellman residual ≤ θ |
| PI | `POLICY_ITERATION` | `CONVERGED` + stable policy + both residuals ≤ θ |
| MPI | `MODIFIED_POLICY_ITERATION` | Same checks, despite partial policy evaluation |
| IVI | `INCREMENTAL_VALUE_ITERATION` | `CONVERGED` + residual ≤ θ, explicit seed and `BFS_ACTIONS` |
| PVI | `PRIORITIZED_VALUE_ITERATION` | `CONVERGED` + residual ≤ θ (not just empty priority queue) |
| BI | `BACKWARD_INDUCTION` | `HORIZON_COMPLETED`, with completedHorizon == horizon > 0; **not** convergence |

Algorithms Dyna-Q and Dyna-Q+ are intentionally **excluded** because they are
not evaluated in the Computing article.

## Usage from the `fresh` root

Start with the preflight, which neither compiles nor executes ProB:

```bash
bash scripts/experiments/offline/run_campaign.sh \
  --algorithm VI --reward ONCEANDFORALL --exploration PREPROCESS \
  --environment tictactoe --dry-run
```

Perform a single smoke-test run before full campaigns:

```bash
bash scripts/experiments/offline/run_campaign.sh \
  --algorithm VI --reward ONCEANDFORALL --exploration PREPROCESS \
  --environment tictactoe --count 1 --expected-states 5478
```

Ten reproducibly seeded IVI runs (once the campaign protocol is finalized):

```bash
bash scripts/experiments/offline/run_campaign.sh \
  --algorithm IVI --reward ONCEANDFORALL --exploration PREPROCESS \
  --environment tictactoe --count 10 --first-seed 0 --expected-states 5478
```

For VI, PI, MPI, PVI, and BI, `--count 10` means **ten independent JVM runs
for timing dispersion**, not ten different random seeds. No random seed is
invented for deterministic algorithms.

Supported reward strategies: `ONCEANDFORALL`, `ONTHEFLY`, and `EMBEDDED`.
Supported explorations: `PREPROCESS` and `RECURSIVE`. `NONE` is deliberately
excluded because these six offline algorithms expect a fully explored graph.

TicTacToe maps `EMBEDDED` automatically to `tictacembedded` (the separate B
machine). For other environments, explicitly choose the factory's embedded
key, e.g. `--environment puzzleembedded`, when using EMBEDDED rewards.
The launcher verifies the exact `EnvironmentFactory.java` routing and the
presence of its B machine **before compilation**. Unknown names are rejected
instead of silently falling back to TicTacToe.

**Important:** the other B models still require individual verification of
reward equivalence and parameter configuration before full campaigns. The
existence of a CLI option does not mean its scientific validity has been
confirmed. Check `config/agent-parameters.properties` (especially BI horizon)
for each environment. For later benchmarks prefer `--count 1` initially.

## Provenance and outputs

Each new campaign is in `fresh/experiments/offline/<unique-name>/`:

- `protocol.json`: the exact parameters, environment, B machine, Git context,
  Java/Maven versions, OS, algorithm-specific seed policy;
- `input_sha256.txt`: per-file hashes of the frozen source/config inputs;
- `inputs/source_snapshot.tar.gz`: compressed source, B machines and config
  sufficient for inspecting the algorithm and reconstructing the experiment;
- `logs/run_000.log`, etc.: full raw Java stdout/stderr and exit code;
- `results.csv`: one typed-schema row per run, preserving the reported status;
- `summary.json`: descriptive statistics **only over verified successes**, plus
  counts of nonconvergent, incomplete and invalid runs.

Java is launched in a **separate JVM for each run** (not `mvn exec:java`),
so IVI's Java `-D` properties reach the correct JVM. Maven only compiles and
resolves the runtime classpath once before the campaign. All returned Java
counter and timing fields are kept (backup definitions differ across algorithms,
so raw backup counts must not automatically be interpreted as equal units of work); `totalSeconds` means only *exploration +
learning*, with ordering included in learning where appropriate, not Maven
compilation or JVM startup. Comparing times requires the same machine,
settings, and no competing workloads. These timings alone are not a statistical
significance analysis.

## Analysis tables without rerunning

```bash
python3 scripts/experiments/offline/aggregate.py
```

This creates or replaces only two **derived** files under `fresh/analysis/`:
`results_all.csv` and `summary_by_configuration.csv`. It reads all recognized
`experiments/**/results.csv`, including the old IVI campaigns. Original CSVs,
logs and protocols are not touched. BI horizon completion remains distinct
from Bellman convergence. The `results_all.csv` includes an origin and schema
column for tracing every article number to raw measurements.

## Git caution

The folder `fresh/` was inside the *parent* Git repository `rllib4prob/` at
last check. A parent commit hash alone is not proof that untracked files in
`fresh` are committed. The scripts record Git context but also freeze input
source files and SHA-256 hashes. Before publication, confirm the code archive,
results and scripts are committed to the intended public repository.
