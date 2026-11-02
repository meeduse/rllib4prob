package fr.polytech.mnia.exploration.concurrent;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.exploration.encoding.ActionLabelEncoder;
import fr.polytech.mnia.exploration.encoding.StateKeyEncoder;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;
import fr.polytech.mnia.exploration.prob.ProBWorkerSession;

/**
 * Step 6: bounded concurrent BFS with a deterministic edge-selection policy.
 *
 * Each worker has an independent private ProB session and a private executor
 * (the session is always used on the same Java thread). The replicated prefix
 * has cut depth d, with 0 <= d <= D. Exactly one prefix snapshot is globally
 * committed; current-level tasks expand concurrently and a coordinator BFS
 * barrier prevents next-level expansions until the entire level is complete.
 *
 * No work stealing and no integration with the current RL agents.
 */
public final class BoundedReachabilityExplorer {
    private final List<String> variables;
    private final int workerCount;
    private final int cutDepth;
    private final int maxDepth;
    private final int width;

    public record Result(ReachabilityGraph graph, Map<StateKey, Integer> levels,
                         long bootstrapNanos, long explorationNanos, long cleanupNanos,
                         int completedPrefixStates, int frontierStates,
                         List<Integer> workerExpansions, List<Long> workerPrefixNanos) {
        public Result {
            levels = Map.copyOf(levels);
            workerExpansions = List.copyOf(workerExpansions);
            workerPrefixNanos = List.copyOf(workerPrefixNanos);
        }
        public long totalNanos() {
            return bootstrapNanos + explorationNanos + cleanupNanos;
        }
    }

    private record Prefix(StateKey initial, Map<StateKey, Integer> levels,
                          Set<LabeledTransition> edges) {
        private Prefix {
            levels = Map.copyOf(levels);
            edges = Set.copyOf(edges);
        }
    }

    private record Selected(LabeledTransition edge, State destination) { }

    public BoundedReachabilityExplorer(List<String> stateVariables, int workerCount,
                                       int cutDepth, int maxDepth, int width) {
        Objects.requireNonNull(stateVariables, "stateVariables");
        new StateKeyEncoder(stateVariables);
        if (workerCount < 1 || cutDepth < 0 || maxDepth < 0 || cutDepth > maxDepth || width < 0) {
            throw new IllegalArgumentException("Require p >= 1, 0 <= d <= D, B >= 0");
        }
        this.variables = List.copyOf(stateVariables);
        this.workerCount = workerCount;
        this.cutDepth = cutDepth;
        this.maxDepth = maxDepth;
        this.width = width;
    }

    public Result explore(Path sourceRoot, Path relativeMachine) throws Exception {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(relativeMachine, "relativeMachine");
        long started = System.nanoTime();
        BoundedStateRegistry registry = new BoundedStateRegistry(workerCount, maxDepth);
        List<Worker> workers = new ArrayList<>(workerCount);
        List<ExecutorService> executors = new ArrayList<>(workerCount);
        ReachabilityGraph graph = null;
        Map<StateKey, Integer> levels = null;
        int completedPrefixStates = 0;
        int frontierStates = 0;
        long bootstrapEnded = started;
        long explorationEnded = started;
        long cleanupEnded;
        Throwable primaryFailure = null;

        try {
            for (int i = 0; i < workerCount; i++) {
                final int id = i;
                workers.add(new Worker(id, sourceRoot, relativeMachine, registry));
                executors.add(Executors.newSingleThreadExecutor(task ->
                        new Thread(task, "prob-bounded-worker-" + id)));
            }
            List<Future<Prefix>> copies = new ArrayList<>();
            for (int i = 0; i < workerCount; i++) {
                final Worker worker = workers.get(i);
                copies.add(executors.get(i).submit(worker::loadAndBuildPrefix));
            }
            Prefix baseline = copies.get(0).get();
            for (int i = 1; i < workerCount; i++) {
                Prefix other = copies.get(i).get();
                if (!baseline.equals(other)) {
                    throw new IllegalStateException("Worker " + i + " built a different bounded prefix");
                }
            }
            registry.initialize(baseline.initial(), baseline.levels(), baseline.edges(), cutDepth);
            completedPrefixStates = (int) baseline.levels().values().stream()
                    .filter(depth -> depth < cutDepth).count();
            frontierStates = (int) baseline.levels().values().stream()
                    .filter(depth -> depth == cutDepth).count();
            bootstrapEnded = System.nanoTime();

            // Level synchronization enforces shortest-depth semantics for G_{B,D}.
            for (int level = cutDepth; level < maxDepth; level++) {
                List<Future<?>> expansions = new ArrayList<>(workerCount);
                for (int i = 0; i < workerCount; i++) {
                    List<BoundedStateRegistry.Task> tasks = registry.tasksForLevel(level, i);
                    final Worker worker = workers.get(i);
                    expansions.add(executors.get(i).submit(() -> {
                        worker.expandLevel(tasks);
                        return null;
                    }));
                }
                for (Future<?> f : expansions) f.get();
                registry.finishLevel(level);
            }
            graph = registry.completedGraph();
            levels = registry.levels();
            int expanded = workers.stream().mapToInt(worker -> worker.expansions).sum();
            int expectedExpanded = (int) levels.values().stream().filter(n -> n < maxDepth).count();
            if (completedPrefixStates + expanded != expectedExpanded) {
                throw new IllegalStateException("Logical expansion count incorrect: prefix="
                        + completedPrefixStates + ", workers=" + expanded
                        + ", required=" + expectedExpanded);
            }
            explorationEnded = System.nanoTime();
        } catch (Exception | Error error) {
            primaryFailure = error;
            throw error;
        } finally {
            // Close on each worker's own thread, including when the run fails.
            Exception cleanupFailure = null;
            List<Future<?>> closing = new ArrayList<>();
            for (int i = 0; i < executors.size(); i++) {
                final Worker worker = workers.get(i);
                try {
                    closing.add(executors.get(i).submit(() -> {
                        worker.close();
                        return null;
                    }));
                } catch (RuntimeException error) {
                    cleanupFailure = accumulate(cleanupFailure, error);
                }
            }
            for (Future<?> f : closing) {
                try {
                    f.get(120, TimeUnit.SECONDS);
                } catch (Exception error) {
                    cleanupFailure = accumulate(cleanupFailure, error);
                }
            }
            for (ExecutorService executor : executors) executor.shutdown();
            for (ExecutorService executor : executors) {
                try {
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                        cleanupFailure = accumulate(cleanupFailure,
                                new IllegalStateException("ProB worker thread did not terminate"));
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                    cleanupFailure = accumulate(cleanupFailure, error);
                }
            }
            cleanupEnded = System.nanoTime();
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
        return new Result(graph, levels, bootstrapEnded - started,
                explorationEnded - bootstrapEnded, cleanupEnded - explorationEnded,
                completedPrefixStates, frontierStates,
                workers.stream().map(worker -> worker.expansions).toList(),
                workers.stream().map(worker -> worker.prefixNanos).toList());
    }

    private static Exception accumulate(Exception previous, Exception error) {
        if (previous == null) return error;
        previous.addSuppressed(error);
        return previous;
    }

    private final class Worker {
        private final int id;
        private final Path sourceRoot;
        private final Path relativeMachine;
        private final BoundedStateRegistry registry;
        private final StateKeyEncoder stateKeys = new StateKeyEncoder(variables);
        private final ActionLabelEncoder actionLabels = new ActionLabelEncoder();
        private final Map<String, StateKey> byLocalId = new HashMap<>();
        private final Map<StateKey, State> localStates = new HashMap<>();
        private ProBWorkerSession session;
        private long prefixNanos;
        private int expansions;

        private Worker(int id, Path sourceRoot, Path relativeMachine, BoundedStateRegistry registry) {
            this.id = id;
            this.sourceRoot = sourceRoot;
            this.relativeMachine = relativeMachine;
            this.registry = registry;
        }

        private Prefix loadAndBuildPrefix() throws Exception {
            session = ProBWorkerSession.open(sourceRoot, relativeMachine, false);
            long start = System.nanoTime();
            Prefix prefix = materializePrefix();
            prefixNanos = System.nanoTime() - start;
            return prefix;
        }

        private Prefix materializePrefix() {
            State initialState = session.initialState();
            StateKey initial = key(initialState);
            Map<StateKey, Integer> levels = new HashMap<>();
            Set<LabeledTransition> edges = new HashSet<>();
            ArrayDeque<State> queue = new ArrayDeque<>();
            levels.put(initial, 0);
            localStates.put(initial, initialState);
            queue.addLast(initialState);

            while (!queue.isEmpty()) {
                State state = queue.removeFirst();
                StateKey source = key(state);
                int level = levels.get(source);
                if (level >= cutDepth) continue; // frontier materialized, never expanded
                for (Selected selected : select(state, source)) {
                    LabeledTransition edge = selected.edge();
                    StateKey destination = edge.destination();
                    localStates.putIfAbsent(destination, selected.destination());
                    edges.add(edge);
                    if (levels.putIfAbsent(destination, level + 1) == null) {
                        queue.addLast(selected.destination());
                    }
                }
            }
            return new Prefix(initial, levels, edges);
        }

        private void expandLevel(List<BoundedStateRegistry.Task> tasks) {
            for (BoundedStateRegistry.Task task : tasks) {
                State source = localStates.get(task.key());
                if (source == null) {
                    throw new IllegalStateException("Worker " + id + " lacks local state " + task.key());
                }
                registry.start(task);
                for (Selected selected : select(source, task.key())) {
                    LabeledTransition edge = selected.edge();
                    localStates.putIfAbsent(edge.destination(), selected.destination());
                    registry.registerSuccessor(edge.destination(), task.level() + 1, id);
                    registry.recordEdge(edge); // even for an already registered destination
                }
                registry.complete(task);
                expansions++;
            }
        }

        private List<Selected> select(State source, StateKey sourceKey) {
            if (width == 0) return List.of();
            Map<LabeledTransition, State> candidates = new HashMap<>();
            for (Transition transition : source.getOutTransitions()) {
                State destination = transition.getDestination();
                StateKey destinationKey = key(destination);
                LabeledTransition edge = new LabeledTransition(sourceKey,
                        actionLabels.label(transition), destinationKey);
                candidates.putIfAbsent(edge, destination);
            }
            return DeterministicTransitionPolicy.select(candidates.keySet(), width).stream()
                    .map(edge -> new Selected(edge, candidates.get(edge))).toList();
        }

        private StateKey key(State state) {
            return byLocalId.computeIfAbsent(state.getId(), ignored -> stateKeys.key(state));
        }

        private void close() throws Exception {
            if (session != null) {
                try {
                    session.close();
                } finally {
                    session = null;
                }
            }
        }
    }
}
