# RLLib4ProB

**Model-based reinforcement learning over executable B specifications, using ProB.**

RLLib4ProB is a Java research library that uses the [ProB](https://prob.hhu.de/) model checker as the execution backend for reinforcement learning. Instead of implementing a separate simulator, the library obtains states, enabled operations, and transitions from formal B models. It supports offline planning over explored state spaces and online learning through interaction with the model.

This repository contains the implementation, formal environments, experimental scripts, raw results, and analysis artifacts associated with the research project *Turning a Model Checker into an Executable Backend for Model-Based Reinforcement Learning*.

## Repository contents

| Path | Contents |
| --- | --- |
| [`src/`](src/) | Java library, RL agents and algorithms, ProB integration, and formal B environments. |
| [`config/`](config/) | Configuration files and agent parameters. |
| [`scripts/`](scripts/) | Scripts for running, validating, auditing, and aggregating experiments. |
| [`experiments/`](experiments/) | Original experiment campaigns, including per-run `results.csv`, `summary.json`, and provenance information. |
| [`analysis/`](analysis/) | Aggregated results and summaries used to analyse the experiments. |
| [`analysis/audits/`](analysis/audits/) | Nine historical CSV audit reports for the offline experiments. |
| [`tictactoe_results.zip`](tictactoe_results.zip) | Validated Tic-Tac-Toe experimental results (360 runs), kept as a separate archive. |
| [`pom.xml`](pom.xml) | Maven build configuration. |

## Experimental data

The repository preserves **2,520 experimental runs**:

- **2,160 offline runs**, recorded across nine benchmark variants and represented by nine audit reports (240 runs each). Their recorded outcomes comprise **2,152 verified successes** and **8 verified non-convergences**.
- **360 Tic-Tac-Toe runs**, preserved in `tictactoe_results.zip` and included in the aggregated results.

The main analysis files are:

- [`analysis/results_all.csv`](analysis/results_all.csv) — aggregated observations from all 2,520 runs.
- [`analysis/summary_by_configuration.csv`](analysis/summary_by_configuration.csv) — results summarized by experimental configuration.
- [`analysis/audits/`](analysis/audits/) — detailed offline audit reports, each containing 80 columns.

The offline campaign manifests and the detailed experimental protocol are documented in [`scripts/experiments/offline/campaigns/README.md`](scripts/experiments/offline/campaigns/README.md).

## Building and inspecting experiments

The Java project uses Maven:

```bash
mvn clean compile
```

To inspect the configuration matrix of an offline benchmark without launching ProB:

```bash
python3 scripts/experiments/offline/manage_campaigns.py frozenlake4 --plan
```

To inspect the existing final campaigns:

```bash
python3 scripts/experiments/offline/manage_campaigns.py frozenlake4 --status
```

To reconstruct its offline audit from the original campaign files (without the former redundant final-results ZIP archives):

```bash
python3 scripts/experiments/offline/manage_campaigns.py frozenlake4 --audit
```

**Note:** `--audit` writes audit CSV files under `analysis/audits/`. The original historical reports are deliberately retained unchanged in Git. Recomputed `auditTimeDeltaSeconds` values may differ textually from historical values because of microsecond-scale numerical rounding; the measured differences remain within the audit tolerance. For comparison without changing tracked reports, run the audit in a separate working copy. See the campaign README for details.

The offline test suite can be run without launching ProB:

```bash
python3 -m unittest discover -s scripts/experiments/offline/tests -p 'test_*.py' -v
```

## Scope and reproducibility

RLLib4ProB brings together executable formal specifications, reinforcement-learning implementations, and traceable experimental evidence. Final offline campaigns remain in `experiments/offline/`; their audits are generated directly from the original CSV and JSON files. The archived Tic-Tac-Toe results are a separate dataset and are not required for the offline audit workflow.
