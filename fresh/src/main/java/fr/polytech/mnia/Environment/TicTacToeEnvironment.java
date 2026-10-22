package fr.polytech.mnia.Environment;

import java.util.HashMap;
import java.util.Map;

import de.prob.statespace.State;

public class TicTacToeEnvironment extends ModelBasedEnvironment {

    // Keyed by ProB state ID: remains valid when State objects are recreated
    // because the ProB Java API evicted an object from its internal LRU cache.
    private final Map<String, Double> rewardByStateId = new HashMap<>();
    private long rewardCacheHits;
    private long rewardCacheMisses;

    public TicTacToeEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy,
              rewardStrategy == RewardStrategy.ONCEANDFORALL ? "reward" : null);
    }

    /**
     * Never invoke State.eval in the ONCEANDFORALL learning path.
     * Missing subscribed results must be reported, never silently replaced by 0.
     */
    @Override
    protected double getRewardFromState(State successor) {
        String stateId = successor.getId();
        Double cached = rewardByStateId.get(stateId);
        if (cached != null) {
            rewardCacheHits++;
            return cached;
        }

        final double reward;
        try {
            // Reads the IEvalElement registered via StateSpace.subscribe;
            // in particular this is NOT successor.eval("reward").
            reward = evalFormulas(successor);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Missing or invalid subscribed reward for TicTacToe state " + stateId
                + ". Check that rewards.def defines reward and subscription succeeded.", e);
        }
        if (!Double.isFinite(reward)) {
            throw new IllegalStateException(
                "Non-finite subscribed reward for TicTacToe state " + stateId);
        }
        rewardByStateId.put(stateId, reward);
        rewardCacheMisses++;
        return reward;
    }

    public long getRewardCacheHits() { return rewardCacheHits; }
    public long getRewardCacheMisses() { return rewardCacheMisses; }
    public int getCachedRewardStateCount() { return rewardByStateId.size(); }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            if ("TRUE".equals(sPrime.eval("win(0)").toString())) {
                return 1.0; // Player 0 wins
            } else if ("TRUE".equals(sPrime.eval("win(1)").toString())) {
                return -1.0; // Player 1 wins
            } else if (sPrime.getOutTransitions().isEmpty()) {
                return 0.0; // Draw
            }
            return -0.25; // Small cost per step
        } catch (Exception e) {
            return 0.0;
        }
    }
}
