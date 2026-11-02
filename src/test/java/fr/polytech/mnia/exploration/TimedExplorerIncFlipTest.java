package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.List;

import fr.polytech.mnia.exploration.concurrent.LocalityAwareReachabilityExplorer;
import fr.polytech.mnia.exploration.concurrent.TimedLocalityAwareReachabilityExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/** Integration test: the additional timing barriers must preserve the stage-7 graph. */
public final class TimedExplorerIncFlipTest {
    private TimedExplorerIncFlipTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        List<String> variables = List.of("x", "y");
        ReachabilityGraph reference = new SequentialReachabilityExplorer(variables)
                .explore(root, machine).graph();
        if (reference.states().size() != 6 || reference.edges().size() != 10) {
            throw new AssertionError("Unexpected IncFlip reference graph");
        }
        for (int workers : new int[] {1, 2, 4}) {
            for (boolean reassignment : new boolean[] {false, true}) {
                TimedLocalityAwareReachabilityExplorer.Result result =
                        new TimedLocalityAwareReachabilityExplorer(variables, workers, 1, reassignment)
                                .explore(root, machine);
                if (!reference.equals(result.graph())) {
                    throw new AssertionError("Timed explorer graph differs from sequential reference");
                }
                if (result.workerLoadNanos().size() != workers ||
                        result.workerPrefixNanos().size() != workers ||
                        result.workerLoadNanos().stream().anyMatch(n -> n <= 0L) ||
                        result.workerPrefixNanos().stream().anyMatch(n -> n <= 0L) ||
                        result.graphReadyNanos() != result.loadWallNanos() + result.prefixNanos()
                                + result.explorationNanos() ||
                        result.endToEndNanos() < result.graphReadyNanos()) {
                    throw new AssertionError("Invalid phase timestamps or load statistics");
                }
                System.out.printf("PASS: TIMED IncFlip p=%d stealing=%s "
                                + "loadWallMs=%.3f prefixMs=%.3f exploreMs=%.3f "
                                + "graphReadyMs=%.3f cleanupMs=%.3f workerLoadsMs=%s%n",
                        workers, reassignment, result.loadWallNanos() / 1e6,
                        result.prefixNanos() / 1e6, result.explorationNanos() / 1e6,
                        result.graphReadyNanos() / 1e6, result.cleanupNanos() / 1e6,
                        result.workerLoadNanos().stream().map(n -> String.format("%.3f", n / 1e6)).toList());
            }
        }
        ReachabilityGraph prior = new LocalityAwareReachabilityExplorer(variables, 2, 1, false)
                .explore(root, machine).graph();
        if (!prior.equals(reference)) {
            throw new AssertionError("Previous step-7 explorer differs from timed explorer reference");
        }
        System.out.println("PASS: TIMED explorer is graph-equivalent to original step-7 explorer");
    }
}
