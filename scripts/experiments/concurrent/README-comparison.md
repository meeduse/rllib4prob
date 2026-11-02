# TicTacToe: PREPROCESS / RECURSIVE / CONCURRENT comparison

This is an **additive** experimental harness. No existing B specifications,
RL algorithms, project `pom.xml`, or existing campaigns are modified.

## Scope of the timings

The benchmark deliberately reports **two different outcomes**:

- **Native exploration complete** (`nativeReadyMs`) for `PREPROCESS` and
  `RECURSIVE`: from invocation until ProB `ConsistencyChecker` finishes, or
  until the legacy-equivalent DFS has visited the reachable states. Neither
  outcome is an independently usable canonical Java graph.
- **Canonical Java graph available** (`graphReadyMs`) for every strategy:
  includes worker load + all work required to construct the complete detached
  Java graph with exact semantic state keys and labeled edges. This is the
  **primary like-for-like comparison** with CONCURRENT.

For `PREPROCESS`/`RECURSIVE`, graph construction is performed in a **separate
Java API traversal**, after their normal state exploration, and measured as
`graphExtractionMs`. The same extraction code is used for both. This is an
explicit integration approach, **not a claim to measure an optimized ProB
native graph-export facility**. For `CONCURRENT`, graph construction is
integrated in its exploration; `prefixMs` and `parallelExplorationMs` are
reported instead. Thus this benchmark compares the full *current Java
integration routes*, not absolute optimal algorithms for each tool.

`loadWallMs` measures start-to-all-workers-loaded elapsed wall time; it is **not
an addition of worker load times**. `graphReadyMs` ends before resource
cleanup; `endToEndMs` additionally includes cleanup. No separate JVM startup
costs are included, since the measurements run inside a prepared Java process.

All runs use the same existing TicTacToe B machine, full exploration, `square`
and `turn` as state variables, and equivalent canonical state and action
encodings. Each completed graph is compared *exactly* against an independently
constructed, untimed sequential reference: 5,478 states / 16,167 edges.
A mismatch, exception, or incomplete run aborts the campaign and prevents the
`.complete` success marker.

**Caveat:** `RECURSIVE` is an isolated reproduction of the existing iterative
DFS algorithm, not a call into the original private method. Both strategies
are executed on independent model copies so ProB instances and temporary
files remain isolated and can be cleaned up. `PREPROCESS` matches the existing
`ConsistencyChecker` options (`IGNORE_OTHER_ERRORS`).

## Commands (from repository root)

Smoke test with real ProB (IncFlip):

```bash
mvn test-compile
mvn exec:exec -Dexec.executable=java \
  '-Dexec.args=-cp %classpath fr.polytech.mnia.exploration.PreprocessRecursiveIncFlipTest' \
  -Dexec.classpathScope=test
```

TicTacToe short comparison (one repetition, no warmup):

```bash
bash scripts/experiments/concurrent/run_tictactoe_comparison.sh 1 1,2 1 false 0
```

Longer comparison (5 repetitions, one warmup per configuration):

```bash
bash scripts/experiments/concurrent/run_tictactoe_comparison.sh 5 1,2,4,8 1 false 1
```

The program rotates configuration order across measured repetitions to reduce
systematic order effects. To check statistical variability, examine the raw
CSV rather than only means. The code does not claim significance from one run.

The `.metadata.txt` file records the version of Git/Java/Maven and experiment
arguments. Output is written to `results/concurrent/` using unique filenames;
existing results and scripts are never overwritten.

## CSV fields

For `PREPROCESS` and `RECURSIVE`:
`graphReadyMs = loadWallMs + nativeExplorationMs + graphExtractionMs`.

For `CONCURRENT`:
`graphReadyMs = loadWallMs + prefixMs + parallelExplorationMs`.

`nativeReadyMs` is blank for `CONCURRENT` because its intermediate stage is
not an independent native ProB model checking result. The reported ProB node
count can include internal states; only the canonical Java graph counts are
compared against the independent oracle.

The baseline model checking may be fast while Java graph materialization is
slow. In that case, it is accurate to claim a faster **RL-ready graph pipeline**
through the ProB Java API, *not* a faster native ProB model checker.

For this archived TicTacToe machine, the model checker is also required to
report the previously observed **5,479 ProB nodes** (including its synthetic
root) before a `PREPROCESS` run can be marked successful. If ProB behavior or
configuration changes, investigate this invariant before interpreting timings.
