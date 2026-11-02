package fr.polytech.mnia.exploration.concurrent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * A registry for optional, locality-aware task reassignment (complete mode).
 *
 * Only canonical Java values are shared. ProB objects never enter the registry.
 * State registration, work publication, ownership changes and completion are
 * linearized under one monitor. No crash recovery is attempted.
 */
public final class StealableStateRegistry {
    public enum Status { PENDING, ACTIVE, DONE }

    public record WorkItem(StateKey key, long generation, boolean stop) {
        public WorkItem {
            if (!stop && key == null) {
                throw new IllegalArgumentException("Missing state key");
            }
        }
    }

    /** Immutable snapshot; may become obsolete before a transfer is attempted. */
    public record Candidate(StateKey key, int owner, long generation,
                            List<StateKey> predecessors) {
        public Candidate {
            predecessors = List.copyOf(predecessors);
        }
    }

    private static final WorkItem STOP = new WorkItem(null, -1, true);

    private static final class Entry {
        int owner;
        long generation;
        Status status;
        Entry(int owner, Status status) {
            this.owner = owner;
            this.status = status;
        }
    }

    private final int workerCount;
    private final Map<StateKey, Entry> states = new HashMap<>();
    private final Set<LabeledTransition> edges = new HashSet<>();
    private final Map<StateKey, Set<StateKey>> predecessors = new HashMap<>();
    private final Set<StateKey> pending = new HashSet<>();
    private final List<BlockingQueue<WorkItem>> queues;
    private final CountDownLatch completion = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean bootstrapped;
    private boolean stopped;
    private int unfinished;
    private long stealAttempts;
    private long stealSuccesses;

    public StealableStateRegistry(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        this.workerCount = workerCount;
        List<BlockingQueue<WorkItem>> pending = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            pending.add(new LinkedBlockingQueue<>());
        }
        queues = List.copyOf(pending);
    }

    /**
     * Single global commit of the already-validated replicated BFS prefix.
     * All workers have their own local handles for its frontier at this point.
     */
    public synchronized void commitPrefix(ReplicatedPrefix prefix, int cutDepth) {
        Objects.requireNonNull(prefix, "prefix");
        if (bootstrapped || cutDepth < 1 ||
                prefix.levels().values().stream().anyMatch(depth -> depth > cutDepth)) {
            throw new IllegalArgumentException("Invalid prefix or duplicate bootstrap");
        }
        for (LabeledTransition edge : prefix.edges()) {
            if (prefix.levels().get(edge.source()) >= cutDepth) {
                throw new IllegalArgumentException("Prefix expands the frontier");
            }
        }
        List<StateKey> frontier = prefix.levels().entrySet().stream()
                .filter(e -> e.getValue() == cutDepth)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(StateKey::toString))
                .toList();
        prefix.levels().forEach((key, depth) -> {
            if (depth < cutDepth) {
                states.put(key, new Entry(-1, Status.DONE));
            }
        });
        for (LabeledTransition edge : prefix.edges()) {
            insertEdge(edge);
        }
        for (int i = 0; i < frontier.size(); i++) {
            registerNew(frontier.get(i), i % workerCount);
        }
        bootstrapped = true;
        if (unfinished == 0) {
            stopWorkers();
        }
    }

    /** Called only after this worker has retained its local ProB destination. */
    public synchronized boolean registerPending(StateKey key, int owner) {
        Objects.requireNonNull(key, "key");
        validateWorker(owner);
        ensureRunning();
        if (!bootstrapped) {
            throw new IllegalStateException("Prefix not committed");
        }
        if (states.containsKey(key)) {
            return false;
        }
        registerNew(key, owner);
        return true;
    }

    private void registerNew(StateKey key, int owner) {
        states.put(key, new Entry(owner, Status.PENDING));
        unfinished++;
        pending.add(key);
        queues.get(owner).add(new WorkItem(key, 0, false));
    }

    public synchronized void recordEdge(LabeledTransition edge) {
        Objects.requireNonNull(edge, "edge");
        ensureRunning();
        if (!states.containsKey(edge.source()) || !states.containsKey(edge.destination())) {
            throw new IllegalStateException("Missing endpoint for " + edge);
        }
        insertEdge(edge);  // idempotent, even for an already-known destination
    }

    /** Returns false for a stale queue entry following a successful reassignment. */
    public synchronized boolean start(WorkItem task, int worker) {
        validateWorker(worker);
        if (task.stop()) {
            return false;
        }
        Entry e = states.get(task.key());
        if (e == null) {
            throw new IllegalStateException("Unknown work item: " + task.key());
        }
        if (e.status != Status.PENDING || e.owner != worker ||
                e.generation != task.generation()) {
            return false;
        }
        e.status = Status.ACTIVE;
        pending.remove(task.key());
        return true;
    }

    /** No completion is allowed before all successor registrations and edges. */
    public synchronized void complete(WorkItem task, int worker) {
        validateWorker(worker);
        Entry e = required(task.key());
        if (e.status != Status.ACTIVE || e.owner != worker ||
                e.generation != task.generation()) {
            throw new IllegalStateException("Invalid completion for " + task.key());
        }
        e.status = Status.DONE;
        if (--unfinished < 0) {
            throw new IllegalStateException("Negative unfinished task count");
        }
        if (unfinished == 0) {
            stopWorkers();
        }
    }

    /** Snapshot only: neither ProB objects nor mutable entry records are exported. */
    public synchronized List<Candidate> pendingFor(int receiver) {
        validateWorker(receiver);
        if (stopped) {
            return List.of();
        }
        List<Candidate> result = new ArrayList<>();
        for (StateKey key : pending) {
            Entry entry = states.get(key);
            if (entry.owner == receiver) {
                continue;
            }
            result.add(new Candidate(key, entry.owner, entry.generation,
                    predecessors.getOrDefault(key, Set.of()).stream()
                            .sorted(Comparator.comparing(StateKey::toString)).toList()));
        }
        result.sort(Comparator.comparing(c -> c.key().toString()));
        return result;
    }

    /**
     * Linearizable CAS and guaranteed publication in one critical section.
     * The caller must ALREADY hold a usable local ProB state for the key.
     */
    public synchronized boolean tryReassign(Candidate expected, int receiver) {
        Objects.requireNonNull(expected, "expected");
        validateWorker(receiver);
        if (receiver == expected.owner()) {
            return false;
        }
        stealAttempts++;
        Entry e = states.get(expected.key());
        if (stopped || failure.get() != null || e == null || e.status != Status.PENDING ||
                e.owner != expected.owner() || e.generation != expected.generation()) {
            return false;
        }
        e.owner = receiver;
        e.generation++;
        // Queue is unbounded. No separate asynchronous publication window.
        queues.get(receiver).add(new WorkItem(expected.key(), e.generation, false));
        stealSuccesses++;
        return true;
    }

    public WorkItem poll(int worker, long milliseconds) throws InterruptedException {
        validateWorker(worker);
        return queues.get(worker).poll(milliseconds, TimeUnit.MILLISECONDS);
    }

    /** Completes on either success or failure. The caller must inspect the graph. */
    public void awaitCompletion() throws InterruptedException {
        completion.await();
    }

    public synchronized ReachabilityGraph completedGraph(StateKey initial) {
        if (failure.get() != null) {
            throw new IllegalStateException("Concurrent exploration failed", failure.get());
        }
        if (!bootstrapped || !stopped || unfinished != 0 ||
                states.values().stream().anyMatch(e -> e.status != Status.DONE)) {
            throw new IllegalStateException("Exploration incomplete");
        }
        return new ReachabilityGraph(Set.of(initial), Set.copyOf(states.keySet()), Set.copyOf(edges));
    }

    public void abort(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        failure.compareAndSet(null, cause);
        synchronized (this) {
            stopWorkers();
        }
    }

    public synchronized long attempts() { return stealAttempts; }
    public synchronized long successes() { return stealSuccesses; }
    public synchronized int unfinished() { return unfinished; }

    private void stopWorkers() {
        if (stopped) {
            return;
        }
        stopped = true;
        for (BlockingQueue<WorkItem> queue : queues) {
            queue.add(STOP);
        }
        completion.countDown();
    }

    private void insertEdge(LabeledTransition edge) {
        if (edges.add(edge)) {
            predecessors.computeIfAbsent(edge.destination(), ignored -> new HashSet<>())
                    .add(edge.source());
        }
    }

    private Entry required(StateKey key) {
        Entry entry = states.get(key);
        if (entry == null) {
            throw new IllegalStateException("Unknown state: " + key);
        }
        return entry;
    }

    private void validateWorker(int worker) {
        if (worker < 0 || worker >= workerCount) {
            throw new IllegalArgumentException("Invalid worker id: " + worker);
        }
    }

    private void ensureRunning() {
        if (stopped || failure.get() != null) {
            throw new IllegalStateException("Registry stopped");
        }
    }
}
