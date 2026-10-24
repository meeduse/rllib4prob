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
 * Modified Policy Iteration for a finite, exhaustively explored, deterministic
 * discounted ProB graph. Policy evaluation is deliberately TRUNCATED to at
 * most evalIterations in-place sweeps at each outer iteration.
 *
 * A stable policy alone is not a convergence certificate: the global Bellman
 * optimality residual must also be <= teta. Exceeding maxIterations is
 * reported separately, even if the policy is already stable.
 *
 * Terminal states have value zero. The output Q-values use the final V.
 */
public class ModifiedPolicyIteration extends Agent {

    public enum TerminationStatus {
        NOT_STARTED, CONVERGED, ITERATION_LIMIT_REACHED, NO_STATES
    }

    private final int maxIterations;
    private final int evalIterations;
    private final Map<State, Double> vValues = new HashMap<>();
    private final Map<State, Transition> policy = new HashMap<>();
    private final Map<State, Map<Transition, Double>> qValues = new HashMap<>();

    private TerminationStatus status = TerminationStatus.NOT_STARTED;
    private int iterations;
    private int exploredStates;
    private int evaluationSweeps;
    private long evaluationBackups;
    private long improvementBackups;
    private long residualBackups;
    private long rewardLookups;
    private boolean policyStable;
    private double lastEvaluationDelta = Double.NaN;
    private double lastPolicyResidual = Double.NaN;
    private double lastBellmanResidual = Double.NaN;

    /** Backwards-compatible constructor used by AgentFactory. */
    public ModifiedPolicyIteration(Environment env, double gamma, double teta,
                                   int maxIterations, int evalIterations) {
        super(env, gamma, teta);
        if (!Double.isFinite(gamma) || gamma < 0.0 || gamma >= 1.0) {
            throw new IllegalArgumentException("Discounted MPI requires 0 <= gamma < 1");
        }
        if (!Double.isFinite(teta) || teta < 0.0) {
            throw new IllegalArgumentException("teta must be finite and nonnegative");
        }
        if (maxIterations <= 0 || evalIterations <= 0) {
            throw new IllegalArgumentException("Both MPI iteration limits must be positive");
        }
        this.maxIterations = maxIterations;
        this.evalIterations = evalIterations;
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        if (strategy == ExplorationStrategy.NONE) {
            throw new IllegalArgumentException("MPI requires PREPROCESS or RECURSIVE exploration");
        }
        reset();
        env.explore(strategy);
        System.out.println(
    "Preparing state transitions and rewards. " +
    "The first iteration may take some time..."
);
System.out.flush();
        long started = System.nanoTime();

        List<Integer> ids = new ArrayList<>(env.getStateIds());
        Collections.sort(ids); // deterministic within a fixed ProB numbering
        List<State> states = new ArrayList<>(ids.size());
        for (int id : ids) {
            State s = env.gState(id);
            if (s == null) {
                throw new IllegalStateException("Missing ProB state ID " + id);
            }
            states.add(s);
            vValues.put(s, 0.0);
        }
        exploredStates = states.size();
        if (states.isEmpty()) {
            status = TerminationStatus.NO_STATES;
            printSummary(System.nanoTime() - started);
            return;
        }

        // Maintain the original choice of first outgoing transition.
        for (State s : states) {
            List<Transition> actions = s.getOutTransitions();
            if (!actions.isEmpty()) {
                policy.put(s, actions.get(0));
            }
        }

        for (int k = 1; k <= maxIterations; k++) {
            iterations = k;
            partialPolicyEvaluation(states);
            policyStable = improvePolicy(states);
            // The partial evaluator is not required to converge at each step.
            // Only check the expensive global residual when policy is stable
            // or when the outer budget has been exhausted.
            if (policyStable || k == maxIterations) {
                lastPolicyResidual = policyResidual(states);
                lastBellmanResidual = globalBellmanResidual(states, false);
            }
            System.out.printf(Locale.ROOT,
                    "MPI iteration=%d evaluation_sweeps=%d evaluation_delta=%.9g "
                    + "policy_stable=%s policy_residual=%.9g bellman_residual=%.9g%n",
                    k, evaluationSweeps, lastEvaluationDelta,
                    policyStable, lastPolicyResidual, lastBellmanResidual);
            System.out.flush();

            if (policyStable && lastBellmanResidual <= teta
                    && lastPolicyResidual <= teta) {
                status = TerminationStatus.CONVERGED;
                break;
            }
        }

        if (status == TerminationStatus.NOT_STARTED) {
            status = TerminationStatus.ITERATION_LIMIT_REACHED;
        }
        lastPolicyResidual = policyResidual(states);
        // Produce full final Q-values and authoritative optimality residual.
        lastBellmanResidual = globalBellmanResidual(states, true);
        printSummary(System.nanoTime() - started);
    }

    private void partialPolicyEvaluation(List<State> states) {
        for (int sweep = 0; sweep < evalIterations; sweep++) {
            evaluationSweeps++;
            double delta = 0.0;
            for (State s : states) {
                Transition action = policy.get(s);
                if (action == null) {
                    continue; // terminal state
                }
                double oldValue = vValues.get(s);
                double newValue = actionValue(s, action);
                vValues.put(s, newValue);
                evaluationBackups++;
                delta = Math.max(delta, Math.abs(newValue - oldValue));
            }
            lastEvaluationDelta = delta;
            if (delta <= teta) {
                break;
            }
        }
    }

    private boolean improvePolicy(List<State> states) {
        boolean stable = true;
        for (State s : states) {
            List<Transition> actions = s.getOutTransitions();
            if (actions.isEmpty()) continue;
            improvementBackups++;
            Transition incumbent = policy.get(s);
            Transition best = null;
            double bestQ = Double.NEGATIVE_INFINITY;
            double oldQ = Double.NEGATIVE_INFINITY;
            for (Transition action : actions) {
                double q = actionValue(s, action);
                if (q > bestQ) {
                    best = action;
                    bestQ = q;
                }
                if (action.equals(incumbent)) {
                    oldQ = q;
                }
            }
            // Keep the original action on a numerical tie; prevents churning.
            if (incumbent != null && oldQ >= bestQ - 1e-12) {
                best = incumbent;
            }
            if (best == null) {
                throw new IllegalStateException("Nonterminal state without a policy action");
            }
            if (!best.equals(incumbent)) {
                policy.put(s, best);
                stable = false;
            }
        }
        return stable;
    }

    private double policyResidual(List<State> states) {
        double residual = 0.0;
        for (State s : states) {
            Transition action = policy.get(s);
            double backedUp = action == null ? 0.0 : actionValue(s, action);
            residualBackups++;
            residual = Math.max(residual, Math.abs(backedUp - vValues.get(s)));
        }
        return residual;
    }

    private double globalBellmanResidual(List<State> states, boolean recordQ) {
        double residual = 0.0;
        for (State s : states) {
            List<Transition> actions = s.getOutTransitions();
            double best = Double.NEGATIVE_INFINITY;
            Map<Transition, Double> qs = recordQ && !actions.isEmpty()
                    ? qValues.computeIfAbsent(s, unused -> new HashMap<>()) : null;
            if (recordQ && actions.isEmpty()) qValues.remove(s);
            for (Transition action : actions) {
                double q = actionValue(s, action);
                if (qs != null) qs.put(action, q);
                best = Math.max(best, q);
            }
            if (actions.isEmpty()) best = 0.0;
            residualBackups++;
            residual = Math.max(residual, Math.abs(best - vValues.get(s)));
        }
        return residual;
    }

    private double actionValue(State s, Transition action) {
        State next = action.getDestination();
        Double nextV = vValues.get(next);
        if (nextV == null) {
            throw new IllegalStateException("MPI transition reaches state outside explored graph");
        }
        double reward = env.reward(s, action, next);
        rewardLookups++;
        double value = reward + gamma * nextV;
        if (!Double.isFinite(reward) || !Double.isFinite(value)) {
            throw new IllegalStateException("Non-finite reward or Q-value in MPI");
        }
        return value;
    }

    private void reset() {
        vValues.clear();
        policy.clear();
        qValues.clear();
        status = TerminationStatus.NOT_STARTED;
        iterations = 0;
        exploredStates = 0;
        evaluationSweeps = 0;
        evaluationBackups = 0;
        improvementBackups = 0;
        residualBackups = 0;
        rewardLookups = 0;
        policyStable = false;
        lastEvaluationDelta = Double.NaN;
        lastPolicyResidual = Double.NaN;
        lastBellmanResidual = Double.NaN;
    }

    private void printSummary(long elapsed) {
        System.out.printf(Locale.ROOT,
                "MPI_RESULT status=%s gamma=%.9g theta=%.9g maxIterations=%d "
                + "evalIterations=%d iterations=%d evaluationSweeps=%d "
                + "exploredStates=%d evaluationBackups=%d improvementBackups=%d "
                + "residualBackups=%d totalBackups=%d rewardLookups=%d "
                + "policyStable=%s evaluationDelta=%.9g policyResidual=%.9g "
                + "bellmanResidual=%.9g learningSeconds=%.6f%n",
                status, gamma, teta, maxIterations, evalIterations,
                iterations, evaluationSweeps, exploredStates,
                evaluationBackups, improvementBackups, residualBackups,
                evaluationBackups + improvementBackups + residualBackups,
                rewardLookups, policyStable, lastEvaluationDelta,
                lastPolicyResidual, lastBellmanResidual,
                elapsed / 1_000_000_000.0);
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

    public int getIterations() {
        return iterations;
    }

    public double getLastBellmanResidual() {
        return lastBellmanResidual;
    }
}
