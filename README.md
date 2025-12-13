# Reinforcement Learning over ProB – Java Library

This project provides a Java-based toolchain for running **Model-Based Reinforcement Learning (MBRL)** algorithms over **formal B specifications** executed through the ProB model checker.  
It supports multiple exploration strategies, several reward computation mechanisms, and a variety of MBRL algorithms including Value Iteration, Policy Iteration, and their variants.

The goal is to treat a B machine as an executable environment, allowing RL agents to learn directly from a **formally specified** transition system without requiring any handcrafted simulator.

## Features

- Integration with the ProB Java API  
- Two environment exploration strategies:
  - PREPROCESS (global model-checking of the state space)
  - RECURSIVE (on-the-fly DFS-like exploration)

- Three reward strategies:
  - ON_THE_FLY (constraint-based evaluation)
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

- Java 17 
- Maven 3.8+  
- ProB Java API  
- Linux or macOS recommended

## Project Structure

```text
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
├── mbrl (model-based)
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
└── mfrl (model-free)
    └── (under experimentation)
```

## Compile

```
mvn clean compile
```

## Running Experiments

```
mvn -q exec:java -Dexec.args="<ALGO> <REWARD> <EXPLORATION>"
```

Defaults:
```
BACKWARD_INDUCTION ONCE_AND_FOR_ALL PREPROCESS
```

### Available Algorithms

VALUE_ITERATION  
POLICY_ITERATION  
MODIFIED_POLICY_ITERATION  
INCREMENTAL_VALUE_ITERATION  
BACKWARD_INDUCTION  
PRIORITIZED_VALUE_ITERATION  

### Reward Strategies

ON_THE_FLY  
ONCE_AND_FOR_ALL  
EMBEDDED  

### Exploration Strategies

PREPROCESS  
RECURSIVE  
NONE  

## Examples

```
mvn -q exec:java -Dexec.args="MODIFIED_POLICY_ITERATION ONCE_AND_FOR_ALL PREPROCESS"
```

```
mvn -q exec:java -Dexec.args="POLICY_ITERATION EMBEDDED RECURSIVE"
```

```
mvn -q exec:java -Dexec.args="INCREMENTAL_VALUE_ITERATION ONCE_AND_FOR_ALL"
```

## Environment Selection

Reward strategy determines the B machine:

- tictactoe.mch for ON_THE_FLY and ONCE_AND_FOR_ALL  
- tictac_rewarded.mch for EMBEDDED

## Output Example

```
Selected algorithm = POLICY_ITERATION
Selected reward strategy = EMBEDDED
Selected machine = /TicTacToe/tictac_rewarded.mch
Exploration strategy = PREPROCESS
```

## Extending the Framework

1. Extend Agent  
2. Implement learn()  
3. Add enum entry  
4. Register in AgentFactory  

## License

Attribution Assurance License

Copyright (c) 2025  
Akram Idani

All rights reserved.

This license gives everyone permission to use, copy, modify, and distribute this software and its documentation for any purpose, without fee, provided that the following attribution requirement is met:

Any work or product that uses, includes, or is derived from this software must display the following acknowledgment:

   "This product includes software developed by Akram Idani.  
    Original source available at: https://github.com/meeduse/rllib4prob"

This acknowledgment must be displayed in:  
   (1) the user interface of any application using this software,  
   or  
   (2) the documentation and marketing materials accompanying the product.

The name of the author may not be used to endorse or promote products  
derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTIES OR CONDITIONS OF ANY  
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO MERCHANTABILITY,  
FITNESS FOR A PARTICULAR PURPOSE, AND NON-INFRINGEMENT. IN NO EVENT SHALL  
THE AUTHOR BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER LIABILITY, WHETHER  
IN CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION  
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
