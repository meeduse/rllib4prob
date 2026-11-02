package fr.polytech.mnia.exploration.concurrent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Step 4: minimal COMPLETE exploration with exactly two independent ProB workers.
 *
 * Each worker loads a private B-machine copy and locally materializes the depth-1
 * prefix. One globally committed initial expansion seeds distinct frontier tasks.
 * Only a canonical state key is queued; ProB State objects remain worker-local.
 * Newly discovered states remain owned by their discovering worker. There is no
 * task stealing, configurable prefix, bounded traversal, or RL integration yet.
 */
public final class ConcurrentReachabilityExplorer {
    private static final int WORKER_COUNT = 2;
    private static final long BOOTSTRAP_TIMEOUT_SECONDS = 120;
    private final List<String> stateVariables;

    public record Result(ReachabilityGraph graph, long bootstrapNanos,
                         long explorationNanos, long cleanupNanos,
                         List<Integer> workerExpansions) {
        public Result {
            workerExpansions = List.copyOf(workerExpansions);
        }
        public long totalNanos() {
            return bootstrapNanos + explorationNanos + cleanupNanos;
        }
    }

    public ConcurrentReachabilityExplorer(List<String> allStateVariables) {
        this.stateVariables = List.copyOf(allStateVariables);
        // Validate before starting expensive worker sessions.
        new StateKeyEncoder(this.stateVariables);
    }

    public Result explore(Path sourceRoot, Path relativeMachine) throws Exception {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(relativeMachine, "relativeMachine");

        long started = System.nanoTime();
        ConcurrentStateRegistry registry = new ConcurrentStateRegistry(WORKER_COUNT);
        CountDownLatch startExpansion = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT, task -> {
            Thread thread = new Thread(task, "prob-explorer-" + threadNumber.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        });
        List<Worker> workers = List.of(
                new Worker(0, sourceRoot, relativeMachine, registry, startExpansion),
                new Worker(1, sourceRoot, relativeMachine, registry, startExpansion));
        List<Future<?>> executions = new ArrayList<>(WORKER_COUNT);
        boolean completed = false;
        try {
            for (Worker worker : workers) {
                executions.add(executor.submit(worker::run));
            }
            // No ProB State crosses the boundary: the snapshots contain Java keys/edges only.
            Snapshot first = workers.get(0).ready.get(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Snapshot second = workers.get(1).ready.get(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!first.equals(second)) {
                throw new IllegalStateException("Worker initial states/edges differ under the same model");
            }

            StateKey initial = first.initial();
            registry.registerCompletedInitial(initial);
            // Both workers already hold local ProB objects for these depth-1 states.
            List<StateKey> frontier = first.successors().stream()
                    .filter(key -> !key.equals(initial))
                    .sorted(Comparator.comparing(StateKey::toString))
                    .toList();
            for (int i = 0; i < frontier.size(); i++) {
                registry.registerPending(frontier.get(i), i % WORKER_COUNT);
            }
            for (LabeledTransition edge : first.edges()) {
                registry.recordEdge(edge);
            }
            registry.finishBootstrap();
            long bootstrapped = System.nanoTime();
            startExpansion.countDown();

            for (Future<?> future : executions) {
                future.get();
            }
            long closed = System.nanoTime();
            long expansionEnded = workers.stream().mapToLong(worker -> worker.expansionFinishedNanos).max()
                    .orElseThrow();
            ReachabilityGraph graph = registry.completedGraph(initial);
            int expansions = workers.stream().mapToInt(worker -> worker.expansions).sum();
            if (expansions + 1 != graph.states().size()) {
                throw new IllegalStateException("State expansion count differs from graph size: "
                        + expansions + " + root vs " + graph.states().size());
            }
            completed = true;
            return new Result(graph, bootstrapped - started,
                    Math.max(0, expansionEnded - bootstrapped),
                    Math.max(0, closed - expansionEnded),
                    workers.stream().map(worker -> worker.expansions).toList());
        } catch (Exception | Error failure) {
            registry.abort(failure);
            throw failure;
        } finally {
            startExpansion.countDown();
            if (!completed) {
                for (Future<?> future : executions) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("ProB worker threads failed to terminate");
            }
        }
    }

    private record Snapshot(StateKey initial, Set<LabeledTransition> edges, Set<StateKey> successors) {
        private Snapshot {
            edges = Set.copyOf(edges);
            successors = Set.copyOf(successors);
        }
    }

    private final class Worker {
        private final int id;
        private final Path sourceRoot;
        private final Path relativeMachine;
        private final ConcurrentStateRegistry registry;
        private final CountDownLatch startExpansion;
        private final StateKeyEncoder stateKeys = new StateKeyEncoder(stateVariables);
        private final ActionLabelEncoder labels = new ActionLabelEncoder();
        private final Map<String, StateKey> byLocalId = new HashMap<>();
        private final Map<StateKey, State> localStates = new HashMap<>();
        private final CompletableFuture<Snapshot> ready = new CompletableFuture<>();
        private volatile long expansionFinishedNanos;
        private volatile int expansions;

        private Worker(int id, Path sourceRoot, Path relativeMachine,
                       ConcurrentStateRegistry registry, CountDownLatch startExpansion) {
            this.id = id;
            this.sourceRoot = sourceRoot;
            this.relativeMachine = relativeMachine;
            this.registry = registry;
            this.startExpansion = startExpansion;
        }

        private void run() {
            try (ProBWorkerSession session = ProBWorkerSession.open(sourceRoot, relativeMachine, false)) {
                ready.complete(materializeInitial(session));
                startExpansion.await();
                while (true) {
                    ConcurrentStateRegistry.WorkItem task = registry.take(id);
                    if (task.isStop()) {
                        break;
                    }
                    StateKey key = task.key();
                    State source = localStates.get(key);
                    if (source == null) {
                        throw new IllegalStateException("Worker " + id + " has no local ProB state for " + key);
                    }
                    registry.start(key, id);
                    expansions++;
                    for (Transition transition : source.getOutTransitions()) {
                        State destination = transition.getDestination();
                        StateKey destinationKey = key(destination);
                        localStates.putIfAbsent(destinationKey, destination);
                        registry.registerPending(destinationKey, id);
                        // Do not discard transitions to previously registered states.
                        registry.recordEdge(new LabeledTransition(key, labels.label(transition), destinationKey));
                    }
                    registry.complete(key, id);
                }
                expansionFinishedNanos = System.nanoTime();
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
                registry.abort(failure);
                // Executors propagate the failure as the cause of Future.get().
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("ProB worker " + id + " failed", failure);
            }
        }

        private Snapshot materializeInitial(ProBWorkerSession session) {
            State initial = session.initialState();
            StateKey root = key(initial);
            localStates.put(root, initial);
            Set<LabeledTransition> outgoing = new HashSet<>();
            Set<StateKey> successors = new HashSet<>();
            for (Transition transition : initial.getOutTransitions()) {
                State destination = transition.getDestination();
                StateKey destinationKey = key(destination);
                localStates.putIfAbsent(destinationKey, destination);
                successors.add(destinationKey);
                outgoing.add(new LabeledTransition(root, labels.label(transition), destinationKey));
            }
            return new Snapshot(root, outgoing, successors);
        }

        private StateKey key(State state) {
            return byLocalId.computeIfAbsent(state.getId(), ignored -> stateKeys.key(state));
        }
    }
}
