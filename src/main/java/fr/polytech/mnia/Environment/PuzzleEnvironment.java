package fr.polytech.mnia.Environment;

import java.util.HashMap;
import java.util.Map;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Puzzle8 B reward adapter: cache subscribed successor-state rewards and
 * reject missing/invalid rewards instead of silently assigning zero.
 */
public class PuzzleEnvironment extends ModelBasedEnvironment {
    private final Map<String, Double> rewardBySuccessorId = new HashMap<>();
    private long rewardCacheHits;
    private long rewardCacheMisses;

    public PuzzleEnvironment(String filePath, RewardStrategy rewardStrategy) {
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
                "Puzzle8: B reward subscription missing at successor state " + id, e);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Puzzle8: non-finite reward at state " + id);
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
                throw new IllegalStateException("No embedded B operation return value");
            }
            double value = Double.parseDouble(values.get(0));
            if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite reward");
            return value;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Puzzle8: invalid embedded reward from " + transition.getPrettyName(), e);
        }
    }

    // Not part of the 24-configuration campaign; do not use a contradictory
    // Java-specific reward function when the B definition already exists.
    @Override
    protected double calculateRewardOnTheFly(State state, Transition action, State successor) {
        try {
            double value = Double.parseDouble(successor.eval("reward").toString());
            if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite reward");
            return value;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Puzzle8: unable to evaluate B reward at state " + successor.getId(), e);
        }
    }

    public long getRewardCacheHits() { return rewardCacheHits; }
    public long getRewardCacheMisses() { return rewardCacheMisses; }
    public int getCachedRewardStateCount() { return rewardBySuccessorId.size(); }
}
