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
 * Exhaustive, lockstep equivalence check of the two reachable application
 * transition systems: B valuations, labelled transitions, multiplicities,
 * and the reward assigned to each transition's successor state.
 *
 * Avoids storing the full graph twice; compares outgoing multisets locally
 * and visits each canonical state valuation once.
 */
public final class InterlockingRewardEquivalence {
    private InterlockingRewardEquivalence() {}

    private record StateKey(String active, String positions, String directions,
                            String reserved, String tail, String pending) {}
    private record Edge(String operation, StateKey successor, double reward) {}
    private record Pair(State once, State embedded) {}

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static StateKey valuation(State state, Map<String, StateKey> cache) {
        return cache.computeIfAbsent(state.getId(), unused -> new StateKey(
            state.eval("acttrn").toString(),
            state.eval("trainpos").toString(),
            state.eval("traindir").toString(),
            state.eval("resss").toString(),
            state.eval("restail").toString(),
            state.eval("pending").toString()));
    }

    private static final class Outgoing {
        final Map<Edge, Integer> multiset = new HashMap<>();
        final Map<StateKey, State> successorStates = new HashMap<>();
        int transitions;
    }

    private static Outgoing outgoing(InterlockingEnvironment environment,
                                     State source, Map<String, StateKey> cache) {
        Outgoing result = new Outgoing();
        for (Transition t : source.getOutTransitions()) {
            State destination = t.getDestination();
            StateKey key = valuation(destination, cache);
            double reward = environment.reward(source, t, destination);
            check(Double.isFinite(reward), "Non-finite reward from " + source.getId());
            result.multiset.merge(new Edge(t.getPrettyName(), key, reward), 1, Integer::sum);
            result.successorStates.putIfAbsent(key, destination);
            result.transitions++;
        }
        return result;
    }

    public static void main(String[] args) {
        InterlockingEnvironment once = new InterlockingEnvironment(
            "/interlocking/interlocking.mch", RewardStrategy.ONCEANDFORALL);
        InterlockingEnvironment embedded = new InterlockingEnvironment(
            "/interlocking/interlockingBis.mch", RewardStrategy.EMBEDDED);

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
            "Different initial B valuations");
        pending.add(new Pair(initialOnce, initialEmbedded));
        scheduled.add(initial);

        long transitions = 0;
        long uniqueEdges = 0;
        while (!pending.isEmpty()) {
            Pair pair = pending.removeFirst();
            StateKey a = valuation(pair.once(), onceKeys);
            StateKey b = valuation(pair.embedded(), embeddedKeys);
            check(a.equals(b), "Different paired B valuations: " + a + " != " + b);
            String previousA = onceIds.putIfAbsent(a, pair.once().getId());
            String previousB = embeddedIds.putIfAbsent(b, pair.embedded().getId());
            check(previousA == null || previousA.equals(pair.once().getId()),
                  "One B valuation maps to multiple Once-and-for-all states");
            check(previousB == null || previousB.equals(pair.embedded().getId()),
                  "One B valuation maps to multiple Embedded states");

            Outgoing first = outgoing(once, pair.once(), onceKeys);
            Outgoing second = outgoing(embedded, pair.embedded(), embeddedKeys);
            check(first.multiset.equals(second.multiset),
                "Different operations, successors, or rewards at valuation " + a
                + " (once=" + first.multiset.size() + " embedded=" + second.multiset.size() + ")");
            transitions += first.transitions;
            uniqueEdges += first.multiset.size();
            for (StateKey successor : first.successorStates.keySet()) {
                if (scheduled.add(successor)) {
                    pending.addLast(new Pair(first.successorStates.get(successor),
                                              second.successorStates.get(successor)));
                }
            }
            if (onceIds.size() % 10000 == 0) {
                System.out.printf("INTERLOCKING_PROGRESS states=%d transitions=%d%n",
                    onceIds.size(), transitions);
            }
        }

        check(onceIds.size() == embeddedIds.size(), "State counts differ");
        check(once.getStateIds().size() == onceIds.size(),
              "Once-and-for-all PREPROCESS count disagrees with reachable graph");
        check(embedded.getStateIds().size() == embeddedIds.size(),
              "Embedded PREPROCESS count disagrees with reachable graph");
        check(once.getRewardCacheMisses() == once.getCachedRewardStateCount(),
              "Reward cache misses differ from cache size");
        check(once.getRewardCacheMisses() > 0 && once.getRewardCacheHits() > 0,
              "Reward cache not populated/reused");
        check(once.getRewardCacheHits() + once.getRewardCacheMisses() == transitions,
              "Reward cache lookups disagree with transition count");
        check(once.getCachedRewardStateCount() <= onceIds.size(),
              "More reward successor states than reachable states");

        System.out.printf("INTERLOCKING_EQUIVALENCE PASS states=%d transitions=%d "
                + "uniqueEdges=%d rewardCacheHits=%d rewardCacheMisses=%d cachedStates=%d%n",
            onceIds.size(), transitions, uniqueEdges, once.getRewardCacheHits(),
            once.getRewardCacheMisses(), once.getCachedRewardStateCount());
    }
}
