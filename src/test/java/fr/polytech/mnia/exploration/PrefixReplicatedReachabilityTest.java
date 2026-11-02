package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.List;

import fr.polytech.mnia.exploration.concurrent.PrefixReplicatedReachabilityExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/** Real ProB test: equality with complete sequential graph for multiple cuts. */
public final class PrefixReplicatedReachabilityTest {
    private PrefixReplicatedReachabilityTest() { }

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        List<String> vars = List.of("x", "y");
        ReachabilityGraph oracle = new SequentialReachabilityExplorer(vars).explore(source, machine).graph();
        check(oracle.states().size() == 6 && oracle.edges().size() == 10,
                "Invalid IncFlip reference graph");

        // Cut 4 exceeds IncFlip's maximal shortest-path level 3.
        int[][] cases = {{2, 1, 2, 1}, {2, 2, 2, 3}, {2, 3, 1, 5},
                         {2, 4, 0, 6}, {4, 2, 2, 3}};
        for (int[] test : cases) {
            int workers = test[0], depth = test[1];
            int expectedFrontier = test[2], expectedCompletedPrefix = test[3];
            for (int repeat = 1; repeat <= 2; repeat++) {
                PrefixReplicatedReachabilityExplorer.Result result =
                        new PrefixReplicatedReachabilityExplorer(vars, workers, depth)
                                .explore(source, machine);
                ReachabilityGraph graph = result.graph();
                check(oracle.equals(graph), "Graph mismatch p=" + workers + " d=" + depth
                        + " run=" + repeat + " missing states=" + missing(oracle.states(), graph.states())
                        + " missing edges=" + missing(oracle.edges(), graph.edges())
                        + " extra states=" + missing(graph.states(), oracle.states())
                        + " extra edges=" + missing(graph.edges(), oracle.edges()));
                check(result.frontierStates() == expectedFrontier,
                        "Unexpected frontier size at depth " + depth);
                check(result.completedPrefixStates() == expectedCompletedPrefix,
                        "Unexpected prefix completion at depth " + depth);
                check(result.workerExpansions().stream().mapToInt(Integer::intValue).sum()
                                + result.completedPrefixStates() == 6,
                        "Incomplete or duplicated logical expansion");
                check(result.workerExpansions().size() == workers
                                && result.workerPrefixNanos().size() == workers,
                        "Missing per-worker metrics");
                if (depth == 1 || depth == 2) {
                    check(result.workerExpansions().get(0) > 0
                                    && result.workerExpansions().get(1) > 0,
                            "Expected two active workers for IncFlip at d=" + depth);
                }
                System.out.printf("PASS: PREFIX IncFlip p=%d d=%d run=%d states=%d edges=%d "
                                + "prefixDone=%d frontier=%d expansions=%s "
                                + "bootstrapMs=%.3f explorationMs=%.3f cleanupMs=%.3f totalMs=%.3f%n",
                        workers, depth, repeat, graph.states().size(), graph.edges().size(),
                        result.completedPrefixStates(), result.frontierStates(), result.workerExpansions(),
                        result.bootstrapNanos() / 1e6, result.explorationNanos() / 1e6,
                        result.cleanupNanos() / 1e6, result.totalNanos() / 1e6);
            }
        }
    }

    private static <T> java.util.Set<T> missing(java.util.Set<T> left, java.util.Set<T> right) {
        java.util.Set<T> result = new java.util.HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
