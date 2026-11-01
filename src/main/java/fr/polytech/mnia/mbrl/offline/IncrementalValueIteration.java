package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Asynchronous value iteration with reproducible randomized state sweeps.
 *
 * Convergence is certified only by a GLOBAL Bellman residual, evaluated after
 * a complete randomized pass over the discovered states (and at the cap).
 * The partial-iteration delta is diagnostic only, never a stopping criterion.
 *
 * Assumes a finite, fully explored, deterministic transition system and a
 * discounted infinite-horizon Bellman optimality equation. Terminal V=0.
 */
public class IncrementalValueIteration extends Agent {

    public enum TerminationStatus {
        NOT_STARTED, CONVERGED, ITERATION_LIMIT_REACHED, NO_STATES
    }

    private final int maxIterations;
    private final int updatesPerIteration;
    private final long seed;
    private final Random rng;
    private final Map<State, Double> vValues = new HashMap<>();
    private final Map<State, Map<Transition, Double>> qValues = new HashMap<>();

    private TerminationStatus status = TerminationStatus.NOT_STARTED;
    private int completedIterations;
    private long updateBackups;
    private long residualBackups;
    private long rewardLookups;
    private int distinctUpdatedStates;
    private int exploredStates;
    private long canonicalOrderingNanos;
    private long canonicalOrderingTransitions;
    private double lastBellmanResidual = Double.NaN;

    /**
     * Backward-compatible signature. A default seed of zero is reproducible.
     * Override it for experimental repetitions using -Drllib4prob.ivi.seed=42.
     */
    public IncrementalValueIteration(Environment env, double gamma, double teta,
                                     int maxIterations, int updatesPerIteration) {
        this(env, gamma, teta, maxIterations, updatesPerIteration,
             Long.getLong("rllib4prob.ivi.seed", 0L));
    }

    /** Explicitly seeded constructor, convenient for controlled experiments. */
    public IncrementalValueIteration(Environment env, double gamma, double teta,
                                     int maxIterations, int updatesPerIteration,
                                     long seed) {
        super(env, gamma, teta);
        if (!Double.isFinite(gamma) || gamma < 0.0 || gamma >= 1.0) {
            throw new IllegalArgumentException("IVI requires 0 <= gamma < 1 for its discounted convergence guarantee");
        }
        if (!Double.isFinite(teta) || teta < 0.0) {
            throw new IllegalArgumentException("teta must be finite and nonnegative");
        }
        if (maxIterations <= 0 || updatesPerIteration <= 0) {
            throw new IllegalArgumentException("iteration and update limits must be positive");
        }
        this.maxIterations = maxIterations;
        this.updatesPerIteration = updatesPerIteration;
        this.seed = seed;
        this.rng = new Random(seed);
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        // IVI requires a fully enumerated, closed state set; NONE is not safe.
        if (strategy == ExplorationStrategy.NONE) {
            throw new IllegalArgumentException("IVI requires PREPROCESS or RECURSIVE exploration");
        }
        reset();
        env.explore(strategy);
        long startTime = System.nanoTime();

        List<Integer> stateIds = new ArrayList<>(env.getStateIds());
        Collections.sort(stateIds); // stable order before seeded shuffling
        List<State> states = new ArrayList<>();
        for (int id : stateIds) {
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
            printSummary(System.nanoTime() - startTime);
            return;
        }

        // Stable semantic ordering: BFS from the initial state, sorting outgoing
        // action labels.  Unlike ProB state IDs, this does not depend on the
        // discovery order.  Each state's transitions are enumerated once here;
        // no State.eval() is needed and no sorting takes place in Bellman backups.
        // Choose the ordering protocol explicitly.  FAST_IDS is useful to
        // measure backend overhead but is NOT reproducible under ProB ID
        // reassignment.  BFS_ACTIONS preserves semantic ordering (default).
        String orderingMethod = System.getProperty("rllib4prob.ivi.ordering", "BFS_ACTIONS")
            .trim().toUpperCase(Locale.ROOT);
        long orderingStart = System.nanoTime();
        switch (orderingMethod) {
            case "BFS_ACTIONS" -> states = canonicalBfsOrder(states);
            case "FAST_IDS" -> { /* already sorted by numeric ID above */ }
            default -> throw new IllegalArgumentException(
                "Unknown IVI ordering method: " + orderingMethod
                + " (expected BFS_ACTIONS or FAST_IDS)");
        }
        canonicalOrderingNanos = System.nanoTime() - orderingStart;
        System.out.printf(Locale.ROOT,
            "IVI_ORDERING method=%s states=%d transitions=%d orderingSeconds=%.6f%n",
            orderingMethod, states.size(), canonicalOrderingTransitions,
            canonicalOrderingNanos / 1_000_000_000.0);

        List<State> shuffled = new ArrayList<>(states);
        Collections.shuffle(shuffled, rng);
        Set<State> updated = new HashSet<>();
        int cursor = 0;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            double sampledDelta = 0.0;
            boolean completedFullPass = false;
            for (int k = 0; k < updatesPerIteration; k++) {
                State s = shuffled.get(cursor++);
                if (cursor == shuffled.size()) {
                    cursor = 0;
                    completedFullPass = true;
                    Collections.shuffle(shuffled, rng);
                }

                double oldV = vValues.get(s);
                double newV = bellmanValue(s, true);
                vValues.put(s, newV);
                updateBackups++;
                if (!s.getOutTransitions().isEmpty()) {
                    updated.add(s);
                }
                sampledDelta = Math.max(sampledDelta, Math.abs(newV - oldV));
            }
            completedIterations = iteration;
            distinctUpdatedStates = updated.size();

            // Check global convergence only after complete state coverage, and
            // always at the iteration cap to distinguish convergence from cap.
            if (completedFullPass || iteration == maxIterations) {
                lastBellmanResidual = computeGlobalBellmanResidual(states, false);
                System.out.printf(Locale.ROOT,
                    "IVI iteration=%d sample_delta=%.9g global_residual=%.9g%n",
                    iteration, sampledDelta, lastBellmanResidual);
                if (lastBellmanResidual <= teta) {
                    status = TerminationStatus.CONVERGED;
                    break;
                }
            }
        }

        if (status != TerminationStatus.CONVERGED) {
            status = TerminationStatus.ITERATION_LIMIT_REACHED;
        }
        // Final Q(s,a) values are recalculated at the FINAL V, not left stale
        // from earlier asynchronous updates. This is a separate full pass.
        computeGlobalBellmanResidual(states, true);
        printSummary(System.nanoTime() - startTime);
    }

    /**
     * Canonical graph traversal for fully explored DETERMINISTIC models.
     * It avoids state evaluation: successors are ordered by operation name and
     * parameter values, both available from the existing transition objects.
     * A given action label must have a unique destination in each state.
     */
    private List<State> canonicalBfsOrder(List<State> allStates) {
        Set<State> allowed = new HashSet<>(allStates);
        State initial = env.gState();
        if (initial == null || !allowed.contains(initial)) {
            throw new IllegalStateException("Initial state missing from ProB explored graph");
        }
        List<State> ordered = new ArrayList<>(allStates.size());
        Set<State> seen = new HashSet<>();
        Deque<State> pending = new ArrayDeque<>();
        seen.add(initial);
        pending.add(initial);
        while (!pending.isEmpty()) {
            State s = pending.removeFirst();
            ordered.add(s);

            // Cache each label for this state's transitions so sorting cannot
            // trigger repeated parameter retrievals in the comparator.
            List<ActionLink> edges = new ArrayList<>();
            for (Transition t : s.getOutTransitions()) {
                if (!allowed.contains(t.getDestination())) {
                    throw new IllegalStateException(
                        "Incomplete explored graph: transition from " + s.getId()
                        + " reaches missing state " + t.getDestination().getId());
                }
                edges.add(new ActionLink(actionKey(t), t.getDestination()));
                canonicalOrderingTransitions++;
            }
            edges.sort(Comparator.comparing(ActionLink::key));
            String previousKey = null;
            State previousDestination = null;
            for (ActionLink edge : edges) {
                if (edge.key().equals(previousKey)
                    && !edge.destination().equals(previousDestination)) {
                    throw new IllegalStateException(
                        "Non-deterministic action " + edge.key()
                        + " at state " + s.getId()
                        + ": canonical ordering is not well-defined");
                }
                previousKey = edge.key();
                previousDestination = edge.destination();
                if (seen.add(edge.destination())) {
                    pending.addLast(edge.destination());
                }
            }
        }
        if (ordered.size() != allStates.size()) {
            throw new IllegalStateException(
                "Explored graph has " + allStates.size() + " states but only "
                + ordered.size() + " reachable from its initial state");
        }
        return ordered;
    }

    private static String actionKey(Transition t) {
        // The delimiter prevents collisions between the name and parameters.
        return t.getName() + "\u0000" + String.valueOf(t.getParameterValues());
    }

    private record ActionLink(String key, State destination) {}

    private double bellmanValue(State s, boolean recordQ) {
        List<Transition> actions = s.getOutTransitions();
        if (actions.isEmpty()) {
            if (recordQ) {
                qValues.remove(s);
            }
            return 0.0;
        }
        double maxQ = Double.NEGATIVE_INFINITY;
        Map<Transition, Double> qs = recordQ
            ? qValues.computeIfAbsent(s, ignored -> new HashMap<>()) : null;
        for (Transition action : actions) {
            State successor = action.getDestination();
            Double successorValue = vValues.get(successor);
            if (successorValue == null) {
                throw new IllegalStateException("Bellman backup uses an undiscovered successor state");
            }
            double reward = env.reward(s, action, successor);
            rewardLookups++;
            if (!Double.isFinite(reward)) {
                throw new IllegalStateException("Non-finite reward for state " + s.getId());
            }
            double q = reward + gamma * successorValue;
            if (!Double.isFinite(q)) {
                throw new IllegalStateException("Non-finite Q-value for state " + s.getId());
            }
            if (recordQ) {
                qs.put(action, q);
            }
            maxQ = Math.max(maxQ, q);
        }
        return maxQ;
    }

    private double computeGlobalBellmanResidual(List<State> states, boolean recordQ) {
        double maxResidual = 0.0;
        for (State s : states) {
            double backedUpV = bellmanValue(s, recordQ);
            residualBackups++;
            maxResidual = Math.max(maxResidual, Math.abs(backedUpV - vValues.get(s)));
        }
        return maxResidual;
    }

    private void reset() {
        status = TerminationStatus.NOT_STARTED;
        completedIterations = 0;
        updateBackups = 0;
        residualBackups = 0;
        rewardLookups = 0;
        distinctUpdatedStates = 0;
        exploredStates = 0;
        canonicalOrderingNanos = 0;
        canonicalOrderingTransitions = 0;
        lastBellmanResidual = Double.NaN;
        vValues.clear();
        qValues.clear();
        rng.setSeed(seed);
    }

    private void printSummary(long nanos) {
        System.out.printf(Locale.ROOT,
            "IVI_RESULT status=%s seed=%d gamma=%.9g theta=%.9g "
            + "maxIterations=%d updatesPerIteration=%d iterations=%d "
            + "exploredStates=%d distinctUpdatedStates=%d "
            + "updateBackups=%d residualBackups=%d totalBackups=%d "
            + "rewardLookups=%d bellmanResidual=%.9g orderingSeconds=%.6f learningSeconds=%.6f%n",
            status, seed, gamma, teta, maxIterations, updatesPerIteration,
            completedIterations, exploredStates, distinctUpdatedStates,
            updateBackups, residualBackups, getTotalBackups(), rewardLookups,
            lastBellmanResidual, canonicalOrderingNanos / 1_000_000_000.0,
            nanos / 1_000_000_000.0);
    }

    public TerminationStatus getStatus() { return status; }
    public int getCompletedIterations() { return completedIterations; }
    public long getUpdateBackups() { return updateBackups; }
    public long getResidualBackups() { return residualBackups; }
    public long getTotalBackups() { return updateBackups + residualBackups; }
    public long getRewardLookups() { return rewardLookups; }
    public int getDistinctUpdatedStates() { return distinctUpdatedStates; }
    public int getExploredStates() { return exploredStates; }
    public double getLastBellmanResidual() { return lastBellmanResidual; }
    public double getCanonicalOrderingSeconds() { return canonicalOrderingNanos / 1_000_000_000.0; }
    public long getSeed() { return seed; }
    public double getValue(State s) { return vValues.getOrDefault(s, 0.0); }

    @Override
    public Map<Transition, Double> getQValues(State s) {
        return Collections.unmodifiableMap(qValues.getOrDefault(s, Collections.emptyMap()));
    }
}
