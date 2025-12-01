# Reinforcement Learning over ProB – Java Library

This project provides a Java-based toolchain for running **Model-Based Reinforcement Learning (MBRL)** algorithms over **formal B specifications** executed through the ProB model checker.  
It supports multiple exploration strategies, several reward computation mechanisms, and a variety of MBRL algorithms including Value Iteration, Policy Iteration, and their variants.

The main motivation is to treat a B machine as an executable environment, allowing RL agents to learn directly from a formally specified transition system without requiring any handcrafted simulator.

---

## Features

- Integration with the ProB Java API  
- Two environment exploration strategies:
  - PREPROCESS (global model-checking of the state space)
  - RECURSIVE (on-the-fly DFS-like exploration)

- Three reward strategies:
  - ON_THE_FLY (constraint-based evaluation after each transition)
  - ONCE_AND_FOR_ALL (precomputed during exploration)
  - EMBEDDED (reward encoded directly in the B machine)

- Two environment specifications:
  - `tictactoe.mch`
  - `tictac_rewarded.mch`

- Implemented MBRL algorithms:
  - Value Iteration
  - Policy Iteration
  - Modified Policy Iteration
  - Incremental Value Iteration
  - Prioritized Value Iteration
  - Backward Induction

- Experiment orchestration through `App.java`.

---

## Requirements

- Java 21 or later  
- Maven 3.8+  
- ProB Java API (declared as a Maven dependency)  
- Linux or macOS recommended

---

## Project Structure