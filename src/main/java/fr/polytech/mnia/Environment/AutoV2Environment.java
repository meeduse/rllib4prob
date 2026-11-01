package fr.polytech.mnia.Environment;

import java.util.HashMap;
import java.util.Map;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * AutoV2 reward strategies, with the B expression subscribed prior to
 * INITIALISATION and cached strictly by ProB successor state identifier.
 */
public class AutoV2Environment extends ModelBasedEnvironment {
    private final Map<String, Double> rewardByStateId = new HashMap<>();
    private long rewardCacheHits;
    private long rewardCacheMisses;

    public AutoV2Environment(String filePath, RewardStrategy rewardStrategy) {
        // Requires the subscription-enabled ModelBasedEnvironment already
        // installed for Frozen Lake / Taxi Driver.
        super(filePath, rewardStrategy,
              rewardStrategy == RewardStrategy.ONCEANDFORALL ? "reward" : null);
    }

    @Override
    protected double getRewardFromState(State successor) {
        final String id = successor.getId();
        Double cached = rewardByStateId.get(id);
        if (cached != null) {
            rewardCacheHits++;
            return cached;
        }
        final double value;
        try {
            value = evalFormulas(successor);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "AutoV2: subscribed B reward missing or invalid at state " + id, ex);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("AutoV2: non-finite subscribed reward at state " + id);
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
                throw new IllegalStateException("B operation did not return a reward");
            }
            double value = Double.parseDouble(values.get(0));
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Non-finite embedded reward");
            }
            return value;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "AutoV2: invalid embedded reward in operation "
                + transition.getPrettyName(), ex);
        }
    }

    // ONTHEFLY is deliberately excluded from these offline campaigns.
    @Override
    protected double calculateRewardOnTheFly(State s, Transition a, State successor) {
        try {
            double value = Double.parseDouble(successor.eval("reward").toString());
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Non-finite B reward");
            }
            return value;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "AutoV2: cannot evaluate B reward at state " + successor.getId(), ex);
        }
    }

    public long getRewardCacheHits() { return rewardCacheHits; }
    public long getRewardCacheMisses() { return rewardCacheMisses; }
    public int getCachedRewardStateCount() { return rewardByStateId.size(); }
}
