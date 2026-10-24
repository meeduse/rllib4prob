package fr.polytech.mnia.Environment;

import java.util.HashMap;
import java.util.Map;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Frozen Lake rewards:
 * ONCEANDFORALL: subscribed B formula + lazy Java state-ID cache.
 * EMBEDDED: return value of the B operation.
 * ONTHEFLY: predicates explicitly evaluated in the successor state.
 */
public class FrozenLakeEnvironment extends ModelBasedEnvironment {

    private final Map<String, Double> rewardByStateId = new HashMap<>();
    private long rewardCacheHits;
    private long rewardCacheMisses;

    public FrozenLakeEnvironment(String filePath, RewardStrategy rewardStrategy) {
        // Subscription must happen before initialize() and state-space exploration.
        super(filePath, rewardStrategy,
              rewardStrategy == RewardStrategy.ONCEANDFORALL ? "reward" : null);
    }

    @Override
    protected double getRewardFromState(State successor) {
        final String stateId = successor.getId();
        final Double cached = rewardByStateId.get(stateId);
        if (cached != null) {
            rewardCacheHits++;
            return cached;
        }

        final double value;
        try {
            // Reads the registered IEvalElement through State.getValues().
            // It never invokes successor.eval("reward") as a fallback.
            value = evalFormulas(successor);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Missing/invalid subscribed FrozenLake reward for state " + stateId
                + "; verify rewards.def and formula subscription", ex);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalStateException(
                "Non-finite subscribed FrozenLake reward for state " + stateId);
        }
        rewardByStateId.put(stateId, value);
        rewardCacheMisses++;
        return value;
    }

    @Override
    protected double getRewardFromTransition(Transition transition) {
        try {
            var returnValues = transition.getReturnValues();
            if (returnValues == null || returnValues.isEmpty()) {
                throw new IllegalStateException("B operation has no reward return value");
            }
            final double value = Double.parseDouble(returnValues.get(0));
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Non-finite embedded reward: " + value);
            }
            return value;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Missing/invalid embedded FrozenLake reward on transition "
                + transition.getPrettyName(), ex);
        }
    }

    @Override
    protected double calculateRewardOnTheFly(State s, Transition action, State successor) {
        try {
            int hole = Integer.parseInt(successor.eval("fellInHole").toString());
            int goal = Integer.parseInt(successor.eval("reachedGoal").toString());
            if ((hole != 0 && hole != 1) || (goal != 0 && goal != 1)) {
                throw new IllegalStateException("Non-Boolean FrozenLake terminal flag");
            }
            return hole == 1 ? -1000.0 : (goal == 1 ? 1000.0 : -1.0);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Cannot compute on-the-fly FrozenLake reward for state "
                + successor.getId(), ex);
        }
    }

    public long getRewardCacheHits() { return rewardCacheHits; }
    public long getRewardCacheMisses() { return rewardCacheMisses; }
    public int getCachedRewardStateCount() { return rewardByStateId.size(); }
}
