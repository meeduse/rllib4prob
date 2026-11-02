package fr.polytech.mnia.exploration.concurrent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * One worker's immutable BFS prefix. Level numbers are shortest-path distances
 * from the B initial state. Only states below cut depth have been expanded.
 * This value contains no ProB objects or instance-local state identifiers.
 */
public record ReplicatedPrefix(StateKey initial, Map<StateKey, Integer> levels,
                               Set<LabeledTransition> edges) {
    public ReplicatedPrefix {
        Objects.requireNonNull(initial, "initial");
        levels = Map.copyOf(levels);
        edges = Set.copyOf(edges);
        if (!Integer.valueOf(0).equals(levels.get(initial))) {
            throw new IllegalArgumentException("Initial state must be at BFS level 0");
        }
        if (levels.values().stream().anyMatch(depth -> depth == null || depth < 0)) {
            throw new IllegalArgumentException("Invalid BFS level");
        }
        for (LabeledTransition edge : edges) {
            Integer sourceDepth = levels.get(edge.source());
            Integer destDepth = levels.get(edge.destination());
            if (sourceDepth == null || destDepth == null || destDepth > sourceDepth + 1) {
                throw new IllegalArgumentException("Prefix edge has inconsistent BFS levels: " + edge);
            }
        }
    }

    public int completedPrefixStates(int cutDepth) {
        return (int) levels.values().stream().filter(value -> value < cutDepth).count();
    }

    public int frontierStates(int cutDepth) {
        return (int) levels.values().stream().filter(value -> value == cutDepth).count();
    }

    /**
     * Commit the common prefix exactly once using the EXISTING atomic registry.
     *
     * All states below the cut are logically completed before releasing worker
     * threads. A prefix node at level >=1 is provisionally registered as pending
     * for the coordinator, then consumed and completed during this serial commit.
     * All frontier nodes are registered first, preventing premature termination.
     * No obsolete bootstrap work item remains in any worker queue afterward.
     *
     * This additive implementation deliberately supports d >= 1. With d=0 the
     * existing registry's registerCompletedInitial() would prematurely mark the
     * root expanded, so d=0 requires a later registry interface change.
     */
    public void commitInto(ConcurrentStateRegistry registry, int cutDepth, int workerCount)
            throws InterruptedException {
        Objects.requireNonNull(registry, "registry");
        if (cutDepth < 1 || workerCount < 1) {
            throw new IllegalArgumentException("cutDepth >= 1 and workerCount >= 1 required");
        }
        if (levels.values().stream().anyMatch(level -> level > cutDepth)) {
            throw new IllegalArgumentException("Prefix includes a state past the cut depth");
        }
        for (LabeledTransition edge : edges) {
            if (levels.get(edge.source()) >= cutDepth) {
                throw new IllegalArgumentException("Prefix expands a frontier state: " + edge);
            }
        }

        Comparator<StateKey> keyOrder = Comparator.comparing(StateKey::toString);
        // A complete prefix contains every outgoing edge from every state below
        // the cut. The caller is responsible for ensuring this by BFS materialisation.
        List<StateKey> interior = new ArrayList<>();
        List<StateKey> frontier = new ArrayList<>();
        for (Map.Entry<StateKey, Integer> entry : levels.entrySet()) {
            if (entry.getKey().equals(initial)) {
                continue;
            }
            if (entry.getValue() < cutDepth) {
                interior.add(entry.getKey());
            } else {
                frontier.add(entry.getKey());
            }
        }
        interior.sort(Comparator.comparingInt((StateKey key) -> levels.get(key)).thenComparing(keyOrder));
        frontier.sort(keyOrder);

        registry.registerCompletedInitial(initial);
        for (StateKey key : interior) {
            registry.registerPending(key, 0);
        }
        for (int i = 0; i < frontier.size(); i++) {
            // Every worker holds a locally materialized ProB state for each key.
            registry.registerPending(frontier.get(i), i % workerCount);
        }
        for (LabeledTransition edge : edges) {
            registry.recordEdge(edge);
        }
        for (StateKey key : interior) {
            ConcurrentStateRegistry.WorkItem queued = registry.take(0);
            if (queued.isStop() || !queued.key().equals(key)) {
                throw new IllegalStateException("Unexpected pending prefix work item for " + key);
            }
            registry.start(key, 0);
            registry.complete(key, 0);
        }
        registry.finishBootstrap();
    }
}
