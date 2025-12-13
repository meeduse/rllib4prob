# Reinforcement Learning over ProB – Java Library

This repository provides a **Java library for Reinforcement Learning over formal B specifications**, using **ProB** as the execution and state-space exploration engine.

The library treats a **B machine as an executable RL environment**, allowing agents to learn directly from a **formally specified transition system**, without requiring any handcrafted simulator.

It supports **model-based reinforcement learning (MBRL)** algorithms, both **offline (planning-based)** and **online (interaction-based)**, and is intended for **research, experimentation, and teaching** at the intersection of **formal methods** and **reinforcement learning**.

---

## Key Concepts

### Formal Environment
- The environment is a **B machine** executed by **ProB**
- States and transitions are taken directly from the formal specification
- There is no abstraction gap between the model and the learning environment

### Model-Based Reinforcement Learning
All algorithms in this library assume access to, or learning of, a **transition model**:
- Offline algorithms rely on a fully explored state space
- Online algorithms incrementally build a partial model during interaction

---

## Offline vs Online MBRL

### Offline Model-Based RL (Planning-Oriented)

Offline algorithms assume that the **reachable state space is explored before learning**.

Typical workflow:
1. Explore the state space using ProB
2. Build a complete transition graph
3. Apply dynamic programming algorithms over the graph

Characteristics:
- Deterministic and reproducible
- Well suited for benchmarking and analysis
- Requires explicit state-space exploration

Algorithms:
- Value Iteration
- Policy Iteration
- Modified Policy Iteration
- Incremental Value Iteration
- Prioritized Value Iteration
- Backward Induction

Exploration strategies:
- PREPROCESS
- RECURSIVE

---

### Online Model-Based RL (Interaction-Oriented)

Online algorithms learn by **interacting directly with the environment**, while incrementally building a model of observed transitions.

Typical workflow:
1. Start from the initial state
2. Select actions using an exploration policy
3. Observe transitions and rewards
4. Update the learned model and value function
5. Perform planning updates from the learned model

Characteristics:
- No exhaustive exploration required
- Better scalability to large or infinite state spaces
- Closer to classical reinforcement learning settings

Algorithms:
- Dyna-Q
- Dyna-Q+

Important:
For online algorithms, you must use:
```
ExplorationStrategy.NONE
```
Explicit state-space exploration must be disabled.

---

## Project Structure

```
src/main/java/fr/polytech/mnia
├── App.java
├── Agent.java
├── AgentFactory.java
├── AlgorithmId.java
├── TicTacToe.java
│
├── Environment
│   ├── Environment.java
│   ├── ExplorationStrategy.java
│   ├── RewardStrategy.java
│   └── MyProb.java
│
├── mbrl
│   ├── offline
│   │   ├── ValueIteration.java
│   │   ├── PolicyIteration.java
│   │   ├── ModifiedPolicyIteration.java
│   │   ├── IncrementalValueIteration.java
│   │   ├── PrioritizedValueIterationV1.java
│   │   └── BackwardInductionV1.java
│   │
│   └── online
│       ├── DynaQ.java
│       └── DynaQPlus.java
│
└── mfrl
    └── (under experimentation)
```

---

## Requirements

- Java 17
- Maven 3.8+
- ProB Java API
- Linux or macOS recommended

---

## Compile

```
mvn clean compile
```

---

## Running Experiments

```
mvn -q exec:java -Dexec.args="<ALGORITHM> <REWARD> <EXPLORATION>"
```

Defaults:
```
BACKWARD_INDUCTION ONCEANDFORALL PREPROCESS
```

---

## Algorithms

Offline:
- VALUE_ITERATION
- POLICY_ITERATION
- MODIFIED_POLICY_ITERATION
- INCREMENTAL_VALUE_ITERATION
- PRIORITIZED_VALUE_ITERATION
- BACKWARD_INDUCTION

Online:
- DYNA_Q
- DYNA_Q_PLUS

---

## Reward Strategies

- ONTHEFLY
- ONCEANDFORALL
- EMBEDDED

---

## Exploration Strategies

- PREPROCESS
- RECURSIVE
- NONE

---

## Examples

Offline learning:
```
mvn -q exec:java -Dexec.args="POLICY_ITERATION ONCEANDFORALL PREPROCESS"
```

Online learning (Dyna-Q):
```
mvn -q exec:java -Dexec.args="DYNA_Q ONCEANDFORALL NONE"
```

Online learning (Dyna-Q+):
```
mvn -q exec:java -Dexec.args="DYNA_Q_PLUS ONCEANDFORALL NONE"
```

---

## Environment Selection

- `tictactoe.mch` is used for ONTHEFLY and ONCEANDFORALL
- `tictac_rewarded.mch` is used for EMBEDDED

---

## Extending the Library

To add a new algorithm:
1. Extend `Agent`
2. Implement `learn()` and `getQValues()`
3. Add an entry to `AlgorithmId`
4. Register the algorithm in `AgentFactory`

---

## License

Attribution Assurance License

Copyright (c) 2025  
Akram Idani

Permission is granted to use, copy, modify, and distribute this software and its documentation for any purpose, provided that the following attribution is included:

"This product includes software developed by Akram Idani.  
Original source available at: https://github.com/meeduse/rllib4prob"

THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
