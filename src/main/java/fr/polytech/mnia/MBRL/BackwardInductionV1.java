package fr.polytech.mnia.MBRL;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

import java.util.*;

/**
 * An agent that implements finite-horizon dynamic programming
 * (backward induction) for solving MDPs.
 *
 * Given a fixed planning horizon H, the algorithm computes V_h(s)
 * for h = 0..H using:
 *
 *   V_0(s) = 0
 *   V_{h+1}(s) = max_a [ R(s,a,s') + gamma * V_h(s') ].
 *
 * This is a model-based planner which requires an exhaustive
 * exploration of the state space via ProB, similarly to
 * {@link ValueIterationV1} and {@link PolicyIterationV1}.
 */
public class BackwardInductionV1 extends Agent {

    /** Planning horizon (number of steps). */
    private final int horizon;

    /** Final state-value function V_H(s). */
    private Map<State, Double> vValues;

    /** Action-value function Q_H(s,a) for inspection/export. */
    private final Map<State, Map<Transition, Double>> qValues;

    public BackwardInductionV1(Environment env,
                               double gamma,
                               int horizon) {
        // teta is unused here, but we keep it in the Agent signature;
        // we can pass a dummy value such as 0.0.
        super(env, gamma, 0.0);
        this.horizon = horizon;
        this.vValues = new HashMap<>();
        this.qValues = new HashMap<>();
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        // Full model construction via ProB
        env.explore(strategy);
        System.out.println("Start learning (Backward Induction / Finite Horizon DP)");
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

        // V_0(s) = 0 for all s
        Map<State, Double> vPrev = new HashMap<>();
        for (State s : states) {
            vPrev.put(s, 0.0);
        }

        // Backward induction: h = 1..H
        for (int h = 1; h <= horizon; h++) {
            Map<State, Double> vCurr = new HashMap<>();
            double delta = 0.0;

            for (State s : states) {
                List<Transition> actions = s.getOutTransitions();
                if (actions.isEmpty()) {
                    // Terminal state: horizon-limited return is zero by convention
                    vCurr.put(s, 0.0);
                    continue;
                }

                double maxQ = Double.NEGATIVE_INFINITY;
                Map<Transition, Double> qForState =
                        qValues.computeIfAbsent(s, key -> new HashMap<>());

                for (Transition t : actions) {
                    State sPrime = t.getDestination();
                    double reward = env.reward(s, t, sPrime);
                    double q = reward + gamma * vPrev.getOrDefault(sPrime, 0.0);

                    // On ne garde que les Q-values de la dernière étape (horizon H),
                    // mais on peut aussi stocker pour tous les h si nécessaire.
                    if (h == horizon) {
                        qForState.put(t, q);
                    }

                    if (q > maxQ) {
                        maxQ = q;
                    }
                }

                double oldV = vPrev.getOrDefault(s, 0.0);
                vCurr.put(s, maxQ);
                double diff = Math.abs(oldV - maxQ);
                if (diff > delta) {
                    delta = diff;
                }
            }

            System.out.println("Horizon step: " + h + " | max delta: " + delta);
            vPrev = vCurr; // for next backward step
        }

        // Final V_H(s)
        this.vValues = vPrev;

        long endTime = System.nanoTime();
        double durationInSeconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("Execution time (Backward Induction): " + durationInSeconds + " seconds");
    }

    @Override
    public Map<Transition, Double> getQValues(State s) {
        return qValues.getOrDefault(s, Collections.emptyMap());
    }

    /**
     * Optional accessor to inspect V_H(s).
     */
    public double getValue(State s) {
        return vValues.getOrDefault(s, 0.0);
    }
}