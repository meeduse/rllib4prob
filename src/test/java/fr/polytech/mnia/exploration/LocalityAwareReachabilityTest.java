package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.List;

import fr.polytech.mnia.exploration.concurrent.LocalityAwareReachabilityExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/** Real ProB test: exact graph equality with and without optional reassignment. */
public final class LocalityAwareReachabilityTest {
    private LocalityAwareReachabilityTest() { }

    private static void check(boolean valid, String description) {
        if (!valid) throw new AssertionError(description);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        List<String> variables = List.of("x", "y");
        ReachabilityGraph reference = new SequentialReachabilityExplorer(variables)
                .explore(root, machine).graph();
        check(reference.states().size() == 6 && reference.edges().size() == 10,
                "Invalid sequential reference");
        for (int p : new int[] {2, 4}) {
            for (int d : new int[] {1, 2}) {
                for (boolean steal : new boolean[] {false, true}) {
                    for (int run = 1; run <= 2; run++) {
                        LocalityAwareReachabilityExplorer.Result result =
                                new LocalityAwareReachabilityExplorer(variables, p, d, steal)
                                        .explore(root, machine);
                        check(reference.equals(result.graph()),
                                "Graph mismatch: p=" + p + " d=" + d + " steal=" + steal);
                        check(result.workerExpansions().stream().mapToInt(Integer::intValue).sum()
                                        + result.completedPrefixStates() == 6,
                                "Logical expansions lost or duplicated");
                        check(result.successfulReassignments() <= result.reassignmentAttempts(),
                                "Invalid reassignment statistics");
                        if (!steal) {
                            check(result.reassignmentAttempts() == 0 &&
                                            result.successfulReassignments() == 0 &&
                                            result.gatewayRegenerations() == 0,
                                    "Reassignment was not disabled");
                        }
                        check(result.graphReadyNanos() >= result.bootstrapNanos() &&
                                        result.endToEndNanos() >= result.graphReadyNanos(),
                                "Invalid phase timings");
                        System.out.printf("PASS: STEAL IncFlip p=%d d=%d enabled=%s run=%d "
                                        + "states=%d edges=%d expansions=%s steals=%d/%d gateways=%d "
                                        + "bootstrapMs=%.3f graphReadyMs=%.3f cleanupMs=%.3f endToEndMs=%.3f%n",
                                p, d, steal, run, result.graph().states().size(),
                                result.graph().edges().size(), result.workerExpansions(),
                                result.successfulReassignments(), result.reassignmentAttempts(),
                                result.gatewayRegenerations(), result.bootstrapNanos() / 1e6,
                                result.graphReadyNanos() / 1e6, result.cleanupNanos() / 1e6,
                                result.endToEndNanos() / 1e6);
                    }
                }
            }
        }
    }
}
