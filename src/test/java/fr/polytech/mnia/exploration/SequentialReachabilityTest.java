package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.polytech.mnia.exploration.encoding.CanonicalBValue;
import fr.polytech.mnia.exploration.model.ActionLabel;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/** Exact oracle for the IncFlip machine (six states, ten labeled edges). */
public final class SequentialReachabilityTest {
    private SequentialReachabilityTest() { }

    public static void main(String[] args) throws Exception {
        SequentialReachabilityExplorer explorer = new SequentialReachabilityExplorer(List.of("x", "y"));
        Path source = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        SequentialReachabilityExplorer.Result first = explorer.explore(source, machine);
        SequentialReachabilityExplorer.Result second = explorer.explore(source, machine);
        check(first.graph().equals(second.graph()), "Independent runs returned different graphs");

        ReachabilityGraph graph = first.graph();
        Set<StateKey> states = new HashSet<>();
        Set<LabeledTransition> expected = new HashSet<>();
        for (int x = 0; x <= 2; x++) {
            for (int y = 0; y <= 1; y++) {
                StateKey sourceKey = state(x, y);
                states.add(sourceKey);
                expected.add(edge(sourceKey, "flip", state(x, 1 - y)));
                if (x < 2) {
                    expected.add(edge(sourceKey, "inc", state(x + 1, y)));
                }
            }
        }
        check(graph.initialStates().equals(Set.of(state(0, 0))), "Incorrect initial state");
        check(graph.states().equals(states), "Incorrect reachable states: " + graph.states());
        check(graph.edges().equals(expected), "Missing or unexpected edges: "
                + difference(expected, graph.edges()) + " / " + difference(graph.edges(), expected));
        check(graph.states().size() == 6 && graph.edges().size() == 10, "Incorrect graph size");
        System.out.printf("PASS: SEQUENTIAL IncFlip states=%d transitions=%d "
                        + "loadMs=%.3f explorationMs=%.3f cleanupMs=%.3f totalMs=%.3f%n",
                graph.states().size(), graph.edges().size(),
                first.loadNanos() / 1e6, first.explorationNanos() / 1e6,
                first.cleanupNanos() / 1e6, first.totalNanos() / 1e6);
    }

    private static StateKey state(int x, int y) {
        return new StateKey(Map.of("x", CanonicalBValue.encode(Integer.toString(x)),
                                   "y", CanonicalBValue.encode(Integer.toString(y))));
    }

    private static LabeledTransition edge(StateKey source, String operation, StateKey target) {
        return new LabeledTransition(source, new ActionLabel(operation, List.of(), List.of()), target);
    }

    private static <T> Set<T> difference(Set<T> left, Set<T> right) {
        Set<T> copy = new HashSet<>(left);
        copy.removeAll(right);
        return copy;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
