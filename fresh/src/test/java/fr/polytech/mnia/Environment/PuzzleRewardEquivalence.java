package fr.polytech.mnia.Environment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Exhaustive lockstep equivalence for Puzzle8 and Puzzle8Bis:
 * initial valuations, reachable valuations, operation-labelled multiset of
 * outgoing edges (including multiplicity), and every successor-state reward.
 * ProB state IDs are never used to match the two graphs.
 */
public final class PuzzleRewardEquivalence {
    private PuzzleRewardEquivalence() {}

    private record StateKey(String board, String zx, String zy) {}
    private record Edge(String operation, StateKey successor, double reward) {}
    private record Pair(State once, State embedded) {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static StateKey valuation(State state, Map<String, StateKey> cache) {
        return cache.computeIfAbsent(state.getId(), unused -> new StateKey(
            state.eval("board").toString(),
            state.eval("zx").toString(),
            state.eval("zy").toString()));
    }

    private static final class Outgoing {
        final Map<Edge, Integer> multiset = new HashMap<>();
        final Map<StateKey, State> successorStates = new HashMap<>();
        long transitions;
    }

    private static Outgoing outgoing(PuzzleEnvironment env, State source,
                                     Map<String, StateKey> cache) {
        Outgoing result = new Outgoing();
        for (Transition transition : source.getOutTransitions()) {
            State destination = transition.getDestination();
            StateKey key = valuation(destination, cache);
            double reward = env.reward(source, transition, destination);
            check(Double.isFinite(reward), "Non-finite reward at " + source.getId());
            result.multiset.merge(
                new Edge(transition.getPrettyName(), key, reward), 1, Integer::sum);
            State old = result.successorStates.putIfAbsent(key, destination);
            check(old == null || old.getId().equals(destination.getId()),
                "Two successor IDs for same board valuation");
            result.transitions++;
        }
        return result;
    }

    public static void main(String[] args) {
        PuzzleEnvironment once = new PuzzleEnvironment(
            "/puzzle/Puzzle8.mch", RewardStrategy.ONCEANDFORALL);
        PuzzleEnvironment embedded = new PuzzleEnvironment(
            "/puzzle/Puzzle8Bis.mch", RewardStrategy.EMBEDDED);

        once.explore(ExplorationStrategy.PREPROCESS);
        embedded.explore(ExplorationStrategy.PREPROCESS);

        Map<String, StateKey> onceKeys = new HashMap<>();
        Map<String, StateKey> embeddedKeys = new HashMap<>();
        Map<StateKey, String> onceIds = new HashMap<>();
        Map<StateKey, String> embeddedIds = new HashMap<>();
        Set<StateKey> scheduled = new HashSet<>();
        ArrayDeque<Pair> pending = new ArrayDeque<>();

        State initialOnce = once.gState();
        State initialEmbedded = embedded.gState();
        StateKey initial = valuation(initialOnce, onceKeys);
        check(initial.equals(valuation(initialEmbedded, embeddedKeys)),
            "Different initial Puzzle8 B valuations");
        scheduled.add(initial);
        pending.addLast(new Pair(initialOnce, initialEmbedded));

        long transitions = 0;
        long uniqueEdges = 0;
        while (!pending.isEmpty()) {
            Pair pair = pending.removeFirst();
            StateKey a = valuation(pair.once(), onceKeys);
            StateKey b = valuation(pair.embedded(), embeddedKeys);
            if (!a.equals(b)) {
                throw new IllegalStateException("Different B valuations: " + a + " versus " + b);
            }
            String previousA = onceIds.putIfAbsent(a, pair.once().getId());
            String previousB = embeddedIds.putIfAbsent(b, pair.embedded().getId());
            check(previousA == null || previousA.equals(pair.once().getId()),
                  "One Once-and-for-all board valuation maps to multiple state IDs");
            check(previousB == null || previousB.equals(pair.embedded().getId()),
                  "One Embedded board valuation maps to multiple state IDs");

            Outgoing first = outgoing(once, pair.once(), onceKeys);
            Outgoing second = outgoing(embedded, pair.embedded(), embeddedKeys);
            if (!first.multiset.equals(second.multiset)) {
                throw new IllegalStateException(
                    "Different outgoing operations, successor boards, or rewards at " + a
                    + " (once=" + first.multiset + "; embedded=" + second.multiset + ")");
            }
            transitions += first.transitions;
            uniqueEdges += first.multiset.size();
            for (StateKey successor : first.successorStates.keySet()) {
                if (scheduled.add(successor)) {
                    pending.addLast(new Pair(first.successorStates.get(successor),
                                             second.successorStates.get(successor)));
                }
            }
            if (onceIds.size() % 10000 == 0) {
                System.out.printf("PUZZLE_PROGRESS states=%d transitions=%d%n",
                    onceIds.size(), transitions);
            }
        }

        check(!onceIds.isEmpty() && transitions > 0, "Empty Puzzle8 graph");
        check(onceIds.size() == embeddedIds.size(), "Different graph state counts");
        check(once.getStateIds().size() == onceIds.size(),
              "PREPROCESS Once-and-for-all count differs from traversed graph");
        check(embedded.getStateIds().size() == embeddedIds.size(),
              "PREPROCESS Embedded count differs from traversed graph");
        check(once.getRewardCacheMisses() == once.getCachedRewardStateCount(),
              "Cache misses differ from cached reward-state count");
        check(once.getRewardCacheMisses() > 0 && once.getRewardCacheHits() > 0,
              "Reward cache unused");
        check(once.getRewardCacheHits() + once.getRewardCacheMisses() == transitions,
              "Reward-cache total does not match examined transitions");
        check(once.getCachedRewardStateCount() <= onceIds.size(),
              "Cache contains more states than application graph");

        System.out.printf(
            "PUZZLE_EQUIVALENCE PASS states=%d transitions=%d uniqueEdges=%d "
              + "rewardCacheHits=%d rewardCacheMisses=%d cachedStates=%d%n",
            onceIds.size(), transitions, uniqueEdges, once.getRewardCacheHits(),
            once.getRewardCacheMisses(), once.getCachedRewardStateCount());
    }
}
