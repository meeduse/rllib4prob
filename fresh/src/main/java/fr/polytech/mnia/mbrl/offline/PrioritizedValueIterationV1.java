package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Prioritized (asynchronous) Value Iteration over the explored ProB graph.
 *
 * This algorithm performs one Bellman update on the state with the highest
 * currently known residual and updates its predecessors' priorities. A global
 * Bellman optimality residual is checked before reporting convergence.
 *
 * The numerical ProB identifiers only break ties between equal priorities.
 * They are NOT a semantic canonicalization of states across ProB runs.
 */
public class PrioritizedValueIterationV1 extends Agent {

    public enum TerminationStatus {
        NOT_STARTED, CONVERGED, UPDATE_LIMIT_REACHED, NO_STATES
    }

    private final int maxUpdates;
    private final Map<State, Double> vValues = new HashMap<>();
    private final Map<State, Map<Transition, Double>> qValues = new HashMap<>();
    private final Map<State, List<Transition>> outgoing = new HashMap<>();
    private final Map<State, Set<State>> predecessors = new HashMap<>();
    private final Map<State, Integer> ranks = new HashMap<>();
    private final TreeSet<StatePriority> queue = new TreeSet<>(
            Comparator.comparingDouble((StatePriority x) -> x.priority).reversed()
                      .thenComparingInt(x -> x.rank));
    private final Map<State, StatePriority> scheduled = new HashMap<>();

    private TerminationStatus status = TerminationStatus.NOT_STARTED;
    private double lastBellmanResidual = Double.NaN;
    private long priorityBackups, residualBackups, finalQBackups;
    private long rewardLookups, updates, graphTransitions;
    private int exploredStates;

    public PrioritizedValueIterationV1(Environment env, double gamma,
                                       double teta, int maxUpdates) {
        super(env, gamma, teta);
        if (!Double.isFinite(gamma) || gamma < 0.0 || gamma >= 1.0
                || !Double.isFinite(teta) || teta < 0.0 || maxUpdates < 0) {
            throw new IllegalArgumentException(
                    "PVI requires 0 <= gamma < 1, finite theta >= 0, maxUpdates >= 0");
        }
        this.maxUpdates = maxUpdates;
    }

    @Override
    public void learn(ExplorationStrategy strategy) {
        if (strategy == null || strategy == ExplorationStrategy.NONE) {
            throw new IllegalArgumentException("PVI requires exhaustive exploration (PREPROCESS or RECURSIVE)");
        }
        reset();
        env.explore(strategy);
        System.out.println("Preparing graph and initial priorities (PVI). First pass may take time...");
        System.out.flush();
        long started = System.nanoTime();

        List<Integer> ids = new ArrayList<>(env.getStateIds());
        Collections.sort(ids);
        List<State> states = new ArrayList<>(ids.size());
        for (int id : ids) {
            State state = env.gState(id);
            if (state == null) {
                throw new IllegalStateException("ProB did not return explored state " + id);
            }
            if (vValues.put(state, 0.0) != null) {
                throw new IllegalStateException("Duplicate state in exploration: " + id);
            }
            ranks.put(state, states.size());
            states.add(state);
        }
        exploredStates = states.size();
        if (states.isEmpty()) {
            status = TerminationStatus.NO_STATES;
            printResult(started);
            return;
        }
        buildGraph(states);

        for (State s : states) {
            scheduleByCurrentError(s);
        }

        while (true) {
            if (queue.isEmpty() || updates >= maxUpdates) {
                // Empty queue is NOT itself evidence of convergence.
                lastBellmanResidual = globalBellmanResidual(states, queue.isEmpty() && updates < maxUpdates);
                if (lastBellmanResidual <= teta) {
                    status = TerminationStatus.CONVERGED;
                    break;
                }
                if (updates >= maxUpdates) {
                    status = TerminationStatus.UPDATE_LIMIT_REACHED;
                    break;
                }
                // A queue exhausted with positive residual indicates stale priorities.
                // Rebuild them from the global residual scan before continuing.
                if (queue.isEmpty()) {
                    throw new IllegalStateException(
                            "Nonzero Bellman residual but no eligible state in priority queue: "
                                    + lastBellmanResidual);
                }
            }

            StatePriority current = queue.pollFirst();
            scheduled.remove(current.state);
            State s = current.state;
            double target = bellmanTarget(s, Counter.PRIORITY);
            double error = Math.abs(vValues.get(s) - target);
            if (error <= teta) {
                continue; // Stale or already sufficiently accurate priority
            }
            vValues.put(s, target);
            updates++;
            if (updates % 1000 == 0) {
                System.out.printf(Locale.ROOT, "PVI updates=%d pending=%d last_error=%.9g%n",
                        updates, queue.size(), error);
                System.out.flush();
            }
            for (State predecessor : predecessors.getOrDefault(s, Collections.emptySet())) {
                scheduleByCurrentError(predecessor);
            }
        }

        refreshFinalQ(states);
        printResult(started);
    }

    private void reset() {
        vValues.clear();
        qValues.clear();
        outgoing.clear();
        predecessors.clear();
        ranks.clear();
        queue.clear();
        scheduled.clear();
        status = TerminationStatus.NOT_STARTED;
        lastBellmanResidual = Double.NaN;
        priorityBackups = residualBackups = finalQBackups = 0;
        rewardLookups = updates = graphTransitions = 0;
        exploredStates = 0;
    }

    private void buildGraph(List<State> states) {
        Set<State> known = new HashSet<>(states);
        for (State s : states) {
            List<Transition> transitions = new ArrayList<>(s.getOutTransitions());
            outgoing.put(s, transitions);
            graphTransitions += transitions.size();
            for (Transition t : transitions) {
                State destination = t.getDestination();
                if (!known.contains(destination)) {
                    throw new IllegalStateException("Exploration omitted a successor state of " + s.getId());
                }
                predecessors.computeIfAbsent(destination, ignored -> new LinkedHashSet<>()).add(s);
            }
        }
    }

    private enum Counter { PRIORITY, RESIDUAL }

    private double bellmanTarget(State s, Counter category) {
        if (category == Counter.PRIORITY) priorityBackups++;
        else residualBackups++;
        List<Transition> actions = outgoing.get(s);
        if (actions == null) throw new IllegalStateException("Unknown state in priority evaluation");
        if (actions.isEmpty()) return 0.0;
        double best = Double.NEGATIVE_INFINITY;
        for (Transition transition : actions) {
            double r = env.reward(s, transition, transition.getDestination());
            rewardLookups++;
            double q = r + gamma * vValues.get(transition.getDestination());
            if (!Double.isFinite(q)) throw new IllegalStateException("Nonfinite Bellman target at state " + s.getId());
            best = Math.max(best, q);
        }
        return best;
    }

    private void scheduleByCurrentError(State s) {
        double error = Math.abs(vValues.get(s) - bellmanTarget(s, Counter.PRIORITY));
        StatePriority old = scheduled.remove(s);
        if (old != null) queue.remove(old);
        if (error > teta) {
            StatePriority entry = new StatePriority(s, error, ranks.get(s));
            queue.add(entry);
            scheduled.put(s, entry);
        }
    }

    private double globalBellmanResidual(List<State> states, boolean requeue) {
        double max = 0.0;
        for (State s : states) {
            double error = Math.abs(vValues.get(s) - bellmanTarget(s, Counter.RESIDUAL));
            max = Math.max(max, error);
            if (requeue && error > teta) {
                StatePriority old = scheduled.remove(s);
                if (old != null) queue.remove(old);
                StatePriority entry = new StatePriority(s, error, ranks.get(s));
                queue.add(entry);
                scheduled.put(s, entry);
            }
        }
        return max;
    }

    private void refreshFinalQ(List<State> states) {
        for (State s : states) {
            finalQBackups++;
            Map<Transition, Double> qs = new HashMap<>();
            for (Transition t : outgoing.get(s)) {
                double reward = env.reward(s, t, t.getDestination());
                rewardLookups++;
                double q = reward + gamma * vValues.get(t.getDestination());
                if (!Double.isFinite(q)) throw new IllegalStateException("Nonfinite final Q at " + s.getId());
                qs.put(t, q);
            }
            qValues.put(s, qs);
        }
    }

    private void printResult(long started) {
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
                "PVI_RESULT status=%s gamma=%.9g theta=%.9g maxUpdates=%d updates=%d " +
                "exploredStates=%d transitions=%d priorityBackups=%d residualBackups=%d " +
                "finalQBackups=%d totalBackups=%d rewardLookups=%d bellmanResidual=%.9g learningSeconds=%.6f%n",
                status, gamma, teta, maxUpdates, updates,
                exploredStates, graphTransitions, priorityBackups, residualBackups,
                finalQBackups, priorityBackups + residualBackups + finalQBackups,
                rewardLookups, lastBellmanResidual, seconds);
        System.out.flush();
    }

    @Override
    public Map<Transition, Double> getQValues(State s) {
        return qValues.getOrDefault(s, Collections.emptyMap());
    }

    public double getValue(State s) {
        return vValues.getOrDefault(s, 0.0);
    }

    public TerminationStatus getStatus() { return status; }
    public long getUpdates() { return updates; }
    public double getLastBellmanResidual() { return lastBellmanResidual; }
    public long getTotalBackups() { return priorityBackups + residualBackups + finalQBackups; }
    public long getRewardLookups() { return rewardLookups; }

    private static final class StatePriority {
        private final State state;
        private final double priority;
        private final int rank;
        private StatePriority(State state, double priority, int rank) {
            this.state = state;
            this.priority = priority;
            this.rank = rank;
        }
    }
}
