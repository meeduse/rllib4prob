# Developer Manual – Integrating a New B Specification

This document explains how to extend the project by adding a **new B specification** as an environment for the reinforcement-learning algorithms.

## 1. Add your B machine to the resources

Place your `.mch` files inside:

```
src/main/resources/<YourModelName>/
```

Example:

```
src/main/resources/JobScheduler/jobsched.mch
src/main/resources/JobScheduler/jobsched_rewarded.mch
```

## 2. Create a new Java environment class

Extend `Environment`:

```java
public class JobSchedulerEnv extends Environment {

    public JobSchedulerEnv(String machinePath, RewardStrategy rewardStrategy) {
        super(machinePath, rewardStrategy);
    }

    @Override
    public void prettyPrint(State s) {
        System.out.println("State: " + s.eval("jobs"));
    }
}
```

## 3. Update machine selection in App.java

Replace Tic-Tac-Toe logic:

```java
private static String resolveMachinePath(RewardStrategy rs) {
    if (rs == RewardStrategy.EMBEDDED)
        return "/JobScheduler/jobsched_rewarded.mch";
    return "/JobScheduler/jobsched.mch";
}
```

Replace environment creation:

```java
Environment env = new JobSchedulerEnv(machinePath, rewardStrategy);
```

## 4. Reward strategy options

### ON_THE_FLY
Rewards computed dynamically with `eval()`.

### ONCE_AND_FOR_ALL
Rewards pre-computed during exploration.

### EMBEDDED
Reward is returned directly by a B operation.

## 5. Running experiments

Examples:

```
mvn exec:java -Dexec.args="VALUE_ITERATION ONCEANDFORALL PREPROCESS"
mvn exec:java -Dexec.args="POLICY_ITERATION EMBEDDED RECURSIVE"
mvn exec:java -Dexec.args="BACKWARD_INDUCTION ON_THE_FLY PREPROCESS"
```

## 6. Troubleshooting

- Check that ProB loads the machine without errors.
- Ensure every move operation is enabled in the current state.
- Make sure the reward function is deterministic if using ONCE_AND_FOR_ALL.

## 7. Summary

To add a new B environment:

1. Add the `.mch` file to `resources/`.
2. Create the corresponding Java class extending `Environment`.
3. Update `App.java` to select this machine.
4. Choose reward strategy and exploration strategy.
5. Run experiments via Maven.

