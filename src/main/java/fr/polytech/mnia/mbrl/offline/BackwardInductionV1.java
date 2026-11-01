package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Finite-horizon backward induction (dynamic programming):
 * V_0(s)=0 and V_h(s)=max_a[R(s,a,s')+gamma*V_(h-1)(s')].
 * A terminal state has V_h(s)=0 for every h.
 * Completing the requested horizon is NOT a claim of Bellman convergence.
 */
public class BackwardInductionV1 extends Agent {
    public enum TerminationStatus { HORIZON_COMPLETED, NO_STATES }

    private final int horizon;
    private Map<State, Double> vValues = new HashMap<>();
    private final Map<State, Map<Transition, Double>> qValues = new HashMap<>();
    private TerminationStatus status = TerminationStatus.NO_STATES;
    private int completedHorizon = 0;
    private long updateBackups = 0;
    private long rewardLookups = 0;
    private double lastHorizonDelta = Double.NaN;

    public BackwardInductionV1(Environment env, double gamma, int horizon) {
        super(env, gamma, 0.0);
        if (horizon < 1) {
            throw new IllegalArgumentException(
                "Backward Induction requires horizon >= 1 (received " + horizon
                + "). Set env.<environment>.BACKWARD_INDUCTION.offline.horizon "
                + "in config/agent-parameters.properties.");
        }
        this.horizon = horizon;
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        env.explore(strategy);
        System.out.println("Preparing finite-horizon dynamic programming (BI). First pass may take time...");
        System.out.flush();
        long startTime = System.nanoTime();

        status = TerminationStatus.NO_STATES;
        completedHorizon = 0;
        updateBackups = 0;
        rewardLookups = 0;
        lastHorizonDelta = Double.NaN;
        vValues.clear();
        qValues.clear();

        List<State> states = new ArrayList<>();
        for (int id : env.getStateIds()) {
            State s = env.gState(id);
            if (s == null) {
                throw new IllegalStateException("Missing explored state " + id);
            }
            states.add(s);
        }

        if (states.isEmpty()) {
            printResult(startTime, states.size());
            return;
        }

        // Establish the finite reachable-state domain; never silently assume
        // V_(h-1)(s')=0 for a successor absent from the explored graph.
        Set<State> stateSet = new HashSet<>(states);
        Map<State, Double> previous = new HashMap<>();
        for (State s : states) {
            previous.put(s, 0.0);
        }

        for (int h = 1; h <= horizon; h++) {
            Map<State, Double> next = new HashMap<>();
            double delta = 0.0;

            for (State s : states) {
                updateBackups++;
                List<Transition> actions = s.getOutTransitions();
                if (actions.isEmpty()) {
                    next.put(s, 0.0);
                    continue;
                }

                double maxQ = Double.NEGATIVE_INFINITY;
                Map<Transition, Double> finalQ = null;
                if (h == horizon) {
                    finalQ = qValues.computeIfAbsent(s, key -> new HashMap<>());
                }
                for (Transition t : actions) {
                    State successor = t.getDestination();
                    if (!stateSet.contains(successor)) {
                        throw new IllegalStateException("Exploration is incomplete: transition to an unlisted successor");
                    }
                    double reward = env.reward(s, t, successor);
                    rewardLookups++;
                    double q = reward + gamma * previous.get(successor);
                    if (!Double.isFinite(q)) {
                        throw new IllegalStateException("Non-finite action value in finite-horizon backup");
                    }
                    if (finalQ != null) {
                        finalQ.put(t, q);
                    }
                    maxQ = Math.max(maxQ, q);
                }
                next.put(s, maxQ);
                delta = Math.max(delta, Math.abs(maxQ - previous.get(s)));
            }

            previous = next;
            completedHorizon = h;
            lastHorizonDelta = delta;
            System.out.printf(Locale.ROOT,
                "BI horizon_step=%d/%d value_delta=%.9g%n", h, horizon, delta);
            System.out.flush();
        }

        vValues = previous;
        status = TerminationStatus.HORIZON_COMPLETED;
        printResult(startTime, states.size());
    }

    private void printResult(long startTime, int exploredStates) {
        double seconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
            "BI_RESULT status=%s gamma=%.9g horizon=%d completedHorizon=%d "
            + "exploredStates=%d updateBackups=%d totalBackups=%d rewardLookups=%d "
            + "lastHorizonDelta=%.9g learningSeconds=%.6f%n",
            status, gamma, horizon, completedHorizon, exploredStates,
            updateBackups, updateBackups, rewardLookups, lastHorizonDelta, seconds);
    }

    public TerminationStatus getStatus() { return status; }
    public int getCompletedHorizon() { return completedHorizon; }
    public long getUpdateBackups() { return updateBackups; }
    public long getRewardLookups() { return rewardLookups; }
    public double getLastHorizonDelta() { return lastHorizonDelta; }
    public double getValue(State s) { return vValues.getOrDefault(s, 0.0); }
    @Override
    public Map<Transition, Double> getQValues(State s) {
        return Collections.unmodifiableMap(qValues.getOrDefault(s, Collections.emptyMap()));
    }
}
