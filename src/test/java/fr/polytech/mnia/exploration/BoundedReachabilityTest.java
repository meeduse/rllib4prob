package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.polytech.mnia.exploration.concurrent.BoundedReachabilityExplorer;
import fr.polytech.mnia.exploration.concurrent.DeterministicTransitionPolicy;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/**
 * Real ProB regression test: independently restrict the complete sequential
 * IncFlip graph and compare exact vertices, labeled edges, and minimum depths.
 */
public final class BoundedReachabilityTest {
    private BoundedReachabilityTest() { }

    private record Expected(ReachabilityGraph graph, Map<StateKey, Integer> levels) { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        List<String> variables = List.of("x", "y");
        ReachabilityGraph complete = new SequentialReachabilityExplorer(variables)
                .explore(root, machine).graph();
        check(complete.states().size() == 6 && complete.edges().size() == 10,
                "IncFlip complete sequential graph differs from the manuscript");

        // p, cut depth d, bound D, width B, expected states, expected edges
        int[][] cases = {
                {2, 0, 0, 2, 1, 0},
                {2, 0, 1, 2, 3, 2},
                {2, 1, 1, 2, 3, 2},
                {2, 0, 2, 2, 5, 6},
                {2, 1, 2, 2, 5, 6},
                {2, 2, 2, 2, 5, 6},
                {2, 1, 3, 2, 6, 9},
                {2, 1, 4, 2, 6, 10},
                {4, 2, 4, 2, 6, 10},
                {2, 1, 3, 1, 2, 2},
                {2, 1, 3, 0, 1, 0}
        };
        for (int[] c : cases) {
            int p = c[0], d = c[1], depth = c[2], width = c[3];
            Expected expected = restrict(complete, depth, width);
            check(expected.graph().states().size() == c[4]
                    && expected.graph().edges().size() == c[5], "Incorrect independent expected counts");
            // Repeat cases with an actual concurrent frontier to expose scheduling errors.
            int repetitions = (p == 2 && d == 1 && width == 2 && depth >= 2) ? 2 : 1;
            for (int run = 1; run <= repetitions; run++) {
                BoundedReachabilityExplorer.Result result =
                        new BoundedReachabilityExplorer(variables, p, d, depth, width)
                                .explore(root, machine);
                check(expected.graph().equals(result.graph()), "Incorrect G_(B,D): p=" + p
                        + " d=" + d + " D=" + depth + " B=" + width
                        + " missingEdges=" + difference(expected.graph().edges(), result.graph().edges())
                        + " extraEdges=" + difference(result.graph().edges(), expected.graph().edges()));
                check(expected.levels().equals(result.levels()), "Incorrect minimum BFS levels");
                check(result.completedPrefixStates()
                                + result.workerExpansions().stream().mapToInt(Integer::intValue).sum()
                        == expected.levels().values().stream().filter(level -> level < depth).count(),
                        "Duplicated or missing logical state expansions");
                if (depth == 2 && width == 2) {
                    check(result.graph().edges().stream().anyMatch(edge ->
                                    edge.destination().equals(expected.graph().initialStates().iterator().next())
                                            && !edge.source().equals(edge.destination())),
                            "Missing transition toward already discovered initial state");
                }
                System.out.printf("PASS: BOUNDED IncFlip p=%d d=%d D=%d B=%d run=%d "
                                + "states=%d edges=%d frontier=%d expansions=%s "
                                + "bootstrapMs=%.3f explorationMs=%.3f cleanupMs=%.3f totalMs=%.3f%n",
                        p, d, depth, width, run,
                        result.graph().states().size(), result.graph().edges().size(),
                        result.frontierStates(), result.workerExpansions(),
                        result.bootstrapNanos() / 1e6, result.explorationNanos() / 1e6,
                        result.cleanupNanos() / 1e6, result.totalNanos() / 1e6);
            }
        }
    }

    /** Pure Java BFS over the complete canonical sequential graph. */
    private static Expected restrict(ReachabilityGraph complete, int maxDepth, int width) {
        StateKey initial = complete.initialStates().iterator().next();
        Map<StateKey, Integer> levels = new HashMap<>();
        Set<LabeledTransition> selected = new HashSet<>();
        ArrayDeque<StateKey> queue = new ArrayDeque<>();
        levels.put(initial, 0);
        queue.add(initial);
        while (!queue.isEmpty()) {
            StateKey state = queue.removeFirst();
            int level = levels.get(state);
            if (level == maxDepth) continue;
            List<LabeledTransition> out = DeterministicTransitionPolicy.select(
                    complete.edges().stream().filter(e -> e.source().equals(state)).toList(), width);
            for (LabeledTransition edge : out) {
                selected.add(edge);
                if (levels.putIfAbsent(edge.destination(), level + 1) == null) {
                    queue.addLast(edge.destination());
                }
            }
        }
        return new Expected(new ReachabilityGraph(Set.of(initial), levels.keySet(), selected),
                Map.copyOf(levels));
    }

    private static <T> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> copy = new HashSet<>(a);
        copy.removeAll(b);
        return copy;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
