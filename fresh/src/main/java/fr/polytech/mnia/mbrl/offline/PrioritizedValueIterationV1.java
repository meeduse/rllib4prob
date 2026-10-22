package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

import java.util.*;

import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/**
 * An agent that implements a prioritized sweeping variant of
 * Value Iteration.
 *
 * After an exhaustive exploration of the state space (via ProB),
 * the algorithm maintains a priority queue of states, ordered by
 * their Bellman error:
 *
 *   error(s) = | V(s) - max_a [ R(s,a,s') + gamma * V(s') ] |
 *
 * At each step, the state with the highest error is updated first,
 * and its predecessors are reinserted into the queue with updated
 * priorities. This focuses computation on states where the value
 * function is most inconsistent with the Bellman equation.
 */
public class PrioritizedValueIterationV1 extends Agent {

    /** Maximum number of state updates (pops from the priority queue). */
    private final int maxUpdates;

    /** State-value function V(s). */
    private final Map<State, Double> vValues;

    /** Action-value function Q(s,a) for inspection/export. */
    private final Map<State, Map<Transition, Double>> qValues;

    /** Outgoing transitions for each state (cached). */
    private final Map<State, List<Transition>> outgoing;

    /** Predecessors of each state: for each s', a list of transitions (s,a)->s'. */
    private final Map<State, List<Transition>> predecessors;

    /**
     * Creates a Prioritized Value Iteration agent.
     *
     * @param env        the environment (backed by ProB)
     * @param gamma      discount factor
     * @param teta       threshold on Bellman error for stopping
     * @param maxUpdates safety bound on the number of updates
     */
    public PrioritizedValueIterationV1(Environment env,
                                       double gamma,
                                       double teta,
                                       int maxUpdates) {
        super(env, gamma, teta);
        this.maxUpdates = maxUpdates;
        this.vValues = new HashMap<>();
        this.qValues = new HashMap<>();
        this.outgoing = new HashMap<>();
        this.predecessors = new HashMap<>();
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        // Full state-space construction via ProB
        env.explore(strategy);
        System.out.println("Start learning (Prioritized Value Iteration)");
        long startTime = System.nanoTime();

        // Collect all reachable states
        Set<Integer> stateIds = env.getStateIds();
        List<State> states = new ArrayList<>();
        for (int sID : stateIds) {
            State s = env.gState(sID);
            states.add(s);
        }

        if (states.isEmpty()) {
            System.out.println("No reachable states. Aborting learning.");
            return;
        }

        // Cache outgoing transitions and build predecessors
        buildGraphs(states);

        // Initialise V(s) = 0 for all states
        for (State s : states) {
            vValues.put(s, 0.0);
        }

        // Priority queue of states, ordered by descending Bellman error
        PriorityQueue<StatePriority> pq = new PriorityQueue<>(
            Comparator.comparingDouble((StatePriority sp) -> sp.priority).reversed()
        );

        // Initial priorities: on first pass, we can compute
        // an initial Bellman error for each state
        for (State s : states) {
            double error = computeBellmanError(s);
            if (error > 0.0) {
                pq.add(new StatePriority(s, error));
            }
        }

        int updates = 0;
        while (!pq.isEmpty() && updates < maxUpdates) {
            StatePriority sp = pq.poll();
            State s = sp.state;

            // Recompute the current error; if it's below teta, skip update
            double currentError = computeBellmanError(s);
            if (currentError < teta) {
                continue;
            }

            // Perform Bellman backup for s
            double oldV = vValues.getOrDefault(s, 0.0);
            double newV = bellmanBackup(s);
            vValues.put(s, newV);

            double delta = Math.abs(oldV - newV);
            updates++;

            if (updates % 100 == 0) {
                System.out.println("Update " + updates + " | last delta: " + delta);
            }

            // After updating s, propagate changes to its predecessors
            List<Transition> preds = predecessors.getOrDefault(s, Collections.emptyList());
            for (Transition tPred : preds) {
                State sPred = tPred.getSource();
                double errPred = computeBellmanError(sPred);
                if (errPred >= teta) {
                    pq.add(new StatePriority(sPred, errPred));
                }
            }
        }

        long endTime = System.nanoTime();
        double durationInSeconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("Execution time (Prioritized VI): " + durationInSeconds + " seconds");
        System.out.println("Total updates performed: " + updates);
    }

    /**
     * Build outgoing and predecessor graphs from the state space.
     */
    private void buildGraphs(List<State> states) {
        outgoing.clear();
        predecessors.clear();

        for (State s : states) {
            List<Transition> outs = s.getOutTransitions();
            outgoing.put(s, outs);

            for (Transition t : outs) {
                State sPrime = t.getDestination();
                predecessors
                    .computeIfAbsent(sPrime, key -> new ArrayList<>())
                    .add(t);
            }
        }
    }

    /**
     * Computes the Bellman error for a given state:
     * |V(s) - max_a [ R(s,a,s') + gamma * V(s') ]|
     */
    private double computeBellmanError(State s) {
        List<Transition> actions = outgoing.getOrDefault(s, Collections.emptyList());
        if (actions.isEmpty()) {
            // Terminal state -> by convention, Bellman error is 0
            return 0.0;
        }

        double oldV = vValues.getOrDefault(s, 0.0);
        double maxQ = Double.NEGATIVE_INFINITY;

        for (Transition t : actions) {
            State sPrime = t.getDestination();
            double reward = env.reward(s, t, sPrime);
            double q = reward + gamma * vValues.getOrDefault(sPrime, 0.0);
            if (q > maxQ) {
                maxQ = q;
            }
        }

        return Math.abs(oldV - maxQ);
    }

    /**
     * Performs a Bellman backup for state s and updates Q(s,a) accordingly.
     *
     * @return the new value V(s) after the backup.
     */
    private double bellmanBackup(State s) {
        List<Transition> actions = outgoing.getOrDefault(s, Collections.emptyList());
        if (actions.isEmpty()) {
            // Terminal state
            return vValues.getOrDefault(s, 0.0);
        }

        double maxQ = Double.NEGATIVE_INFINITY;
        Map<Transition, Double> qForState =
            qValues.computeIfAbsent(s, key -> new HashMap<>());

        for (Transition t : actions) {
            State sPrime = t.getDestination();
            double reward = env.reward(s, t, sPrime);
            double q = reward + gamma * vValues.getOrDefault(sPrime, 0.0);
            qForState.put(t, q);
            if (q > maxQ) {
                maxQ = q;
            }
        }

        return maxQ;
    }

    @Override
    public Map<Transition, Double> getQValues(State s) {
        return qValues.getOrDefault(s, Collections.emptyMap());
    }

    /**
     * Internal structure for priority queue entries.
     */
    private static class StatePriority {
        final State state;
        final double priority;

        StatePriority(State state, double priority) {
            this.state = state;
            this.priority = priority;
        }
    }
}