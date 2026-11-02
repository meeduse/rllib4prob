package fr.polytech.mnia.exploration.benchmark;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.prob.ProBWorkerSession;

/**
 * Isolated reproduction of the current unbounded Environment.recursive()
 * iterative DFS. Times state discovery before a separate, common Java graph
 * extraction pass. Not a direct invocation of Environment's private method.
 */
public final class RecursiveGraphExplorer {
    private final List<String> variables;

    public record Result(ReachabilityGraph graph, long loadNanos,
                         long depthFirstSearchNanos, long extractionNanos,
                         long cleanupNanos, int discoveredStateCount) {
        public long nativeReadyNanos() { return loadNanos + depthFirstSearchNanos; }
        public long graphReadyNanos() { return nativeReadyNanos() + extractionNanos; }
        public long endToEndNanos() { return graphReadyNanos() + cleanupNanos; }
    }

    public RecursiveGraphExplorer(List<String> variableNames) {
        this.variables = List.copyOf(variableNames);
    }

    public Result explore(Path sourceRoot, Path relativeMachine) throws Exception {
        long started = System.nanoTime();
        ProBWorkerSession session = ProBWorkerSession.open(sourceRoot, relativeMachine, false);
        long loaded = System.nanoTime();
        ReachabilityGraph graph;
        int discovered;
        long traversed;
        long graphReady;
        try {
            State initial = session.initialState();
            discovered = visitUsingExistingDfsOrder(initial);
            traversed = System.nanoTime();
            graph = new ReferenceGraphExtractor(variables).extract(initial);
            graphReady = System.nanoTime();
            if (discovered != graph.states().size()) {
                throw new IllegalStateException("RECURSIVE visited " + discovered
                        + " states but graph extraction found " + graph.states().size());
            }
        } finally {
            session.close();
        }
        long closed = System.nanoTime();
        return new Result(graph, loaded - started, traversed - loaded,
                graphReady - traversed, closed - graphReady, discovered);
    }

    private static int visitUsingExistingDfsOrder(State root) {
        // Equivalent unbounded DFS to Environment.recursive(), which uses
        // a stack of transition iterators (not recursive Java calls).
        class Frame {
            final Iterator<Transition> transitions;
            Frame(State state) { transitions = state.getOutTransitions().iterator(); }
        }
        Set<State> visited = new HashSet<>();
        Deque<Frame> stack = new ArrayDeque<>();
        visited.add(root);
        stack.push(new Frame(root));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (!frame.transitions.hasNext()) {
                stack.pop();
                continue;
            }
            State next = frame.transitions.next().getDestination();
            if (visited.add(next)) stack.push(new Frame(next));
        }
        return visited.size();
    }
}
