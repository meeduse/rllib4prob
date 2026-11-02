package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.List;

import fr.polytech.mnia.exploration.concurrent.ConcurrentReachabilityExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/** Compares the COMPLETE canonical graph of two workers to the sequential oracle. */
public final class ConcurrentReachabilityTest {
    private ConcurrentReachabilityTest() { }

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        List<String> variables = List.of("x", "y");
        ReachabilityGraph oracle = new SequentialReachabilityExplorer(variables)
                .explore(source, machine).graph();
        check(oracle.states().size() == 6 && oracle.edges().size() == 10,
                "Sequential IncFlip oracle not valid");

        ConcurrentReachabilityExplorer explorer = new ConcurrentReachabilityExplorer(variables);
        for (int repeat = 1; repeat <= 2; repeat++) {
            ConcurrentReachabilityExplorer.Result result = explorer.explore(source, machine);
            ReachabilityGraph candidate = result.graph();
            check(candidate.equals(oracle), "Concurrent graph differs from sequential oracle (run "
                    + repeat + "): missing states=" + difference(oracle.states(), candidate.states())
                    + ", extra states=" + difference(candidate.states(), oracle.states())
                    + ", missing edges=" + difference(oracle.edges(), candidate.edges())
                    + ", extra edges=" + difference(candidate.edges(), oracle.edges()));
            check(candidate.states().size() == 6 && candidate.edges().size() == 10,
                    "Wrong IncFlip cardinalities");
            check(result.workerExpansions().size() == 2
                            && result.workerExpansions().get(0) > 0
                            && result.workerExpansions().get(1) > 0,
                    "Both ProB workers must expand a non-initial state");
            check(result.workerExpansions().stream().mapToInt(Integer::intValue).sum() + 1 == 6,
                    "A state was logically expanded more than once (or not at all)");
            System.out.printf("PASS: CONCURRENT IncFlip run=%d states=%d transitions=%d "
                            + "expansions=%s bootstrapMs=%.3f explorationMs=%.3f "
                            + "cleanupMs=%.3f totalMs=%.3f%n",
                    repeat, candidate.states().size(), candidate.edges().size(),
                    result.workerExpansions(), result.bootstrapNanos() / 1e6,
                    result.explorationNanos() / 1e6, result.cleanupNanos() / 1e6,
                    result.totalNanos() / 1e6);
        }
    }

    private static <T> java.util.Set<T> difference(java.util.Set<T> left, java.util.Set<T> right) {
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
