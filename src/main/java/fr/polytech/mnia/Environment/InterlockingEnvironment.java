package fr.polytech.mnia.Environment;

import java.util.HashMap;
import java.util.Map;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Interlocking reward adapter for the two equivalent B machines.
 * The ONCEANDFORALL B formula is subscribed before machine initialisation;
 * lookups are cached per successor state. No silent reward fallback is allowed.
 */
public class InterlockingEnvironment extends ModelBasedEnvironment {
    private final Map<String, Double> rewardBySuccessorId = new HashMap<>();
    private long rewardCacheHits;
    private long rewardCacheMisses;

    public InterlockingEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy,
              rewardStrategy == RewardStrategy.ONCEANDFORALL ? "reward" : null);
    }

    @Override
    protected double getRewardFromState(State successor) {
        String id = successor.getId();
        Double cached = rewardBySuccessorId.get(id);
        if (cached != null) {
            rewardCacheHits++;
            return cached;
        }
        final double value;
        try {
            value = evalFormulas(successor);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Interlocking: B reward subscription missing at successor state " + id, e);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Interlocking: non-finite reward at state " + id);
        }
        rewardBySuccessorId.put(id, value);
        rewardCacheMisses++;
        return value;
    }

    @Override
    protected double getRewardFromTransition(Transition transition) {
        try {
            var values = transition.getReturnValues();
            if (values == null || values.isEmpty()) {
                throw new IllegalStateException("No reward returned from B operation");
            }
            double value = Double.parseDouble(values.get(0));
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Non-finite embedded reward");
            }
            return value;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Interlocking: invalid embedded reward in " + transition.getPrettyName(), e);
        }
    }

    // Not used by the six-algorithm offline campaign. Kept consistent with the
    // *actual* B model reward (not the historical, unimported rewards.def).
    @Override
    protected double calculateRewardOnTheFly(State s, Transition action, State successor) {
        try {
            double value = Double.parseDouble(successor.eval("reward").toString());
            if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite reward");
            return value;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Interlocking: unable to evaluate B reward at " + successor.getId(), e);
        }
    }

    public long getRewardCacheHits() { return rewardCacheHits; }
    public long getRewardCacheMisses() { return rewardCacheMisses; }
    public int getCachedRewardStateCount() { return rewardBySuccessorId.size(); }
}
