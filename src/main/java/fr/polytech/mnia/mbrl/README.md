# Model-Based Reinforcement Learning (MBRL)

This package provides a collection of **model-based reinforcement learning algorithms** built on top of formally specified environments animated and explored using **ProB**.

The architecture distinguishes between **offline** and **online** model-based learning, reflecting different assumptions about when and how the environment model is made available to the learner.

---

## Package structure

```text
mbrl
├── offline
│   ├── ValueIteration.java
│   ├── PolicyIteration.java
│   ├── ModifiedPolicyIteration.java
│   ├── IncrementalValueIteration.java
│   ├── PrioritizedValueIterationV1.java
│   └── BackwardInductionV1.java
│
└── online
    ├── DynaQ.java
    └── DynaQPlus.java
```

---

## Offline model-based reinforcement learning

### Principle

Offline model-based algorithms assume that the **complete transition model of the environment is available before learning begins**. In our setting, this means that the entire reachable state space of the B specification is explored upfront using ProB.

Learning is then performed over this fixed model.

### Characteristics

- The full set of reachable states and transitions is computed *before* learning.
- Learning relies on dynamic programming techniques.
- Value propagation assumes a static, complete model.
- Convergence is guaranteed once the model is fully known.
- Learning performance is largely determined by the cost of state-space exploration.

### Exploration requirement

Because these algorithms require the full model, **an explicit exploration phase is mandatory**.

Supported exploration strategies are:
```java
ExplorationStrategy.PREPROCESS
```
or
```java
ExplorationStrategy.RECURSIVE
```

### Algorithms

- Value Iteration
- Policy Iteration
- Modified Policy Iteration
- Incremental Value Iteration
- Prioritized Value Iteration
- Backward Induction

---

## Online model-based reinforcement learning

### Principle

Online model-based algorithms **do not require an explicit prior exploration of the environment**. Instead, they interleave real interaction, model learning, and planning.

### Characteristics

- States are discovered on demand.
- The model is learned incrementally.
- Planning is performed using the learned model.
- Learning starts immediately from the initial state.

### Exploration strategy

For online algorithms, **no explicit exploration must be triggered**.

Always use:
```java
ExplorationStrategy.NONE
```

### Algorithms

- Dyna-Q
- Dyna-Q+

Dyna-Q+ extends Dyna-Q with an exploration bonus that encourages revisiting rarely used transitions.

---

| Aspect | Offline | Online |
|------|--------|--------|
| Model | Known upfront | Learned incrementally |
| Exploration | Required | None |
| Strategy | PREPROCESS / RECURSIVE | NONE |
| Examples | VI, PI, BI | Dyna-Q, Dyna-Q+ |

---

## Example

```bash
mvn exec:java -Dexec.args="DYNA_Q ONCEANDFORALL NONE"
```

