package fr.polytech.mnia.Environment;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Compares all reachable game states, real B-operation transitions, and rewards
 * between the Once-and-for-all and Embedded Frozen Lake 10x10 environments.
 *
 * The traversal starts from the already initialised state (gState()), NOT from
 * ProB's synthetic root or from a guessed numeric interval of state IDs.
 */
public final class FrozenLake10RewardEquivalence {

    private static final String[] VARIABLES = {
        "posX", "posY", "reachedGoal", "fellInHole", "lastAction"
    };

    private static final class EdgeData {
        int multiplicity;
        final double reward;
        EdgeData(double reward) { this.reward = reward; }
    }

    private static final class Snapshot {
        final Map<String, Integer> states = new HashMap<>();
        final Map<String, EdgeData> edges = new HashMap<>();
        final Set<Integer> reachableIds = new HashSet<>();
        int transitions;
    }

    private static String stateKey(State state) {
        StringBuilder b = new StringBuilder();
        for (String variable : VARIABLES) {
            b.append(variable).append('=').append(state.eval(variable)).append(';');
        }
        return b.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static Snapshot snapshot(FrozenLakeEnvironment environment) {
        environment.explore(ExplorationStrategy.PREPROCESS);
        Snapshot result = new Snapshot();
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(environment.gState()); // Actual state AFTER INITIALISATION.

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            int stateId = Integer.parseInt(state.getId());
            if (!result.reachableIds.add(stateId)) continue;

            String source = stateKey(state);
            result.states.merge(source, 1, Integer::sum);

            for (Transition transition : state.getOutTransitions()) {
                String operation = transition.getPrettyName();
                if ("INITIALISATION".equalsIgnoreCase(operation)
                        || "SETUP_CONSTANTS".equalsIgnoreCase(operation)) {
                    throw new IllegalStateException(
                        "Unexpected ProB administrative transition from an initialised game state: "
                        + operation + " (state " + stateId + ")");
                }

                State successor = transition.getDestination();
                String target = stateKey(successor);
                double reward = environment.reward(state, transition, successor);
                check(Double.isFinite(reward),
                      "Non-finite reward for " + source + " -> " + target);

                // The Frozen Lake reward depends on the successor state.
                // Compare source/target pairs with multiplicity, even when the
                // Embedded B operation has a different return-parameter syntax.
                String edge = source + " -> " + target;
                EdgeData data = result.edges.computeIfAbsent(edge, k -> new EdgeData(reward));
                check(Double.compare(data.reward, reward) == 0,
                      "Inconsistent rewards for the same state pair: " + edge);
                data.multiplicity++;
                result.transitions++;
                queue.addLast(successor);
            }
        }

        check(result.states.size() == result.reachableIds.size(),
              "Distinct ProB state IDs have identical game-state variable valuations");

        // Crucial for experimental validity: the VI/PI/... planning algorithms
        // use getStateIds(), so ensure PREPROCESS did not include the synthetic
        // root or omit a valid game state due to an off-by-one numeric range.
        Set<Integer> supplied = new HashSet<>(environment.getStateIds());
        if (!supplied.equals(result.reachableIds)) {
            Set<Integer> missing = new HashSet<>(result.reachableIds);
            missing.removeAll(supplied);
            Set<Integer> unexpected = new HashSet<>(supplied);
            unexpected.removeAll(result.reachableIds);
            throw new IllegalStateException(
                "PREPROCESS state-ID mismatch: missing real game IDs=" + missing
                + "; extra IDs=" + unexpected
                + ". Inspect Environment.modelCheck() before running VI.");
        }
        return result;
    }

    public static void main(String[] args) {
        FrozenLakeEnvironment once = new FrozenLakeEnvironment(
            "/frozen_lake/FrozenLakeGrid_10_10.mch", RewardStrategy.ONCEANDFORALL);
        FrozenLakeEnvironment embedded = new FrozenLakeEnvironment(
            "/frozen_lake/FrozenLakeGrid_10_10Bis.mch", RewardStrategy.EMBEDDED);

        Snapshot a = snapshot(once);
        Snapshot b = snapshot(embedded);
        check(!a.states.isEmpty(), "No reachable states found");
        check(a.transitions > 0, "No game transitions found");
        check(a.states.equals(b.states), "Different reachable game-state valuations");
        check(a.edges.keySet().equals(b.edges.keySet()),
              "Different source-to-successor game-state pairs");
        check(a.transitions == b.transitions, "Different transition counts");

        for (String edge : a.edges.keySet()) {
            EdgeData x = a.edges.get(edge);
            EdgeData y = b.edges.get(edge);
            check(x.multiplicity == y.multiplicity,
                  "Different transition multiplicity at " + edge);
            check(Double.compare(x.reward, y.reward) == 0,
                  "Different rewards for " + edge
                  + " once=" + x.reward + " embedded=" + y.reward);
        }

        check(once.getRewardCacheMisses() > 0,
              "No subscribed Once-and-for-all reward values were retrieved");
        check(once.getRewardCacheHits() > 0,
              "No repeated reward lookups were served from the cache");

        System.out.printf(
            "FROZEN_LAKE_10_EQUIVALENCE PASS states=%d transitions=%d uniqueStatePairs=%d "
            + "rewardCacheHits=%d rewardCacheMisses=%d cachedStates=%d%n",
            a.states.size(), a.transitions, a.edges.size(),
            once.getRewardCacheHits(), once.getRewardCacheMisses(),
            once.getCachedRewardStateCount());
    }
}
