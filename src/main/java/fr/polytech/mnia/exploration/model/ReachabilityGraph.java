package fr.polytech.mnia.exploration.model;

import java.util.Set;

/** Immutable canonical graph, detached entirely from ProB. */
public record ReachabilityGraph(Set<StateKey> initialStates, Set<StateKey> states,
                                Set<LabeledTransition> edges) {
    public ReachabilityGraph {
        initialStates = Set.copyOf(initialStates);
        states = Set.copyOf(states);
        edges = Set.copyOf(edges);
        if (initialStates.isEmpty() || !states.containsAll(initialStates)) {
            throw new IllegalArgumentException("Invalid initial states");
        }
        for (LabeledTransition edge : edges) {
            if (!states.contains(edge.source()) || !states.contains(edge.destination())) {
                throw new IllegalArgumentException("Edge endpoint missing from state registry");
            }
        }
    }
}
