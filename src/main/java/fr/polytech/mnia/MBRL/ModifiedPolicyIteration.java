package fr.polytech.mnia.MBRL;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/**
 *  book{puterman1994mdp,
        title={Markov Decision Processes: Discrete Stochastic Dynamic Programming},
        author={Puterman, Martin L.},
        year={1994},
        publisher={John Wiley \& Sons}
    } => Chapter 6: Modified Policy Iteration
 *
 * An agent that implements a variant of Modified Policy Iteration (MPI).
 * <p>
 * Compared to standard Policy Iteration, the policy evaluation step is
 * truncated: instead of iterating until full convergence of V, we perform
 * only a bounded number of sweeps (evalIterations) over the current policy.
 * This keeps the algorithm fully model-based and requires a prior exploration
 * of the state space, exactly like {@link PolicyIterationV1}, but can offer
 * a different trade-off between computation time and quality of the value
 * estimates.
 * </p>
 */
public class ModifiedPolicyIteration extends Agent {

    /** Maximum number of outer policy-iteration loops. */
    private final int maxIterations;

    /**
     * Maximum number of partial evaluation sweeps of the value function
     * per policy-evaluation phase.
     */
    private final int evalIterations;

    /** State-value function V(s). */
    private final Map<State, Double> vValues;

    /** Deterministic policy mapping each state to a chosen transition (action). */
    private final Map<State, Transition> policy;

    /**
     * Q-values Q(s,a) induced by the final policy and value function.
     * These are mainly used for inspection or export (e.g. to SimB).
     */
    private final Map<State, Map<Transition, Double>> qValues;

    /**
     * Builds a ModifiedPolicyIterationV1 agent.
     *
     * @param env            the environment on which to run MPI
     * @param gamma          discount factor
     * @param teta           convergence threshold used inside the partial
     *                       evaluation sweeps
     * @param maxIterations  maximum number of policy-iteration steps
     * @param evalIterations maximum number of evaluation sweeps per step
     */
    public ModifiedPolicyIteration(Environment env,
                                     double gamma,
                                     double teta,
                                     int maxIterations,
                                     int evalIterations) {
        super(env, gamma, teta);
        this.maxIterations = maxIterations;
        this.evalIterations = evalIterations;
        this.vValues = new HashMap<>();
        this.policy = new HashMap<>();
        this.qValues = new HashMap<>();
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        // Explore the whole state space using the chosen strategy
        env.explore(strategy);
        System.out.println("Start learning (Modified Policy Iteration)");
        long startTime = System.nanoTime();

        // Initialise a simple deterministic policy and V(s)
        Set<Integer> stateIds = env.getStateIds();
        for (int sID : stateIds) {
            State s = env.gState(sID);
            List<Transition> actions = s.getOutTransitions();
            if (!actions.isEmpty()) {
                // Pick the first available transition as an initial policy
                policy.put(s, actions.get(0));
                vValues.put(s, 0.0);
            }
        }

        // Alternate between partial policy evaluation and policy improvement
        int iteration = 0;
        boolean policyStable;
        do {
            partialPolicyEvaluation();
            policyStable = policyImprovement();
            iteration++;
            System.out.println("Iteration: " + iteration + " | policy stable: " + policyStable);
        } while (!policyStable && iteration < maxIterations);

        long endTime = System.nanoTime();
        double durationInSeconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("Execution time (MPI): " + durationInSeconds + " seconds");
    }

    /**
     * Performs a truncated policy-evaluation phase.
     * <p>
     * We perform at most {@code evalIterations} sweeps over the states,
     * updating V(s) according to the current policy. Each sweep stops early
     * if the maximum change over all V(s) falls below {@code teta}.
     * </p>
     */
    private void partialPolicyEvaluation() {
        for (int k = 0; k < evalIterations; k++) {
            double delta = 0.0;

            for (Map.Entry<State, Transition> entry : policy.entrySet()) {
                State s = entry.getKey();
                Transition t = entry.getValue();
                State sPrime = t.getDestination();

                double reward = env.reward(s, t, sPrime);
                double oldV = vValues.getOrDefault(s, 0.0);
                double newV = reward + gamma * vValues.getOrDefault(sPrime, 0.0);

                vValues.put(s, newV);
                delta = Math.max(delta, Math.abs(oldV - newV));
            }

            if (delta <= teta) {
                // V is sufficiently stable for the current policy
                break;
            }
        }
    }

    /**
     * Policy improvement step: for each state, choose the transition that
     * maximizes the one-step lookahead based on the current V(s).
     *
     * @return true if the policy did not change for any state (i.e., stable)
     */
    private boolean policyImprovement() {
        boolean stable = true;

        for (Map.Entry<State, Transition> entry : policy.entrySet()) {
            State s = entry.getKey();
            Transition oldAction = entry.getValue();

            double maxQ = Double.NEGATIVE_INFINITY;
            Transition bestAction = null;

            for (Transition t : s.getOutTransitions()) {
                State sPrime = t.getDestination();
                double reward = env.reward(s, t, sPrime);
                double q = reward + gamma * vValues.getOrDefault(sPrime, 0.0);

                // Maintain Q-values mainly for inspection/export
                qValues.computeIfAbsent(s, k -> new HashMap<>()).put(t, q);

                if (q > maxQ) {
                    maxQ = q;
                    bestAction = t;
                }
            }

            if (bestAction != null && !bestAction.equals(oldAction)) {
                policy.put(s, bestAction);
                stable = false;
            }
        }
        return stable;
    }

    @Override
    public Map<Transition, Double> getQValues(State s) {
        return qValues.getOrDefault(s, Collections.emptyMap());
    }
}