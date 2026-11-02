package fr.polytech.mnia.exploration.concurrent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * Global bounded-BFS registry, independent of ProB objects. Only states at the
 * current BFS level may be expanded. The coordinator advances to the next
 * level after all workers have completed their assigned work at this level.
 *
 * All public operations are synchronized so registration/ownership/edge
 * publication are linearizable. The caller keeps the ProB state object locally.
 */
public final class BoundedStateRegistry {
    public enum Status { PENDING, ACTIVE, DONE, BOUNDARY }
    public record Task(StateKey key, int level, int owner) { }

    private static final class Entry {
        final int level;
        final int owner;
        Status status;

        Entry(int level, int owner, Status status) {
            this.level = level;
            this.owner = owner;
            this.status = status;
        }
    }

    private final int workerCount;
    private final int maxDepth;
    private final Map<StateKey, Entry> entries = new HashMap<>();
    private final Set<LabeledTransition> edges = new HashSet<>();
    private StateKey initial;
    private int currentLevel;
    private boolean initialized;

    public BoundedStateRegistry(int workerCount, int maxDepth) {
        if (workerCount < 1 || maxDepth < 0) {
            throw new IllegalArgumentException("workerCount >= 1 and maxDepth >= 0 required");
        }
        this.workerCount = workerCount;
        this.maxDepth = maxDepth;
    }

    /** Commit a common replicated prefix exactly once, before worker release. */
    public synchronized void initialize(StateKey initial, Map<StateKey, Integer> prefixLevels,
                                        Set<LabeledTransition> prefixEdges, int cutDepth) {
        if (initialized) throw new IllegalStateException("Already initialized");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(prefixLevels, "prefixLevels");
        Objects.requireNonNull(prefixEdges, "prefixEdges");
        if (cutDepth < 0 || cutDepth > maxDepth || !Integer.valueOf(0).equals(prefixLevels.get(initial))) {
            throw new IllegalArgumentException("Invalid prefix cut or initial state");
        }
        for (Integer level : prefixLevels.values()) {
            if (level == null || level < 0 || level > cutDepth) {
                throw new IllegalArgumentException("State outside prefix BFS levels");
            }
        }
        List<StateKey> frontier = prefixLevels.entrySet().stream()
                .filter(e -> e.getValue() == cutDepth)
                .map(Map.Entry::getKey)
                .sorted(DeterministicTransitionPolicy.STATE_ORDER).toList();
        Map<StateKey, Integer> frontierOwner = new HashMap<>();
        for (int i = 0; i < frontier.size(); i++) frontierOwner.put(frontier.get(i), i % workerCount);
        for (var e : prefixLevels.entrySet()) {
            int depth = e.getValue();
            Status status = depth < cutDepth ? Status.DONE
                    : depth == maxDepth ? Status.BOUNDARY : Status.PENDING;
            entries.put(e.getKey(), new Entry(depth, status == Status.PENDING
                    ? frontierOwner.get(e.getKey()) : -1, status));
        }
        for (LabeledTransition edge : prefixEdges) {
            Entry source = entries.get(edge.source());
            Entry target = entries.get(edge.destination());
            if (source == null || target == null || source.level >= cutDepth
                    || target.level > source.level + 1) {
                throw new IllegalArgumentException("Invalid prefix edge: " + edge);
            }
        }
        edges.addAll(prefixEdges);
        this.initial = initial;
        this.currentLevel = cutDepth;
        this.initialized = true;
    }

    public synchronized List<Task> tasksForLevel(int level, int owner) {
        requireReady();
        requireWorker(owner);
        if (level != currentLevel || level >= maxDepth) {
            throw new IllegalStateException("Cannot schedule BFS level " + level);
        }
        List<Task> tasks = new ArrayList<>();
        for (var e : entries.entrySet()) {
            Entry value = e.getValue();
            if (value.level == level && value.owner == owner && value.status == Status.PENDING) {
                tasks.add(new Task(e.getKey(), level, owner));
            }
        }
        tasks.sort((a, b) -> DeterministicTransitionPolicy.STATE_ORDER.compare(a.key(), b.key()));
        return List.copyOf(tasks);
    }

    public synchronized void start(Task task) {
        requireReady();
        requireWorker(task.owner());
        Entry value = required(task.key());
        if (task.level() != currentLevel || value.level != task.level()
                || value.owner != task.owner() || value.status != Status.PENDING) {
            throw new IllegalStateException("Duplicate or invalid expansion: " + task);
        }
        value.status = Status.ACTIVE;
    }

    /** Register a successor at the next BFS level; existing older states remain unchanged. */
    public synchronized boolean registerSuccessor(StateKey key, int successorLevel, int owner) {
        requireReady();
        Objects.requireNonNull(key, "key");
        requireWorker(owner);
        if (successorLevel != currentLevel + 1 || successorLevel > maxDepth) {
            throw new IllegalStateException("Invalid successor level " + successorLevel);
        }
        Entry known = entries.get(key);
        if (known != null) {
            if (known.level > successorLevel) {
                throw new IllegalStateException("Nonminimum BFS level detected");
            }
            return false;
        }
        entries.put(key, new Entry(successorLevel, successorLevel == maxDepth ? -1 : owner,
                successorLevel == maxDepth ? Status.BOUNDARY : Status.PENDING));
        return true;
    }

    /** Edge recording never depends on whether registerSuccessor returned true. */
    public synchronized void recordEdge(LabeledTransition edge) {
        requireReady();
        Objects.requireNonNull(edge, "edge");
        Entry from = required(edge.source());
        Entry to = required(edge.destination());
        if (from.level != currentLevel || to.level > currentLevel + 1) {
            throw new IllegalStateException("Invalid edge/BFS level: " + edge);
        }
        edges.add(edge);
    }

    public synchronized void complete(Task task) {
        requireReady();
        Entry value = required(task.key());
        if (task.level() != currentLevel || value.owner != task.owner()
                || value.status != Status.ACTIVE) {
            throw new IllegalStateException("Completion without active ownership: " + task);
        }
        value.status = Status.DONE;
    }

    /** BFS barrier. All tasks at this level must be DONE before advancing. */
    public synchronized void finishLevel(int level) {
        requireReady();
        if (currentLevel != level || level >= maxDepth) {
            throw new IllegalStateException("Unexpected BFS barrier level");
        }
        if (entries.values().stream().anyMatch(e -> e.level == level && e.status != Status.DONE)) {
            throw new IllegalStateException("Level " + level + " still has unexpanded tasks");
        }
        currentLevel++;
    }

    public synchronized Map<StateKey, Integer> levels() {
        requireReady();
        Map<StateKey, Integer> snapshot = new HashMap<>();
        entries.forEach((key, value) -> snapshot.put(key, value.level));
        return Map.copyOf(snapshot);
    }

    public synchronized ReachabilityGraph completedGraph() {
        requireReady();
        if (currentLevel != maxDepth) throw new IllegalStateException("BFS exploration not finished");
        for (Entry entry : entries.values()) {
            Status expected = entry.level == maxDepth ? Status.BOUNDARY : Status.DONE;
            if (entry.status != expected) throw new IllegalStateException("Incomplete bounded graph");
        }
        return new ReachabilityGraph(Set.of(initial), Set.copyOf(entries.keySet()), Set.copyOf(edges));
    }

    private Entry required(StateKey key) {
        Entry value = entries.get(key);
        if (value == null) throw new IllegalStateException("Unknown canonical state key: " + key);
        return value;
    }

    private void requireReady() {
        if (!initialized) throw new IllegalStateException("Prefix not committed");
    }

    private void requireWorker(int worker) {
        if (worker < 0 || worker >= workerCount) {
            throw new IllegalArgumentException("Unknown worker " + worker);
        }
    }
}
