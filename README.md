# Reinforcement Learning over ProB – Java Library

This project provides a Java-based toolchain for running **Model-Based Reinforcement Learning (MBRL)** algorithms over **formal B specifications** executed through the ProB model checker.  
It supports multiple exploration strategies, several reward computation mechanisms, and a variety of MBRL algorithms including Value Iteration, Policy Iteration, and their variants.

The main motivation is to treat a B machine as an executable environment, allowing RL agents to learn directly from a formally specified transition system without requiring any handcrafted simulator.

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
  - tictactoe.mch
  - tictac_rewarded.mch

- Implemented MBRL algorithms:
  - Value Iteration
  - Policy Iteration
  - Modified Policy Iteration
  - Incremental Value Iteration
  - Prioritized Value Iteration
  - Backward Induction

- Experiment orchestration through App.java.

## Requirements

- Java 21 or later  
- Maven 3.8+  
- ProB Java API  
- Linux or macOS recommended

## Project Structure

```text
src/main/java/fr/polytech/mnia/
├── App.java
├── Environment.java
├── TicTacToe.java
├── MyProb.java
│
├── Agent.java
├── AgentFactory.java
├── AlgorithmId.java
│
├── ValueIteration.java
├── PolicyIteration.java
├── ModifiedPolicyIteration.java
├── IncrementalValueIteration.java
├── PrioritizedValueIteration.java
├── BackwardInductionV1.java
│
├── ExplorationStrategy.java
└── RewardStrategy.java

## Running Experiments

```
mvn exec:java -Dexec.args="<ALGO> <REWARD>"
```

Available Algorithms:

VALUE_ITERATION  
POLICY_ITERATION  
MODIFIED_POLICY_ITERATION  
INCREMENTAL_VALUE_ITERATION  
BACKWARD_INDUCTION  
PRIORITIZED_VALUE_ITERATION  

Reward Strategies:

ON_THE_FLY  
ONCE_AND_FOR_ALL  
EMBEDDED  

If no arguments are given, the default is:

```
BACKWARD_INDUCTION ONCE_AND_FOR_ALL
```

## Examples

Value Iteration with on-the-fly reward evaluation:

```
mvn exec:java -Dexec.args="VALUE_ITERATION ON_THE_FLY"
```

Policy Iteration with embedded rewards:

```
mvn exec:java -Dexec.args="POLICY_ITERATION EMBEDDED"
```

Incremental Value Iteration with once-and-for-all rewards:

```
mvn exec:java -Dexec.args="INCREMENTAL_VALUE_ITERATION ONCE_AND_FOR_ALL"
```

## Exploration Strategy Selection

Exploration strategy is chosen in App.java:

```java
agent.learn(ExplorationStrategy.PREPROCESS);
```

Available strategies:

PREPROCESS  
RECURSIVE  
NONE  

## Environment Selection

Reward strategy determines the B machine:

- tictactoe.mch for ON_THE_FLY and ONCE_AND_FOR_ALL  
- tictac_rewarded.mch for EMBEDDED

## Output and Logging

Example output:

```
Selected algorithm = POLICY_ITERATION
Selected reward strategy = EMBEDDED
Selected machine = /TicTacToe/tictac_rewarded.mch
```

## Extending the Framework

1. Extend Agent  
2. Implement learn()  
3. Add enum entry  
4. Register in AgentFactory  

## License

Private research code. No license assigned.
