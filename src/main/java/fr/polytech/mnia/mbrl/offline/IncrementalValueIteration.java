package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

import java.util.*;

/**
 * An agent that implements an incremental (asynchronous) variant
 * of Value Iteration for solving MDPs.
 *
 * Compared to {@link ValueIteration}, which performs full sweeps over
 * all states at each iteration, this algorithm updates the value of a
 * subset of states at each step. This can be seen as an asynchronous or
 * incremental value iteration scheme: we repeatedly pick states and apply
 * a Bellman backup, using the current value function.
 *
 * The algorithm still requires an exhaustive exploration of the state
 * space via ProB (i.e., it is fully model-based), but the value updates
 * are more fine-grained.
 */
public class IncrementalValueIteration extends Agent {

    /** Maximum number of outer iterations. */
    private final int maxIterations;

    /**
     * Number of state updates performed per outer iteration.
     * If this is greater than or equal to the number of states,
     * each outer iteration will, in practice, touch all states.
     */
    private final int updatesPerIteration;

    /** State-value function V(s). */
    private final Map<State, Double> vValues;

    /** Action-value function Q(s,a) for inspection/export. */
    private final Map<State, Map<Transition, Double>> qValues;

    private final Random rng;

    /**
     * Constructs an Incremental Value Iteration agent.
     *
     * @param env                 the environment (backed by ProB)
     * @param gamma               discount factor
     * @param teta                convergence threshold on value changes
     * @param maxIterations       maximum number of outer iterations
     * @param updatesPerIteration number of Bellman backups (state updates)
     *                            per outer iteration
     */
    public IncrementalValueIteration(Environment env,
                                       double gamma,
                                       double teta,
                                       int maxIterations,
                                       int updatesPerIteration) {
        super(env, gamma, teta);
        this.maxIterations = maxIterations;
        this.updatesPerIteration = updatesPerIteration;
        this.vValues = new HashMap<>();
        this.qValues = new HashMap<>();
        this.rng = new Random();
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        // Explore the whole state space once using ProB
        env.explore(strategy);
        System.out.println("Start learning (Incremental Value Iteration)");
        long startTime = System.nanoTime();

        // Collect all reachable states
        Set<Integer> stateIds = env.getStateIds();
        List<State> states = new ArrayList<>();
        for (int sID : stateIds) {
            State s = env.gState(sID);
            states.add(s);
            vValues.putIfAbsent(s, 0.0);  // initialize V(s) = 0
        }

        if (states.isEmpty()) {
            System.out.println("No reachable states. Aborting learning.");
            return;
        }

        int nStates = states.size();
        int effectiveUpdates = Math.min(updatesPerIteration, nStates);

        // Incremental Value Iteration
        int iteration = 0;
        double delta;
        do {
            delta = 0.0;

            // Perform a limited number of Bellman backups per outer iteration
            for (int k = 0; k < effectiveUpdates; k++) {
                // Pick a random state to update (asynchronous scheme)
                State s = states.get(rng.nextInt(nStates));
                List<Transition> actions = s.getOutTransitions();
                if (actions.isEmpty()) {
                    // Terminal state: by convention, V(s) remains as is (often 0)
                    continue;
                }

                double oldV = vValues.getOrDefault(s, 0.0);
                double maxQ = Double.NEGATIVE_INFINITY;

                // Q-values for this state (for inspection/export)
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

                // Bellman backup for V(s)
                vValues.put(s, maxQ);
                double diff = Math.abs(oldV - maxQ);
                if (diff > delta) {
                    delta = diff;
                }
            }

            iteration++;
            System.out.println("Iteration: " + iteration + " | delta: " + delta);

            if (iteration >= maxIterations) {
                System.out.println("Reached maximum number of iterations.");
                break;
            }
        } while (delta > teta);

        long endTime = System.nanoTime();
        double durationInSeconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("Execution time (Incremental VI): " + durationInSeconds + " seconds");
    }

    @Override
    public Map<Transition, Double> getQValues(State s) {
        return qValues.getOrDefault(s, Collections.emptyMap());
    }
}