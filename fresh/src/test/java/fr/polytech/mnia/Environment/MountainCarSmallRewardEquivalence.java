package fr.polytech.mnia.Environment;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/** Compare every reachable state, operation edge, and post-transition reward. */
public final class MountainCarSmallRewardEquivalence {
    private MountainCarSmallRewardEquivalence() {}

    private record Valuation(int position, int velocity, int done, int lastAction) {
        double reward() { return done == 1 ? 10000.0 : -0.1; }
    }
    private record Edge(Valuation from, String operation, Valuation to, double reward) {}

    private static final class Snapshot {
        final Set<Valuation> states = new HashSet<>();
        final Set<Valuation> successors = new HashSet<>();
        final Set<Integer> reachableIds = new HashSet<>();
        final Map<Edge,Integer> edges = new HashMap<>();
        int transitions;
    }

    private static void check(boolean condition, String explanation) {
        if (!condition) throw new IllegalStateException(explanation);
    }
    private static int value(State state, String expression) {
        return Integer.parseInt(state.eval(expression).toString());
    }
    private static Valuation valuation(State state, Map<String, Valuation> cache) {
        return cache.computeIfAbsent(state.getId(), id -> new Valuation(
            value(state, "position"), value(state, "velocity"),
            value(state, "done"), value(state, "lastAction")));
    }

    private static Snapshot snapshot(MountainCarEnvironment environment) {
        environment.explore(ExplorationStrategy.PREPROCESS);
        Snapshot data = new Snapshot();
        Map<String, Valuation> cache = new HashMap<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(environment.gState());
        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            int id = Integer.parseInt(state.getId());
            if (!data.reachableIds.add(id)) continue;
            Valuation from = valuation(state, cache);
            check(data.states.add(from), "Duplicate B state valuation at different IDs: " + from);
            check(from.position >= -120 && from.position <= 60
               && from.velocity >= -14 && from.velocity <= 14
               && (from.done == 0 || from.done == 1)
               && from.lastAction >= 0 && from.lastAction <= 3
               && (from.done != 1 || from.position >= 50),
                "Invariant violation at " + from);
            for (Transition edge : state.getOutTransitions()) {
                State destination = edge.getDestination();
                Valuation to = valuation(destination, cache);
                data.successors.add(to);
                double observed = environment.reward(state, edge, destination);
                check(Double.isFinite(observed), "Non-finite reward");
                check(Math.abs(observed - to.reward()) <= 1e-9,
                    "Incorrect successor reward from " + from + " via " +
                    edge.getPrettyName() + " to " + to + ": " + observed);
                Edge identity = new Edge(from, edge.getPrettyName(), to, observed);
                data.edges.merge(identity, 1, Integer::sum);
                data.transitions++;
                queue.addLast(destination);
            }
        }
        check(new HashSet<>(environment.getStateIds()).equals(data.reachableIds),
            "PREPROCESS application state IDs differ from graph traversal: " +
            "preprocess=" + environment.getStateIds().size() +
            ", reachable=" + data.reachableIds.size());
        return data;
    }

    public static void main(String[] args) {
        MountainCarEnvironment once = new MountainCarEnvironment(
            "/Mountain_Car/MountainCarSmall.mch", RewardStrategy.ONCEANDFORALL);
        MountainCarEnvironment embedded = new MountainCarEnvironment(
            "/Mountain_Car/MountainCarSmallBis.mch", RewardStrategy.EMBEDDED);
        Snapshot first = snapshot(once);
        Snapshot second = snapshot(embedded);
        check(!first.states.isEmpty() && first.transitions > 0, "Empty application graph");
        check(first.states.equals(second.states), "Application state valuations differ");
        check(first.edges.equals(second.edges),
            "Application operations, edges, multiplicities or rewards differ");
        check(first.transitions == second.transitions, "Transition counts differ");
        check(once.getRewardCacheMisses() == once.getCachedRewardStateCount(),
            "Once-and-for-all cache misses differ from cache size");
        check(once.getCachedRewardStateCount() == first.successors.size(),
            "Cache does not cover distinct application successor states");
        check(once.getRewardCacheHits() > 0, "Reward cache was never used");
        System.out.printf(
            "MOUNTAINCAR_SMALL_EQUIVALENCE PASS states=%d transitions=%d uniqueEdges=%d " +
            "rewardCacheHits=%d rewardCacheMisses=%d cachedStates=%d%n",
            first.states.size(), first.transitions, first.edges.size(),
            once.getRewardCacheHits(), once.getRewardCacheMisses(),
            once.getCachedRewardStateCount());
    }
}
