package fr.polytech.mnia.Environment;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Independently checks application-level reachability and transition/reward
 * equivalence for the Once-and-for-all and Embedded B specifications.
 * The traversal starts after SETUP_CONSTANTS and INITIALISATION.
 * Usage: java ...TaxiDriverRewardEquivalence [5|10|30]
 */
public final class TaxiDriverRewardEquivalence {

    private static final String[] VARIABLES = {
        "taxiX", "taxiY", "passengerLoc", "destination",
        "passengerInTaxi", "delivered"
    };

    private static final class EdgeData {
        final double reward;
        int multiplicity;
        EdgeData(double reward) { this.reward = reward; }
    }

    private static final class Snapshot {
        final Set<String> valuations = new HashSet<>();
        final Set<Integer> reachableIds = new HashSet<>();
        final Map<String, EdgeData> edges = new HashMap<>();
        int transitions;
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }

    private static String stateKey(State s) {
        StringBuilder b = new StringBuilder();
        for (String variable : VARIABLES) {
            b.append(variable).append('=').append(s.eval(variable)).append(';');
        }
        return b.toString();
    }

    private static Snapshot snapshot(TaxiDriverEnvironment env) {
        env.explore(ExplorationStrategy.PREPROCESS);
        Snapshot result = new Snapshot();
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(env.gState());
        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            int id = Integer.parseInt(state.getId());
            if (!result.reachableIds.add(id)) continue;
            String source = stateKey(state);
            check(result.valuations.add(source),
                  "Different state IDs correspond to identical B variable valuations: " + source);

            for (Transition transition : state.getOutTransitions()) {
                String op = transition.getPrettyName();
                check(!"INITIALISATION".equalsIgnoreCase(op)
                      && !"SETUP_CONSTANTS".equalsIgnoreCase(op),
                      "Administrative ProB transition encountered in game state: " + op);
                State successor = transition.getDestination();
                String target = stateKey(successor);
                double value = env.reward(state, transition, successor);
                check(Double.isFinite(value), "Non-finite reward: " + source + " -> " + target);

                // Also verify both strategies agree with the actual reward definition:
                // reward == IF delivered=1 THEN 1000 ELSE -1 END.
                int delivered = Integer.parseInt(successor.eval("delivered").toString());
                check(delivered == 0 || delivered == 1, "Invalid delivered flag");
                double expectedReward = delivered == 1 ? 1000.0 : -1.0;
                check(Double.compare(value, expectedReward) == 0,
                      "Reward violates rewards.def on " + source + " -> " + target
                      + ": actual=" + value + " expected=" + expectedReward);

                String edgeKey = source + " -> " + target;
                EdgeData edge = result.edges.get(edgeKey);
                if (edge == null) {
                    edge = new EdgeData(value);
                    result.edges.put(edgeKey, edge);
                }
                check(Double.compare(edge.reward, value) == 0,
                      "Different rewards for same state pair: " + edgeKey);
                edge.multiplicity++;
                result.transitions++;
                queue.addLast(successor);
            }
        }

        Set<Integer> supplied = new HashSet<>(env.getStateIds());
        if (!supplied.equals(result.reachableIds)) {
            Set<Integer> missing = new HashSet<>(result.reachableIds);
            missing.removeAll(supplied);
            Set<Integer> extra = new HashSet<>(supplied);
            extra.removeAll(result.reachableIds);
            throw new IllegalStateException(
                "PREPROCESS state-ID mismatch: missing=" + missing + " extra=" + extra);
        }
        return result;
    }

    private static void compare(Snapshot once, Snapshot embedded) {
        check(once.valuations.equals(embedded.valuations),
              "The two B machines have different reachable application states");
        check(once.transitions == embedded.transitions,
              "Different numbers of application transitions");
        check(once.edges.keySet().equals(embedded.edges.keySet()),
              "The two B machines have different application transition graphs");
        for (String edge : once.edges.keySet()) {
            EdgeData a = once.edges.get(edge), b = embedded.edges.get(edge);
            check(a.multiplicity == b.multiplicity,
                  "Transition multiplicities differ: " + edge);
            check(Double.compare(a.reward, b.reward) == 0,
                  "Reward mismatch: " + edge + " Once=" + a.reward + " Embedded=" + b.reward);
        }
    }

    private static void run(int size) {
        String stem = "/taxi_driver/SafeTaxiDriver_" + size + "_" + size;
        TaxiDriverEnvironment once = new TaxiDriverEnvironment(
            stem + ".mch", RewardStrategy.ONCEANDFORALL);
        TaxiDriverEnvironment embedded = new TaxiDriverEnvironment(
            stem + "Bis.mch", RewardStrategy.EMBEDDED);
        Snapshot a = snapshot(once);
        Snapshot b = snapshot(embedded);
        check(!a.valuations.isEmpty() && a.transitions > 0,
              "No reachable application transitions");
        compare(a, b);
        check(once.getRewardCacheMisses() > 0, "No subscribed reward cache misses");
        check(once.getRewardCacheHits() > 0, "No repeated reward requests hit the cache");
        check(once.getCachedRewardStateCount() == once.getRewardCacheMisses(),
              "Cache entry count does not match first-time lookups");
        System.out.printf(
            "TAXI_DRIVER_%d_EQUIVALENCE PASS states=%d transitions=%d uniqueStatePairs=%d "
            + "rewardCacheHits=%d rewardCacheMisses=%d cachedStates=%d%n",
            size, a.valuations.size(), a.transitions, a.edges.size(),
            once.getRewardCacheHits(), once.getRewardCacheMisses(),
            once.getCachedRewardStateCount());
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            run(5);
            run(10);
            run(30);
        } else {
            check(args.length == 1, "Usage: TaxiDriverRewardEquivalence [5|10|30]");
            int size = Integer.parseInt(args[0]);
            check(size == 5 || size == 10 || size == 30,
                  "Grid size must be 5, 10 or 30");
            run(size);
        }
    }
}
