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

/**
 * Gauss-Seidel (in-place) value iteration on a fully explored, deterministic,
 * discounted finite-state ProB model. Terminal-state values are zero.
 *
 * CONVERGED means that the GLOBAL Bellman optimality residual on all discovered
 * states is at most teta; reaching maxIterations is reported separately.
 */
public class ValueIteration extends Agent {

    public enum TerminationStatus {
        NOT_STARTED, CONVERGED, ITERATION_LIMIT_REACHED, NO_STATES
    }

    private final int maxIterations;
    private final Map<State, Double> vValues = new HashMap<>();
    protected final Map<State, Map<Transition, Double>> qValues = new HashMap<>();

    private TerminationStatus status = TerminationStatus.NOT_STARTED;
    private int completedIterations;
    private int exploredStates;
    private long updateBackups;
    private long residualBackups;
    private long rewardLookups;
    private double lastBellmanResidual = Double.NaN;

    public ValueIteration(Environment env, double gamma, double teta, int maxIterations) {
        super(env, gamma, teta);
        if (!Double.isFinite(gamma) || gamma < 0.0 || gamma >= 1.0) {
            throw new IllegalArgumentException("Discounted VI requires 0 <= gamma < 1");
        }
        if (!Double.isFinite(teta) || teta < 0.0) {
            throw new IllegalArgumentException("teta must be finite and nonnegative");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        this.maxIterations = maxIterations;
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        if (strategy == ExplorationStrategy.NONE) {
            throw new IllegalArgumentException("VI requires PREPROCESS or RECURSIVE exploration");
        }

        reset();
        env.explore(strategy);
        long learningStart = System.nanoTime();

        // Stable within a given ProB state numbering, but not a canonical
        // semantic order across independent ProB explorations.
        List<Integer> ids = new ArrayList<>(env.getStateIds());
        Collections.sort(ids);
        List<State> states = new ArrayList<>(ids.size());
        for (int id : ids) {
            State state = env.gState(id);
            if (state == null) {
                throw new IllegalStateException("Missing ProB state ID " + id);
            }
            states.add(state);
            vValues.put(state, 0.0);
        }
        exploredStates = states.size();
        if (states.isEmpty()) {
            status = TerminationStatus.NO_STATES;
            printSummary(System.nanoTime() - learningStart);
            return;
        }

        // In-place VI preserves the update style of the original implementation.
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            double sweepDelta = 0.0;
            for (State state : states) {
                double oldValue = vValues.get(state);
                double newValue = bellmanValue(state, false);
                vValues.put(state, newValue);
                sweepDelta = Math.max(sweepDelta, Math.abs(newValue - oldValue));
                updateBackups++;
            }
            completedIterations = iteration;

// Affichage après chaque itération
System.out.printf(Locale.ROOT,
        "VI iteration=%d sweep_delta=%.9g%n",
        iteration, sweepDelta);
System.out.flush();

// Vérification de la convergence
if (sweepDelta <= teta || iteration == maxIterations) {

    lastBellmanResidual = globalBellmanResidual(states, true);

    System.out.printf(Locale.ROOT,
            "VI_RESIDUAL iteration=%d global_residual=%.9g%n",
            iteration, lastBellmanResidual);

    if (lastBellmanResidual <= teta) {
        status = TerminationStatus.CONVERGED;
        break;
    }
}
        }

        if (status != TerminationStatus.CONVERGED) {
            status = TerminationStatus.ITERATION_LIMIT_REACHED;
        }
        printSummary(System.nanoTime() - learningStart);
    }

    /** One Bellman optimality backup. Updates Q only when requested. */
    private double bellmanValue(State state, boolean recordQ) {
        List<Transition> actions = state.getOutTransitions();
        if (actions.isEmpty()) {
            if (recordQ) {
                qValues.remove(state);
            }
            return 0.0;
        }

        double best = Double.NEGATIVE_INFINITY;
        Map<Transition, Double> qs = recordQ
                ? qValues.computeIfAbsent(state, ignored -> new HashMap<>()) : null;
        for (Transition action : actions) {
            State successor = action.getDestination();
            Double successorValue = vValues.get(successor);
            if (successorValue == null) {
                throw new IllegalStateException(
                        "Bellman backup reached a state missing from exploration");
            }
            double reward = env.reward(state, action, successor);
            rewardLookups++;
            if (!Double.isFinite(reward)) {
                throw new IllegalStateException("Non-finite transition reward");
            }
            double q = reward + gamma * successorValue;
            if (!Double.isFinite(q)) {
                throw new IllegalStateException("Non-finite Q value");
            }
            if (recordQ) {
                qs.put(action, q);
            }
            best = Math.max(best, q);
        }
        return best;
    }

    private double globalBellmanResidual(List<State> states, boolean recordQ) {
        double residual = 0.0;
        for (State state : states) {
            double backedUpValue = bellmanValue(state, recordQ);
            residualBackups++;
            residual = Math.max(residual,
                    Math.abs(backedUpValue - vValues.get(state)));
        }
        return residual;
    }

    private void reset() {
        vValues.clear();
        qValues.clear();
        status = TerminationStatus.NOT_STARTED;
        completedIterations = 0;
        exploredStates = 0;
        updateBackups = 0;
        residualBackups = 0;
        rewardLookups = 0;
        lastBellmanResidual = Double.NaN;
    }

    private void printSummary(long learningNanos) {
        System.out.printf(Locale.ROOT,
                "VI_RESULT status=%s gamma=%.9g theta=%.9g maxIterations=%d iterations=%d "
                + "exploredStates=%d updateBackups=%d residualBackups=%d "
                + "totalBackups=%d rewardLookups=%d bellmanResidual=%.9g "
                + "learningSeconds=%.6f%n",
                status, gamma, teta, maxIterations, completedIterations, exploredStates,
                updateBackups, residualBackups, updateBackups + residualBackups,
                rewardLookups, lastBellmanResidual, learningNanos / 1_000_000_000.0);
    }

    @Override
    public Map<Transition, Double> getQValues(State state) {
        return qValues.getOrDefault(state, Collections.emptyMap());
    }

    public double getValue(State state) {
        return vValues.getOrDefault(state, 0.0);
    }

    public TerminationStatus getStatus() {
        return status;
    }

    public int getCompletedIterations() {
        return completedIterations;
    }

    public double getLastBellmanResidual() {
        return lastBellmanResidual;
    }

    public long getTotalBackups() {
        return updateBackups + residualBackups;
    }
}
