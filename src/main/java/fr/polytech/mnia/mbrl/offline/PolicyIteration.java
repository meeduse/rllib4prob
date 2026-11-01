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
 * In-place (Gauss-Seidel) Policy Iteration on an exhaustively explored,
 * deterministic, discounted ProB transition graph.
 *
 * CONVERGED requires a stable improved policy, an evaluated policy with
 * residual <= teta, and a global Bellman OPTIMALITY residual <= teta.
 * Reaching either iteration limit is never silently called convergence.
 * Terminal states have value zero.
 */
public class PolicyIteration extends Agent {

    public enum TerminationStatus {
        NOT_STARTED, CONVERGED, ITERATION_LIMIT_REACHED,
        EVALUATION_LIMIT_REACHED, NO_STATES
    }

    private final int maxIterations;
    private final int maxEvaluationSweeps;
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
    private double lastPolicyResidual = Double.NaN;
    private double lastBellmanResidual = Double.NaN;
    private boolean policyStable;

    public PolicyIteration(Environment env, double gamma, double teta, int maxIterations) {
        this(env, gamma, teta, maxIterations,
                Integer.getInteger("rllib4prob.pi.maxEvaluationSweeps", 10_000));
    }

    /** Testable explicit evaluation budget; factory continues to use the original constructor. */
    public PolicyIteration(Environment env, double gamma, double teta,
                           int maxIterations, int maxEvaluationSweeps) {
        super(env, gamma, teta);
        if (!Double.isFinite(gamma) || gamma < 0.0 || gamma >= 1.0) {
            throw new IllegalArgumentException("Discounted PI requires 0 <= gamma < 1");
        }
        if (!Double.isFinite(teta) || teta < 0.0) {
            throw new IllegalArgumentException("teta must be finite and nonnegative");
        }
        if (maxIterations <= 0 || maxEvaluationSweeps <= 0) {
            throw new IllegalArgumentException("Both PI iteration limits must be positive");
        }
        this.maxIterations = maxIterations;
        this.maxEvaluationSweeps = maxEvaluationSweeps;
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        if (strategy == ExplorationStrategy.NONE) {
            throw new IllegalArgumentException("PI requires PREPROCESS or RECURSIVE exploration");
        }
        reset();
        env.explore(strategy);
        long start = System.nanoTime();

        // Stable for a fixed ProB state numbering; IDs are not semantic keys.
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
            printSummary(System.nanoTime() - start);
            return;
        }

        // Original PI initialized its policy with the first outgoing action.
        for (State state : states) {
            List<Transition> actions = state.getOutTransitions();
            if (!actions.isEmpty()) {
                policy.put(state, actions.get(0));
            }
        }

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            iterations = iteration;
            if (!evaluatePolicy(states)) {
                status = TerminationStatus.EVALUATION_LIMIT_REACHED;
                System.out.printf(Locale.ROOT,
                        "PI iteration=%d evaluation_limit=%d policy_residual=%.9g%n",
                        iteration, maxEvaluationSweeps, lastPolicyResidual);
                break;
            }

            policyStable = improvePolicy(states);
            // Compute the expensive global optimality residual only when
            // policy is stable or the outer iteration budget is exhausted.
            if (policyStable || iteration == maxIterations) {
                lastBellmanResidual = globalBellmanResidual(states, false);
            }
            System.out.printf(Locale.ROOT,
                    "PI iteration=%d evaluation_sweeps=%d policy_residual=%.9g "
                    + "policy_stable=%s bellman_residual=%.9g%n",
                    iteration, evaluationSweeps, lastPolicyResidual,
                    policyStable, lastBellmanResidual);
            System.out.flush();

            if (policyStable && lastPolicyResidual <= teta
                    && lastBellmanResidual <= teta) {
                status = TerminationStatus.CONVERGED;
                break;
            }
        }

        if (status == TerminationStatus.NOT_STARTED) {
            status = TerminationStatus.ITERATION_LIMIT_REACHED;
        }
        // Always report the final optimality residual, and build Q from the
        // FINAL V, not values recorded in an earlier improvement step.
        lastBellmanResidual = globalBellmanResidual(states, true);
        printSummary(System.nanoTime() - start);
    }

    /** Returns false on exhaustion of the policy evaluation sweep budget. */
    private boolean evaluatePolicy(List<State> states) {
        for (int sweep = 1; sweep <= maxEvaluationSweeps; sweep++) {
            evaluationSweeps++;
            double delta = 0.0;
            for (State state : states) {
                Transition action = policy.get(state);
                if (action == null) {
                    continue; // terminal state: value zero
                }
                double oldV = vValues.get(state);
                double newV = actionValue(state, action);
                vValues.put(state, newV);
                evaluationBackups++;
                delta = Math.max(delta, Math.abs(newV - oldV));
            }
            if (delta <= teta || sweep == maxEvaluationSweeps) {
                lastPolicyResidual = policyResidual(states);
                if (lastPolicyResidual <= teta) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean improvePolicy(List<State> states) {
        boolean stable = true;
        for (State state : states) {
            List<Transition> actions = state.getOutTransitions();
            if (actions.isEmpty()) {
                continue;
            }
            improvementBackups++;
            Transition old = policy.get(state);
            Transition best = null;
            double maxQ = Double.NEGATIVE_INFINITY;
            double oldQ = Double.NEGATIVE_INFINITY;
            for (Transition action : actions) {
                double q = actionValue(state, action);
                if (q > maxQ) {
                    maxQ = q;
                    best = action;
                }
                if (action.equals(old)) {
                    oldQ = q;
                }
            }
            // Preserve the incumbent on numerical ties to prevent policy
            // oscillation caused solely by transition enumeration order.
            if (old != null && oldQ >= maxQ - 1e-12) {
                best = old;
            }
            if (best == null) {
                throw new IllegalStateException("Nonterminal state has no policy action");
            }
            if (!best.equals(old)) {
                policy.put(state, best);
                stable = false;
            }
        }
        return stable;
    }

    private double policyResidual(List<State> states) {
        double residual = 0.0;
        for (State state : states) {
            Transition action = policy.get(state);
            double backedUpValue = action == null ? 0.0 : actionValue(state, action);
            residualBackups++;
            residual = Math.max(residual,
                    Math.abs(backedUpValue - vValues.get(state)));
        }
        return residual;
    }

    private double globalBellmanResidual(List<State> states, boolean recordQ) {
        double residual = 0.0;
        for (State state : states) {
            List<Transition> actions = state.getOutTransitions();
            double best = Double.NEGATIVE_INFINITY;
            Map<Transition, Double> qs = recordQ && !actions.isEmpty()
                    ? qValues.computeIfAbsent(state, ignored -> new HashMap<>()) : null;
            if (recordQ && actions.isEmpty()) {
                qValues.remove(state);
            }
            for (Transition action : actions) {
                double q = actionValue(state, action);
                if (qs != null) {
                    qs.put(action, q);
                }
                best = Math.max(best, q);
            }
            if (actions.isEmpty()) {
                best = 0.0;
            }
            residualBackups++;
            residual = Math.max(residual, Math.abs(best - vValues.get(state)));
        }
        return residual;
    }

    private double actionValue(State state, Transition action) {
        State successor = action.getDestination();
        Double next = vValues.get(successor);
        if (next == null) {
            throw new IllegalStateException(
                    "PI transition reaches a state missing from exhaustive exploration");
        }
        double reward = env.reward(state, action, successor);
        rewardLookups++;
        if (!Double.isFinite(reward)) {
            throw new IllegalStateException("Non-finite reward in PI");
        }
        double q = reward + gamma * next;
        if (!Double.isFinite(q)) {
            throw new IllegalStateException("Non-finite Q value in PI");
        }
        return q;
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
        lastPolicyResidual = Double.NaN;
        lastBellmanResidual = Double.NaN;
    }

    private void printSummary(long elapsedNanos) {
        System.out.printf(Locale.ROOT,
                "PI_RESULT status=%s gamma=%.9g theta=%.9g maxIterations=%d "
                + "maxEvaluationSweeps=%d iterations=%d evaluationSweeps=%d "
                + "exploredStates=%d evaluationBackups=%d improvementBackups=%d "
                + "residualBackups=%d totalBackups=%d rewardLookups=%d "
                + "policyStable=%s policyResidual=%.9g bellmanResidual=%.9g "
                + "learningSeconds=%.6f%n",
                status, gamma, teta, maxIterations, maxEvaluationSweeps,
                iterations, evaluationSweeps, exploredStates,
                evaluationBackups, improvementBackups, residualBackups,
                evaluationBackups + improvementBackups + residualBackups,
                rewardLookups, policyStable, lastPolicyResidual,
                lastBellmanResidual, elapsedNanos / 1_000_000_000.0);
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

    public double getLastBellmanResidual() {
        return lastBellmanResidual;
    }

    public int getIterations() {
        return iterations;
    }
}
