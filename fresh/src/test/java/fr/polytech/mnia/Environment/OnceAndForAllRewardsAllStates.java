package fr.polytech.mnia.Environment;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Integration validation against a real ProB instance, without running IVI.
 * For this Tic-Tac-Toe benchmark the reward depends on the successor state only.
 * We compare the subscribed state reward against the current on-the-fly
 * implementation on every reachable state, including the initial state.
 * A second subscribed read of the same state checks the Java-side cache.
 */
public final class OnceAndForAllRewardsAllStates {

    private OnceAndForAllRewardsAllStates() { }

    public static void main(String[] args) {
        int expectedStates = args.length == 0 ? -1 : Integer.parseInt(args[0]);
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: OnceAndForAllRewardsAllStates [expectedStates]");
        }

        TicTacToeEnvironment env = new TicTacToeEnvironment(
                "/TicTacToe/tictac.mch", RewardStrategy.ONCEANDFORALL);

        Queue<State> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        State initial = env.gState();
        pending.add(initial);
        seen.add(initial.getId());

        long transitions = 0;
        int compared = 0;
        long start = System.nanoTime();
        while (!pending.isEmpty()) {
            State state = pending.remove();
            String id = state.getId();

            // Both implementations depend on sPrime only in this benchmark.
            // The transition argument is unused by these two implementations.
            double subscribed = env.reward(state, null, state);
            double onTheFly = env.calculateRewardOnTheFly(state, null, state);
            double fromCache = env.reward(state, null, state);

            if (!Double.isFinite(subscribed) || !Double.isFinite(onTheFly)
                    || Double.compare(subscribed, fromCache) != 0
                    || Math.abs(subscribed - onTheFly) > 1e-12) {
                throw new AssertionError("REWARD_MISMATCH state=" + id
                        + " subscribed=" + subscribed
                        + " onthefly=" + onTheFly
                        + " cached=" + fromCache);
            }

            compared++;
            for (Transition t : state.getOutTransitions()) {
                transitions++;
                State next = t.getDestination();
                if (seen.add(next.getId())) {
                    pending.add(next);
                }
            }
            if (compared % 250 == 0) {
                System.out.printf(Locale.ROOT,
                        "REWARD_PROGRESS checked=%d discovered=%d queued=%d elapsedSeconds=%.2f%n",
                        compared, seen.size(), pending.size(),
                        (System.nanoTime() - start) / 1_000_000_000.0);
            }
        }

        long hits = env.getRewardCacheHits();
        long misses = env.getRewardCacheMisses();
        int cached = env.getCachedRewardStateCount();
        if (expectedStates >= 0 && compared != expectedStates) {
            throw new AssertionError("REWARD_INCOMPLETE expectedStates=" + expectedStates
                    + " checked=" + compared);
        }
        if (seen.size() != compared || hits != compared || misses != compared || cached != compared) {
            throw new AssertionError("REWARD_CACHE_INVALID checked=" + compared
                    + " unique=" + seen.size() + " hits=" + hits
                    + " misses=" + misses + " cachedStates=" + cached);
        }

        System.out.printf(Locale.ROOT,
                "REWARD_ALL_STATES PASS checked=%d transitions=%d mismatches=0 "
                + "cacheHits=%d cacheMisses=%d cachedStates=%d "
                + "elapsedSeconds=%.3f%n",
                compared, transitions, hits, misses, cached,
                (System.nanoTime() - start) / 1_000_000_000.0);
    }
}
