package fr.polytech.mnia.exploration.concurrent;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.exploration.encoding.ActionLabelEncoder;
import fr.polytech.mnia.exploration.encoding.StateKeyEncoder;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;
import fr.polytech.mnia.exploration.prob.ProBWorkerSession;

/**
 * Step 5: complete graph exploration using a replicated BFS prefix of depth d.
 *
 * Each of p independent ProB sessions materializes the same prefix, but only
 * one canonical snapshot is committed to the global registry. Frontier nodes
 * are distributed round-robin; later discoveries remain with their discovering
 * worker. No work stealing, bounded mode, or RL integration is implemented.
 *
 * This is an ADDITIONAL explorer. The step-4 implementation is unchanged.
 */
public final class PrefixReplicatedReachabilityExplorer {
    private static final long BOOTSTRAP_TIMEOUT_SECONDS = 120;
    private final List<String> variables;
    private final int workerCount;
    private final int cutDepth;

    public record Result(ReachabilityGraph graph,
                         long bootstrapNanos, long explorationNanos, long cleanupNanos,
                         int completedPrefixStates, int frontierStates,
                         List<Integer> workerExpansions, List<Long> workerPrefixNanos) {
        public Result {
            workerExpansions = List.copyOf(workerExpansions);
            workerPrefixNanos = List.copyOf(workerPrefixNanos);
        }
        public long totalNanos() {
            return bootstrapNanos + explorationNanos + cleanupNanos;
        }
    }

    /** Cut depth is at least 1 for compatibility with the unmodified step-4 registry. */
    public PrefixReplicatedReachabilityExplorer(List<String> stateVariables,
                                                 int workerCount, int cutDepth) {
        this.variables = List.copyOf(stateVariables);
        new StateKeyEncoder(this.variables);
        if (workerCount < 1 || cutDepth < 1) {
            throw new IllegalArgumentException("workerCount >= 1 and cutDepth >= 1 required");
        }
        this.workerCount = workerCount;
        this.cutDepth = cutDepth;
    }

    public Result explore(Path sourceRoot, Path relativeMachine) throws Exception {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(relativeMachine, "relativeMachine");
        long started = System.nanoTime();
        ConcurrentStateRegistry registry = new ConcurrentStateRegistry(workerCount);
        CountDownLatch releaseFrontier = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, task ->
                new Thread(task, "prob-replicated-prefix-" + threadNumber.getAndIncrement()));
        List<Worker> workers = new ArrayList<>(workerCount);
        List<Future<?>> futures = new ArrayList<>(workerCount);
        boolean completed = false;
        try {
            for (int i = 0; i < workerCount; i++) {
                workers.add(new Worker(i, sourceRoot, relativeMachine, registry, releaseFrontier));
            }
            for (Worker worker : workers) {
                futures.add(pool.submit(worker::run));
            }
            // Await all local copies before publishing the shared prefix.
            ReplicatedPrefix baseline = workers.get(0).ready.get(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (int i = 1; i < workers.size(); i++) {
                ReplicatedPrefix candidate = workers.get(i).ready.get(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!baseline.equals(candidate)) {
                    throw new IllegalStateException("Worker " + i + " replicated a different canonical prefix");
                }
            }
            // Only the coordinator commits the prefix; the workers are still paused.
            baseline.commitInto(registry, cutDepth, workerCount);
            long bootstrapped = System.nanoTime();
            releaseFrontier.countDown();

            for (Future<?> future : futures) {
                future.get();
            }
            long closed = System.nanoTime();
            long expansionEnded = workers.stream().mapToLong(w -> w.expansionFinishedNanos)
                    .max().orElseThrow();
            ReachabilityGraph graph = registry.completedGraph(baseline.initial());
            int completedPrefix = baseline.completedPrefixStates(cutDepth);
            int expandedAfterPrefix = workers.stream().mapToInt(w -> w.expansions).sum();
            if (completedPrefix + expandedAfterPrefix != graph.states().size()) {
                throw new IllegalStateException("Missing or duplicate logical expansions: prefix="
                        + completedPrefix + ", workers=" + expandedAfterPrefix
                        + ", states=" + graph.states().size());
            }
            completed = true;
            return new Result(graph, bootstrapped - started,
                    Math.max(0, expansionEnded - bootstrapped),
                    Math.max(0, closed - expansionEnded),
                    completedPrefix, baseline.frontierStates(cutDepth),
                    workers.stream().map(w -> w.expansions).toList(),
                    workers.stream().map(w -> w.prefixNanos).toList());
        } catch (Exception | Error error) {
            registry.abort(error);
            throw error;
        } finally {
            releaseFrontier.countDown();
            if (!completed) {
                for (Future<?> future : futures) {
                    future.cancel(true);
                }
            }
            pool.shutdownNow();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("ProB workers did not terminate; exploration invalid");
            }
        }
    }

    private final class Worker {
        private final int id;
        private final Path sourceRoot;
        private final Path relativeMachine;
        private final ConcurrentStateRegistry registry;
        private final CountDownLatch releaseFrontier;
        private final StateKeyEncoder stateKeys = new StateKeyEncoder(variables);
        private final ActionLabelEncoder actionLabels = new ActionLabelEncoder();
        private final Map<String, StateKey> byLocalId = new HashMap<>();
        private final Map<StateKey, State> localStates = new HashMap<>();
        private final CompletableFuture<ReplicatedPrefix> ready = new CompletableFuture<>();
        private volatile long prefixNanos;
        private volatile long expansionFinishedNanos;
        private volatile int expansions;

        private Worker(int id, Path sourceRoot, Path relativeMachine,
                       ConcurrentStateRegistry registry, CountDownLatch releaseFrontier) {
            this.id = id;
            this.sourceRoot = sourceRoot;
            this.relativeMachine = relativeMachine;
            this.registry = registry;
            this.releaseFrontier = releaseFrontier;
        }

        private void run() {
            try (ProBWorkerSession session = ProBWorkerSession.open(sourceRoot, relativeMachine, false)) {
                long prefixStarted = System.nanoTime();
                ready.complete(materializePrefix(session));
                prefixNanos = System.nanoTime() - prefixStarted;
                releaseFrontier.await();
                while (true) {
                    ConcurrentStateRegistry.WorkItem item = registry.take(id);
                    if (item.isStop()) {
                        break;
                    }
                    StateKey key = item.key();
                    State source = localStates.get(key);
                    if (source == null) {
                        throw new IllegalStateException("Worker " + id + " lacks local state " + key);
                    }
                    registry.start(key, id);
                    expansions++;
                    for (Transition transition : source.getOutTransitions()) {
                        State destination = transition.getDestination();
                        StateKey dest = key(destination);
                        localStates.putIfAbsent(dest, destination);
                        registry.registerPending(dest, id);
                        registry.recordEdge(new LabeledTransition(key, actionLabels.label(transition), dest));
                    }
                    registry.complete(key, id);
                }
                expansionFinishedNanos = System.nanoTime();
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
                registry.abort(failure);
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure instanceof RuntimeException error) {
                    throw error;
                }
                throw new IllegalStateException("Worker " + id + " failed", failure);
            }
        }

        /** Full BFS prefix, deduplicated by semantic key, with complete outgoing edges for depth < d. */
        private ReplicatedPrefix materializePrefix(ProBWorkerSession session) {
            State initialState = session.initialState();
            StateKey initial = key(initialState);
            Map<StateKey, Integer> levels = new HashMap<>();
            Set<LabeledTransition> edges = new HashSet<>();
            ArrayDeque<State> queue = new ArrayDeque<>();
            levels.put(initial, 0);
            localStates.put(initial, initialState);
            queue.addLast(initialState);

            while (!queue.isEmpty()) {
                State source = queue.removeFirst();
                StateKey sourceKey = key(source);
                int level = levels.get(sourceKey);
                if (level == cutDepth) {
                    continue; // The frontier is locally materialized, not expanded.
                }
                for (Transition transition : source.getOutTransitions()) {
                    State destination = transition.getDestination();
                    StateKey dest = key(destination);
                    localStates.putIfAbsent(dest, destination);
                    edges.add(new LabeledTransition(sourceKey, actionLabels.label(transition), dest));
                    if (levels.putIfAbsent(dest, level + 1) == null) {
                        queue.addLast(destination);
                    }
                }
            }
            return new ReplicatedPrefix(initial, levels, edges);
        }

        private StateKey key(State state) {
            return byLocalId.computeIfAbsent(state.getId(), ignored -> stateKeys.key(state));
        }
    }
}
