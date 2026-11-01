package fr.polytech.mnia.Environment;

import java.util.HashMap;
import java.util.Map;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * MountainCar B reward adapter.  Once-and-for-all subscribes to the B formula
 * before INITIALISATION, then caches rewards by successor state ID.
 * Invalid reward values are fatal: no implicit fallback to zero.
 */
public class MountainCarEnvironment extends ModelBasedEnvironment {
    private final Map<String, Double> rewardsBySuccessorId = new HashMap<>();
    private long rewardCacheHits;
    private long rewardCacheMisses;

    public MountainCarEnvironment(String filePath, RewardStrategy strategy) {
        // Requires the subscription-enabled constructor installed during
        // the earlier Frozen Lake / Taxi Driver experiments.
        super(filePath, strategy,
              strategy == RewardStrategy.ONCEANDFORALL ? "reward" : null);
    }

    @Override
    protected double getRewardFromState(State successor) {
        String id = successor.getId();
        Double cached = rewardsBySuccessorId.get(id);
        if (cached != null) {
            rewardCacheHits++;
            return cached;
        }
        final double value;
        try {
            value = evalFormulas(successor);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "MountainCar: subscribed B reward missing at successor state " + id, e);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("MountainCar: non-finite reward at " + id);
        }
        rewardsBySuccessorId.put(id, value);
        rewardCacheMisses++;
        return value;
    }

    @Override
    protected double getRewardFromTransition(Transition transition) {
        try {
            var values = transition.getReturnValues();
            if (values == null || values.isEmpty()) {
                throw new IllegalStateException("No reward returned by B operation");
            }
            double value = Double.parseDouble(values.get(0));
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Non-finite B reward");
            }
            return value;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "MountainCar: invalid Embedded reward for " + transition.getPrettyName(), e);
        }
    }

    // ONTHEFLY is deliberately outside our six-algorithm benchmark design.
    @Override
    protected double calculateRewardOnTheFly(State s, Transition a, State successor) {
        try {
            double value = Double.parseDouble(successor.eval("reward").toString());
            if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite reward");
            return value;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "MountainCar: unable to evaluate reward at " + successor.getId(), e);
        }
    }

    public long getRewardCacheHits() { return rewardCacheHits; }
    public long getRewardCacheMisses() { return rewardCacheMisses; }
    public int getCachedRewardStateCount() { return rewardsBySuccessorId.size(); }
}
