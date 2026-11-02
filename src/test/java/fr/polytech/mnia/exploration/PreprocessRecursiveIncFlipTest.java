package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.List;

import fr.polytech.mnia.exploration.benchmark.PreprocessGraphExplorer;
import fr.polytech.mnia.exploration.benchmark.RecursiveGraphExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/** Actual ProB smoke test; no mocks, no JUnit dependency. */
public final class PreprocessRecursiveIncFlipTest {
    private PreprocessRecursiveIncFlipTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        List<String> variables = List.of("x", "y");
        ReachabilityGraph reference = new SequentialReachabilityExplorer(variables)
                .explore(root, machine).graph();
        if (reference.states().size() != 6 || reference.edges().size() != 10) {
            throw new AssertionError("Incorrect IncFlip reference");
        }
        PreprocessGraphExplorer.Result preprocess =
                new PreprocessGraphExplorer(variables).explore(root, machine);
        if (!reference.equals(preprocess.graph())) {
            throw new AssertionError("PREPROCESS graph differs from sequential IncFlip");
        }
        RecursiveGraphExplorer.Result recursive =
                new RecursiveGraphExplorer(variables).explore(root, machine);
        if (!reference.equals(recursive.graph())) {
            throw new AssertionError("RECURSIVE graph differs from sequential IncFlip");
        }
        if (recursive.discoveredStateCount() != 6 || preprocess.reportedProBNodes() < 6) {
            throw new AssertionError("Native exploration did not discover all six states");
        }
        System.out.printf("PASS: COMPARE IncFlip PREPROCESS states=6 edges=10 "
                        + "nativeReadyMs=%.3f graphReadyMs=%.3f reportedNodes=%d%n",
                preprocess.nativeReadyNanos() / 1e6, preprocess.graphReadyNanos() / 1e6,
                preprocess.reportedProBNodes());
        System.out.printf("PASS: COMPARE IncFlip RECURSIVE states=6 edges=10 "
                        + "nativeReadyMs=%.3f graphReadyMs=%.3f%n",
                recursive.nativeReadyNanos() / 1e6, recursive.graphReadyNanos() / 1e6);
    }
}
