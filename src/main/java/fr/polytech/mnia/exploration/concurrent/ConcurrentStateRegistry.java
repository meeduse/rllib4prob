package fr.polytech.mnia.exploration.concurrent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * Shared state and labeled-edge registry for the minimal concurrent explorer.
 *
 * Only canonical Java values cross the worker boundary. In particular, no ProB
 * State or Transition object is stored here. All state registration, queue
 * publication and status changes are protected by the same monitor.
 *
 * No task reassignment and no recovery after a failed worker (step 4 scope).
 */
public final class ConcurrentStateRegistry {
    public enum Status { PENDING, ACTIVE, DONE }

    /** A null key is used only for the internal stop notification. */
    public static final class WorkItem {
        private final StateKey key;

        private WorkItem(StateKey key) {
            this.key = key;
        }

        public boolean isStop() {
            return key == null;
        }

        public StateKey key() {
            if (isStop()) {
                throw new IllegalStateException("Stop notification has no state key");
            }
            return key;
        }
    }

    private static final WorkItem STOP = new WorkItem(null);

    private static final class Entry {
        private final int owner;
        private Status status;

        private Entry(int owner, Status status) {
            this.owner = owner;
            this.status = status;
        }
    }

    private final Map<StateKey, Entry> entries = new HashMap<>();
    private final Set<LabeledTransition> edges = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<BlockingQueue<WorkItem>> queues;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private int unfinished;
    private boolean stopped;

    public ConcurrentStateRegistry(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("At least one worker required");
        }
        List<BlockingQueue<WorkItem>> pending = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            pending.add(new LinkedBlockingQueue<>());
        }
        this.queues = List.copyOf(pending);
    }

    /** The common B initial state is logically expanded during bootstrap. */
    public synchronized void registerCompletedInitial(StateKey initial) {
        Objects.requireNonNull(initial, "initial");
        if (!entries.isEmpty()) {
            throw new IllegalStateException("Initial state already registered");
        }
        entries.put(initial, new Entry(-1, Status.DONE));
    }

    /**
     * Atomic register-and-publish. The caller must already hold a usable local
     * ProB state for key; this registry deliberately cannot validate that fact.
     *
     * @return true only when a NEW logical expansion task was published.
     */
    public synchronized boolean registerPending(StateKey key, int workerId) {
        Objects.requireNonNull(key, "key");
        validateWorker(workerId);
        if (stopped || failure.get() != null) {
            throw new IllegalStateException("Cannot publish after stop/failure");
        }
        if (entries.containsKey(key)) {
            return false;
        }
        entries.put(key, new Entry(workerId, Status.PENDING));
        unfinished++;
        // Unbounded queue: publication cannot fail without a fatal VM error.
        queues.get(workerId).add(new WorkItem(key));
        return true;
    }

    /** Called by the owning worker before reading any outgoing transitions. */
    public synchronized void start(StateKey key, int workerId) {
        Entry entry = required(key);
        if (entry.owner != workerId || entry.status != Status.PENDING) {
            throw new IllegalStateException("Invalid start or duplicate expansion: " + key);
        }
        entry.status = Status.ACTIVE;
    }

    /** Called only after every outgoing edge and successor task is published. */
    public synchronized void complete(StateKey key, int workerId) {
        Entry entry = required(key);
        if (entry.owner != workerId || entry.status != Status.ACTIVE) {
            throw new IllegalStateException("Invalid completion: " + key);
        }
        entry.status = Status.DONE;
        if (--unfinished < 0) {
            throw new IllegalStateException("Negative outstanding task count");
        }
        if (unfinished == 0) {
            stopWorkers();
        }
    }

    /** A previously known destination does NOT prevent edge insertion. */
    public void recordEdge(LabeledTransition edge) {
        Objects.requireNonNull(edge, "edge");
        edges.add(edge);
    }

    /** Completes bootstrapping when the initial state has no new successor. */
    public synchronized void finishBootstrap() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Initial state not registered");
        }
        if (unfinished == 0) {
            stopWorkers();
        }
    }

    public WorkItem take(int workerId) throws InterruptedException {
        validateWorker(workerId);
        return queues.get(workerId).take();
    }

    /** Unblocks idle workers on failure; the explorer must reject the run. */
    public void abort(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        failure.compareAndSet(null, cause);
        synchronized (this) {
            stopWorkers();
        }
    }

    public synchronized ReachabilityGraph completedGraph(StateKey initial) {
        if (failure.get() != null) {
            throw new IllegalStateException("Concurrent exploration failed", failure.get());
        }
        if (unfinished != 0 || !stopped || entries.values().stream().anyMatch(e -> e.status != Status.DONE)) {
            throw new IllegalStateException("Exploration incomplete");
        }
        Set<StateKey> known = Set.copyOf(entries.keySet());
        return new ReachabilityGraph(Set.of(initial), known, Set.copyOf(edges));
    }

    public synchronized int totalRegisteredStates() {
        return entries.size();
    }

    public synchronized int unfinishedTasks() {
        return unfinished;
    }

    private void stopWorkers() {
        if (stopped) {
            return;
        }
        stopped = true;
        for (BlockingQueue<WorkItem> queue : queues) {
            queue.add(STOP);
        }
    }

    private void validateWorker(int id) {
        if (id < 0 || id >= queues.size()) {
            throw new IllegalArgumentException("Unknown worker: " + id);
        }
    }

    private Entry required(StateKey key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            throw new IllegalStateException("Unknown state key: " + key);
        }
        return entry;
    }
}
