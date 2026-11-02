package fr.polytech.mnia.exploration.sequential;

import java.nio.file.Path;
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
import fr.polytech.mnia.exploration.prob.ProBWorkerSession;

/**
 * Reference complete BFS exploration with exactly one isolated ProB session.
 * Every state is expanded once; every distinct labeled transition is recorded.
 * No learning algorithms, existing environments, or model checker are changed.
 */
public final class SequentialReachabilityExplorer {
    private final StateKeyEncoder stateKeys;
    private final ActionLabelEncoder labels = new ActionLabelEncoder();

    public record Result(ReachabilityGraph graph, long loadNanos,
                         long explorationNanos, long cleanupNanos) {
        public long totalNanos() { return loadNanos + explorationNanos + cleanupNanos; }
    }

    public SequentialReachabilityExplorer(List<String> allStateVariables) {
        this.stateKeys = new StateKeyEncoder(allStateVariables);
    }

    public Result explore(Path sourceRoot, Path relativeMachine) throws Exception {
        long start = System.nanoTime();
        ProBWorkerSession worker = ProBWorkerSession.open(sourceRoot, relativeMachine, false);
        long loaded = System.nanoTime();
        ReachabilityGraph graph;
        long completed;
        try {
            graph = exploreLoaded(worker);
            completed = System.nanoTime();
        } finally {
            worker.close();
        }
        long closed = System.nanoTime();
        return new Result(graph, loaded - start, completed - loaded, closed - completed);
    }

    private ReachabilityGraph exploreLoaded(ProBWorkerSession worker) {
        State initial = worker.initialState();
        Map<String, StateKey> byLocalId = new HashMap<>();
        StateKey initialKey = key(initial, byLocalId);
        Set<StateKey> discovered = new HashSet<>();
        Set<StateKey> expanded = new HashSet<>();
        Set<LabeledTransition> edges = new HashSet<>();
        ArrayDeque<State> pending = new ArrayDeque<>();
        discovered.add(initialKey);
        pending.addLast(initial);

        while (!pending.isEmpty()) {
            State source = pending.removeFirst();
            StateKey sourceKey = key(source, byLocalId);
            if (!expanded.add(sourceKey)) {
                continue;
            }
            for (Transition transition : source.getOutTransitions()) {
                State destination = transition.getDestination();
                StateKey destinationKey = key(destination, byLocalId);
                edges.add(new LabeledTransition(sourceKey, labels.label(transition), destinationKey));
                if (discovered.add(destinationKey)) {
                    pending.addLast(destination);
                }
            }
        }
        if (!expanded.equals(discovered)) {
            throw new IllegalStateException("Incomplete exploration: unexpanded states remain");
        }
        return new ReachabilityGraph(Set.of(initialKey), discovered, edges);
    }

    private StateKey key(State state, Map<String, StateKey> byLocalId) {
        return byLocalId.computeIfAbsent(state.getId(), ignored -> stateKeys.key(state));
    }
}
