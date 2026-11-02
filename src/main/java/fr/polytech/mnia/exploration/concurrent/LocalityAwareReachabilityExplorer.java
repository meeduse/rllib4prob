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
 * Step 7: complete exploration with optional locality-aware reassignment.
 *
 * Task ownership is distinct from ProB-local state materialization. Only
 * PENDING tasks can move; stale queue tokens carry an obsolete generation.
 * The coordinator snapshots the finished canonical graph BEFORE waiting for
 * ProB cleanup, so graphReadyNanos is a true end-to-graph wall-clock metric.
 * No existing exploration class is modified.
 */
public final class LocalityAwareReachabilityExplorer {
    private static final long BOOTSTRAP_TIMEOUT_SECONDS = 120;
    private static final long WORK_POLL_MS = 10;

    private final List<String> variables;
    private final int workerCount;
    private final int cutDepth;
    private final boolean reassignmentEnabled;

    public record Result(ReachabilityGraph graph,
                         long bootstrapNanos, long explorationNanos, long cleanupNanos,
                         int completedPrefixStates, int frontierStates,
                         List<Integer> workerExpansions, List<Long> workerPrefixNanos,
                         long reassignmentAttempts, long successfulReassignments,
                         long gatewayRegenerations) {
        public Result {
            workerExpansions = List.copyOf(workerExpansions);
            workerPrefixNanos = List.copyOf(workerPrefixNanos);
        }

        /** Elapsed wall-clock time until the complete canonical graph is available. */
        public long graphReadyNanos() { return bootstrapNanos + explorationNanos; }

        /** Includes worker shutdown and ProB workspace cleanup. */
        public long endToEndNanos() { return graphReadyNanos() + cleanupNanos; }
    }

    public LocalityAwareReachabilityExplorer(List<String> stateVariables,
                                             int workerCount, int cutDepth,
                                             boolean reassignmentEnabled) {
        this.variables = List.copyOf(stateVariables);
        new StateKeyEncoder(this.variables);
        if (workerCount < 1 || cutDepth < 1) {
            throw new IllegalArgumentException("workerCount >= 1 and cutDepth >= 1 required");
        }
        this.workerCount = workerCount;
        this.cutDepth = cutDepth;
        this.reassignmentEnabled = reassignmentEnabled;
    }

    public Result explore(Path sourceRoot, Path relativeMachine) throws Exception {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(relativeMachine, "relativeMachine");
        long startedAt = System.nanoTime();
        StealableStateRegistry registry = new StealableStateRegistry(workerCount);
        CountDownLatch releaseFrontier = new CountDownLatch(1);
        AtomicInteger threadSequence = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable ->
                new Thread(runnable, "prob-locality-worker-" + threadSequence.getAndIncrement()));
        List<Worker> workers = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        boolean finished = false;
        try {
            for (int id = 0; id < workerCount; id++) {
                workers.add(new Worker(id, sourceRoot, relativeMachine, registry, releaseFrontier));
            }
            for (Worker worker : workers) {
                futures.add(pool.submit(worker::run));
            }
            ReplicatedPrefix baseline = workers.get(0).ready.get(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (int id = 1; id < workers.size(); id++) {
                ReplicatedPrefix candidate = workers.get(id).ready.get(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!baseline.equals(candidate)) {
                    throw new IllegalStateException("Worker " + id + " produced a different prefix");
                }
            }
            registry.commitPrefix(baseline, cutDepth);
            long bootstrapDone = System.nanoTime();
            releaseFrontier.countDown();

            // This barrier signals that all required edges and registrations
            // have been published, not that worker sessions have been closed.
            registry.awaitCompletion();
            ReachabilityGraph graph = registry.completedGraph(baseline.initial());
            long graphReady = System.nanoTime();
            for (Future<?> future : futures) {
                future.get(); // Includes ProB session cleanup; failures invalidate the run.
            }
            long allClosed = System.nanoTime();
            // Recheck for a cleanup failure racing with the first graph snapshot.
            registry.completedGraph(baseline.initial());
            int completedPrefix = baseline.completedPrefixStates(cutDepth);
            int expanded = workers.stream().mapToInt(w -> w.expansions).sum();
            if (completedPrefix + expanded != graph.states().size()) {
                throw new IllegalStateException("Duplicate or missing logical expansions: prefix="
                        + completedPrefix + ", workers=" + expanded + ", graph=" + graph.states().size());
            }
            finished = true;
            return new Result(graph, bootstrapDone - startedAt,
                    graphReady - bootstrapDone, allClosed - graphReady,
                    completedPrefix, baseline.frontierStates(cutDepth),
                    workers.stream().map(w -> w.expansions).toList(),
                    workers.stream().map(w -> w.prefixNanos).toList(),
                    registry.attempts(), registry.successes(),
                    workers.stream().mapToLong(w -> w.gatewayRegenerations).sum());
        } catch (Exception | Error error) {
            registry.abort(error);
            throw error;
        } finally {
            releaseFrontier.countDown();
            if (!finished) {
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
        private final StealableStateRegistry registry;
        private final CountDownLatch releaseFrontier;
        private final StateKeyEncoder stateKeys = new StateKeyEncoder(variables);
        private final ActionLabelEncoder actionLabels = new ActionLabelEncoder();
        private final Map<String, StateKey> byLocalId = new HashMap<>();
        private final Map<StateKey, State> localStates = new HashMap<>();
        private final CompletableFuture<ReplicatedPrefix> ready = new CompletableFuture<>();
        private volatile long prefixNanos;
        private volatile int expansions;
        private volatile long gatewayRegenerations;

        Worker(int id, Path sourceRoot, Path relativeMachine,
               StealableStateRegistry registry, CountDownLatch releaseFrontier) {
            this.id = id;
            this.sourceRoot = sourceRoot;
            this.relativeMachine = relativeMachine;
            this.registry = registry;
            this.releaseFrontier = releaseFrontier;
        }

        void run() {
            try (ProBWorkerSession session = ProBWorkerSession.open(sourceRoot, relativeMachine, false)) {
                long prefixStart = System.nanoTime();
                ReplicatedPrefix prefix = materializePrefix(session);
                prefixNanos = System.nanoTime() - prefixStart;
                ready.complete(prefix);
                releaseFrontier.await();
                while (true) {
                    StealableStateRegistry.WorkItem task = registry.poll(id, WORK_POLL_MS);
                    if (task != null) {
                        if (task.stop()) {
                            break;
                        }
                        if (!registry.start(task, id)) {
                            continue; // Stale owner/generation or already active.
                        }
                        State source = localStates.get(task.key());
                        if (source == null) {
                            throw new IllegalStateException("Worker " + id
                                    + " lacks locally materialized state " + task.key());
                        }
                        expansions++;
                        for (Transition transition : source.getOutTransitions()) {
                            State destination = transition.getDestination();
                            StateKey destinationKey = key(destination);
                            localStates.putIfAbsent(destinationKey, destination);
                            registry.registerPending(destinationKey, id);
                            registry.recordEdge(new LabeledTransition(
                                    task.key(), actionLabels.label(transition), destinationKey));
                        }
                        registry.complete(task, id);
                    } else if (reassignmentEnabled) {
                        tryReassignOne();
                    }
                }
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

        private void tryReassignOne() {
            for (StealableStateRegistry.Candidate candidate : registry.pendingFor(id)) {
                LocalStateGateway.Acquisition available =
                        LocalStateGateway.acquire(candidate, localStates, this::key);
                gatewayRegenerations += available.predecessorExpansions();
                if (available.available() && registry.tryReassign(candidate, id)) {
                    return; // Do not take further tasks until this one is processed.
                }
            }
        }

        /** Local BFS through depth d, never published by this worker. */
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
                    continue;
                }
                for (Transition transition : source.getOutTransitions()) {
                    State destination = transition.getDestination();
                    StateKey destinationKey = key(destination);
                    localStates.putIfAbsent(destinationKey, destination);
                    edges.add(new LabeledTransition(sourceKey, actionLabels.label(transition), destinationKey));
                    if (levels.putIfAbsent(destinationKey, level + 1) == null) {
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
