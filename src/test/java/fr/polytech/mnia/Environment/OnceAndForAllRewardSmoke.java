package fr.polytech.mnia.Environment;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/** A small, real-ProB integration check; does not run model checking or IVI. */
public final class OnceAndForAllRewardSmoke {

    private OnceAndForAllRewardSmoke() {}

    public static void main(String[] args) {
        TicTacToeEnvironment environment = new TicTacToeEnvironment(
            "/TicTacToe/tictac.mch", RewardStrategy.ONCEANDFORALL);
        State initial = environment.gState();
        if (initial.getOutTransitions().isEmpty()) {
            throw new IllegalStateException("TicTacToe initial state has no moves");
        }
        Transition move = initial.getOutTransitions().get(0);
        State successor = move.getDestination();

        double onceFirst = environment.reward(initial, move, successor);
        double onceSecond = environment.reward(initial, move, successor);
        double onTheFly = environment.calculateRewardOnTheFly(initial, move, successor);

        if (Math.abs(onceFirst - onTheFly) > 1e-12
                || Math.abs(onceSecond - onTheFly) > 1e-12) {
            throw new IllegalStateException(
                "Reward mismatch: subscribed=" + onceFirst + ", onTheFly=" + onTheFly);
        }
        if (environment.getRewardCacheMisses() != 1
                || environment.getRewardCacheHits() != 1
                || environment.getCachedRewardStateCount() != 1) {
            throw new IllegalStateException(
                "Unexpected cache accounting: hits=" + environment.getRewardCacheHits()
                + " misses=" + environment.getRewardCacheMisses());
        }

        System.out.printf(java.util.Locale.ROOT,
            "REWARD_SMOKE PASS state=%s subscribed=%.4f onthefly=%.4f "
            + "cacheHits=%d cacheMisses=%d%n",
            successor.getId(), onceFirst, onTheFly,
            environment.getRewardCacheHits(), environment.getRewardCacheMisses());
    }
}
