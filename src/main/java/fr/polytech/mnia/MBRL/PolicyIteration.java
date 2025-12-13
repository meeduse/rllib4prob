package fr.polytech.mnia.MBRL;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

import java.util.*;

import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/**
 * An agent that implements Policy Iteration for solving MDPs.
 * Iteratively evaluates and improves a policy until convergence.
 */
public class PolicyIteration extends Agent {

    private final int maxIterations;
    private Map<State, Double> vValues;
    private Map<State, Transition> policy;
    private Map<State, Map<Transition, Double>> qValues;

    public PolicyIteration(Environment env, double gamma, double teta, int maxIterations) {
        super(env, gamma, teta);
        this.maxIterations = maxIterations;
        this.vValues = new HashMap<>();
        this.policy = new HashMap<>();
        this.qValues = new HashMap<>();
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        env.explore(strategy);
        System.out.println("Start learning");long startTime = System.nanoTime();   

        Set<Integer> stateIds = env.getStateIds();
        for (int sID : stateIds) {
            State s = env.gState(sID);
            List<Transition> actions = s.getOutTransitions();
            if (!actions.isEmpty()) {
                policy.put(s, actions.get(0)); // Initial random policy
                vValues.put(s, 0.0);
            }
        }

        int iteration = 0;
        boolean policyStable;
        do {
            policyEvaluation();
            policyStable = policyImprovement();
            iteration++;
            System.out.println("Iteration: " + iteration + " | policy stable: " + policyStable);
        } while (!policyStable && iteration < maxIterations);
        long endTime = System.nanoTime();
        long duration = endTime - startTime; 
        double durationInSeconds = duration / 1_000_000_000.0;
        System.out.println("Execution time: " + durationInSeconds + " seconds");
    }

    private void policyEvaluation() {
        double delta;
        do {
            delta = 0.0;
            for (Map.Entry<State, Transition> entry : policy.entrySet()) {
                State s = entry.getKey();
                Transition t = entry.getValue();
                State sPrime = t.getDestination();
                double reward = env.reward(s, t, sPrime);
                double newV = reward + gamma * vValues.getOrDefault(sPrime, 0.0);
                delta = Math.max(delta, Math.abs(vValues.get(s) - newV));
                vValues.put(s, newV);
            }
        } while (delta > teta);
    }

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
