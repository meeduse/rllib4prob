package fr.polytech.mnia.Environment;

import java.util.HashMap;
import java.util.Map;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Taxi Driver reward implementations.
 * ONCEANDFORALL: subscribed B expression "reward", cached per ProB state ID.
 * EMBEDDED: return value produced by the B operation.
 * Never silently substitute zero for a missing reward.
 */
public class TaxiDriverEnvironment extends ModelBasedEnvironment {

    private final Map<String, Double> rewardByStateId = new HashMap<>();
    private long rewardCacheHits;
    private long rewardCacheMisses;

    public TaxiDriverEnvironment(String filePath, RewardStrategy rewardStrategy) {
        // ModelBasedEnvironment subscribes BEFORE executing B initialization.
        super(filePath, rewardStrategy,
              rewardStrategy == RewardStrategy.ONCEANDFORALL ? "reward" : null);
    }

    @Override
    protected double getRewardFromState(State successor) {
        String id = successor.getId();
        Double cached = rewardByStateId.get(id);
        if (cached != null) {
            rewardCacheHits++;
            return cached;
        }
        final double value;
        try {
            // Reads the subscribed IEvalElement, never calls State.eval("reward").
            value = evalFormulas(successor);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Taxi Driver: subscribed reward missing/invalid at state " + id, ex);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalStateException(
                "Taxi Driver: non-finite subscribed reward at state " + id);
        }
        rewardByStateId.put(id, value);
        rewardCacheMisses++;
        return value;
    }

    @Override
    protected double getRewardFromTransition(Transition transition) {
        try {
            var values = transition.getReturnValues();
            if (values == null || values.isEmpty()) {
                throw new IllegalStateException("B operation returned no reward");
            }
            double value = Double.parseDouble(values.get(0));
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Non-finite embedded reward: " + value);
            }
            return value;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Taxi Driver: missing/invalid embedded reward for transition "
                + transition.getPrettyName(), ex);
        }
    }

    // On-the-fly is not included in the Taxi Driver benchmark campaign.
    // If invoked explicitly, it evaluates the same B reward expression on demand.
    @Override
    protected double calculateRewardOnTheFly(State s, Transition a, State successor) {
        try {
            double value = Double.parseDouble(successor.eval("reward").toString());
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Non-finite on-the-fly reward");
            }
            return value;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Taxi Driver: cannot evaluate on-the-fly reward for state "
                + successor.getId(), ex);
        }
    }

    public long getRewardCacheHits() { return rewardCacheHits; }
    public long getRewardCacheMisses() { return rewardCacheMisses; }
    public int getCachedRewardStateCount() { return rewardByStateId.size(); }
}
