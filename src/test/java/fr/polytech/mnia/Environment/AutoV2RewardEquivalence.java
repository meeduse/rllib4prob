package fr.polytech.mnia.Environment;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/** Test state/transition/reward equivalence of the AutoV2 B machines. */
public final class AutoV2RewardEquivalence {
    private record Variables(int distance, int egoSpeed, int frontSpeed) {
        double reward() {
            if (distance == 0) return -10000.0;
            return 1000.0 - 30.0 * Math.abs(distance - 30)
                          - 20.0 * Math.abs(egoSpeed - 30)
                          - (distance < 20 ? 2000.0 : 0.0);
        }
    }
    private record Edge(Variables source, String operation, Variables destination, double reward) {}
    private static final class Snapshot {
        final Set<Variables> states = new HashSet<>();
        final Set<Variables> rewardSuccessors = new HashSet<>();
        final Set<Integer> reachableIds = new HashSet<>();
        final Map<Edge, Integer> edges = new HashMap<>();
        int transitions;
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
    private static int evalInt(State s, String formula) {
        return Integer.parseInt(s.eval(formula).toString());
    }
    private static Variables value(State s, Map<String, Variables> cache) {
        return cache.computeIfAbsent(s.getId(), id ->
            new Variables(evalInt(s, "distance_front"),
                          evalInt(s, "ego_speed"),
                          evalInt(s, "front_speed")));
    }
    private static Snapshot read(AutoV2Environment env) {
        env.explore(ExplorationStrategy.PREPROCESS);
        Snapshot result = new Snapshot();
        Map<String, Variables> cache = new HashMap<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(env.gState());
        while (!queue.isEmpty()) {
            State s = queue.removeFirst();
            int id = Integer.parseInt(s.getId());
            if (!result.reachableIds.add(id)) continue;
            Variables source = value(s, cache);
            check(result.states.add(source), "Duplicate B valuation for distinct state IDs: " + source);
            check(source.distance() >= 0 && source.distance() <= 95
               && source.egoSpeed() >= 0 && source.egoSpeed() <= 50
               && source.frontSpeed() >= 0 && source.frontSpeed() <= 50,
               "B invariant violated at " + source);
            for (Transition t : s.getOutTransitions()) {
                State destinationState = t.getDestination();
                Variables destination = value(destinationState, cache);
                result.rewardSuccessors.add(destination);
                double actual = env.reward(s, t, destinationState);
                double expected = destination.reward();
                check(Double.compare(actual, expected) == 0,
                    "Incorrect reward from " + source + " via " + t.getPrettyName()
                    + " to " + destination + ": actual=" + actual + ", expected=" + expected);
                check(Double.isFinite(actual), "Non-finite reward");
                Edge edge = new Edge(source, t.getPrettyName(), destination, actual);
                result.edges.merge(edge, 1, Integer::sum);
                result.transitions++;
                queue.addLast(destinationState);
            }
        }
        Set<Integer> supplied = new HashSet<>(env.getStateIds());
        check(supplied.equals(result.reachableIds),
            "PREPROCESS differs from reachable application state IDs: PREPROCESS="
            + supplied.size() + ", BFS=" + result.reachableIds.size());
        return result;
    }
    public static void main(String[] args) {
        AutoV2Environment once = new AutoV2Environment(
            "/autoV2/HighwayPerception_Q.mch", RewardStrategy.ONCEANDFORALL);
        AutoV2Environment embedded = new AutoV2Environment(
            "/autoV2/HighwayPerception_QBis.mch", RewardStrategy.EMBEDDED);
        Snapshot a = read(once);
        Snapshot b = read(embedded);
        check(!a.states.isEmpty() && a.transitions > 0, "No application states/transitions");
        check(a.states.equals(b.states), "Reachable valuations differ between B machines");
        check(a.edges.equals(b.edges), "Operations, transitions, multiplicities or rewards differ");
        check(a.transitions == b.transitions, "Different transition counts");
        check(once.getRewardCacheMisses() == once.getCachedRewardStateCount(),
            "Cache misses differ from number of cached successor states");
        check(once.getCachedRewardStateCount() == a.rewardSuccessors.size(),
            "The number of cached rewards differs from the number of distinct successor states");
        check(once.getRewardCacheHits() > 0, "Reward cache never hit");
        System.out.printf("AUTO_V2_EQUIVALENCE PASS states=%d transitions=%d "
                + "uniqueEdges=%d rewardCacheHits=%d rewardCacheMisses=%d cachedStates=%d%n",
            a.states.size(), a.transitions, a.edges.size(),
            once.getRewardCacheHits(), once.getRewardCacheMisses(),
            once.getCachedRewardStateCount());
    }
}
