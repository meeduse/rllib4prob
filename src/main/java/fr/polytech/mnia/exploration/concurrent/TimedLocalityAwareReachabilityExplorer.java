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
 * Step 8: instrumented variant of the validated step-7 complete explorer.
 *
 * ADDITIVE: code path is intentionally the same as step 7, with only load-phase
 * barrier and timestamps added. Its graphs must be checked against the unmodified
 * explorer. Not a second exploration algorithm.
 *
 * loadWallNanos: coordinator start until ALL worker ProB sessions loaded.
 * prefixNanos: release of load barrier until prefix committed globally.
 * explorationNanos: prefix committed until canonical graph snapshot available.
 * cleanupNanos: graph available until all worker sessions are closed.
 *
 * Task ownership is distinct from ProB-local state materialization. Only
 * PENDING tasks can move; stale queue tokens carry an obsolete generation.
 * The coordinator snapshots the finished canonical graph BEFORE waiting for
 * ProB cleanup, so graphReadyNanos is a true end-to-graph wall-clock metric.
 * No existing exploration class is modified.
 */
public final class TimedLocalityAwareReachabilityExplorer {
    private static final long BOOTSTRAP_TIMEOUT_SECONDS = 120;
    private static final long WORK_POLL_MS = 10;

    private final List<String> variables;
    private final int workerCount;
    private final int cutDepth;
    private final boolean reassignmentEnabled;

    public record Result(ReachabilityGraph graph,
                         long loadWallNanos, long prefixNanos,
                         long explorationNanos, long cleanupNanos,
                         int completedPrefixStates, int frontierStates,
                         List<Integer> workerExpansions,
                         List<Long> workerLoadNanos, List<Long> workerPrefixNanos,
                         long reassignmentAttempts, long successfulReassignments,
                         long gatewayRegenerations) {
        public Result {
            workerExpansions = List.copyOf(workerExpansions);
            workerLoadNanos = List.copyOf(workerLoadNanos);
            workerPrefixNanos = List.copyOf(workerPrefixNanos);
        }

        /** Parallel wall-clock latency, not the sum of individual workers. */
        public long graphReadyNanos() {
            return loadWallNanos + prefixNanos + explorationNanos;
        }

        public long endToEndNanos() { return graphReadyNanos() + cleanupNanos; }
    }

    public TimedLocalityAwareReachabilityExplorer(List<String> stateVariables,
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
        CountDownLatch releaseLoaded = new CountDownLatch(1);
        CountDownLatch releaseFrontier = new CountDownLatch(1);
        AtomicInteger threadSequence = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable ->
                new Thread(runnable, "prob-locality-worker-" + threadSequence.getAndIncrement()));
        List<Worker> workers = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        boolean finished = false;
        try {
            for (int id = 0; id < workerCount; id++) {
                workers.add(new Worker(id, sourceRoot, relativeMachine, registry, releaseLoaded, releaseFrontier));
            }
            for (Worker worker : workers) {
                futures.add(pool.submit(worker::run));
            }
            // The independent worker loads run concurrently. Start the replicated
            // prefix ONLY after every instance has finished ProB loading.
            for (Worker worker : workers) {
                worker.loaded.get(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            long allLoaded = System.nanoTime();
            releaseLoaded.countDown();
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
            return new Result(graph,
                    allLoaded - startedAt, bootstrapDone - allLoaded,
                    graphReady - bootstrapDone, allClosed - graphReady,
                    completedPrefix, baseline.frontierStates(cutDepth),
                    workers.stream().map(w -> w.expansions).toList(),
                    workers.stream().map(w -> w.loadNanos).toList(),
                    workers.stream().map(w -> w.prefixNanos).toList(),
                    registry.attempts(), registry.successes(),
                    workers.stream().mapToLong(w -> w.gatewayRegenerations).sum());
        } catch (Exception | Error error) {
            registry.abort(error);
            throw error;
        } finally {
            releaseLoaded.countDown();
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
        private final CountDownLatch releaseLoaded;
        private final CountDownLatch releaseFrontier;
        private final StateKeyEncoder stateKeys = new StateKeyEncoder(variables);
        private final ActionLabelEncoder actionLabels = new ActionLabelEncoder();
        private final Map<String, StateKey> byLocalId = new HashMap<>();
        private final Map<StateKey, State> localStates = new HashMap<>();
        private final CompletableFuture<Long> loaded = new CompletableFuture<>();
        private final CompletableFuture<ReplicatedPrefix> ready = new CompletableFuture<>();
        private volatile long loadNanos;
        private volatile long prefixNanos;
        private volatile int expansions;
        private volatile long gatewayRegenerations;

        Worker(int id, Path sourceRoot, Path relativeMachine,
               StealableStateRegistry registry, CountDownLatch releaseLoaded,
               CountDownLatch releaseFrontier) {
            this.id = id;
            this.sourceRoot = sourceRoot;
            this.relativeMachine = relativeMachine;
            this.registry = registry;
            this.releaseLoaded = releaseLoaded;
            this.releaseFrontier = releaseFrontier;
        }

        void run() {
            long loadStarted = System.nanoTime();
            try (ProBWorkerSession session = ProBWorkerSession.open(sourceRoot, relativeMachine, false)) {
                loadNanos = System.nanoTime() - loadStarted;
                loaded.complete(loadNanos);
                releaseLoaded.await();
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
                loaded.completeExceptionally(failure);
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
