package fr.polytech.mnia.exploration.benchmark;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.exploration.encoding.ActionLabelEncoder;
import fr.polytech.mnia.exploration.encoding.StateKeyEncoder;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * Common Java graph-extraction pass for state-only ProB exploration strategies.
 * Uses the same exact state/action encoders as CONCURRENT; never shares ProB
 * objects across sessions. The cost of this pass is measured separately.
 *
 * Important: this is a Java API traversal AFTER native exploration, not a
 * special optimized ProB export facility. Benchmark reports say so explicitly.
 */
public final class ReferenceGraphExtractor {
    private final StateKeyEncoder stateKeys;
    private final ActionLabelEncoder labels = new ActionLabelEncoder();

    public ReferenceGraphExtractor(List<String> variableNames) {
        this.stateKeys = new StateKeyEncoder(variableNames);
    }

    public ReachabilityGraph extract(State initializedRoot) {
        Map<String, StateKey> byLocalId = new HashMap<>();
        StateKey initialKey = key(initializedRoot, byLocalId);
        Set<StateKey> visited = new HashSet<>();
        Set<LabeledTransition> edges = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        visited.add(initialKey);
        queue.addLast(initializedRoot);

        while (!queue.isEmpty()) {
            State source = queue.removeFirst();
            StateKey sourceKey = key(source, byLocalId);
            for (Transition transition : source.getOutTransitions()) {
                State destination = transition.getDestination();
                StateKey destinationKey = key(destination, byLocalId);
                edges.add(new LabeledTransition(
                        sourceKey, labels.label(transition), destinationKey));
                if (visited.add(destinationKey)) {
                    queue.addLast(destination);
                }
            }
        }
        return new ReachabilityGraph(Set.of(initialKey), visited, edges);
    }

    private StateKey key(State state, Map<String, StateKey> byLocalId) {
        return byLocalId.computeIfAbsent(state.getId(), id -> stateKeys.key(state));
    }
}
