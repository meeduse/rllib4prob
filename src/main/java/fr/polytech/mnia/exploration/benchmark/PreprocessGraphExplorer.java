package fr.polytech.mnia.exploration.benchmark;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import de.prob.check.ConsistencyChecker;
import de.prob.check.IModelCheckListener;
import de.prob.check.IModelCheckingResult;
import de.prob.check.ModelCheckingOptions;
import de.prob.check.ModelCheckingOptions.Options;
import de.prob.check.StateSpaceStats;
import de.prob.statespace.State;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.prob.ProBWorkerSession;

/**
 * Replicates the existing Environment.modelCheck() options in an independently
 * managed ProB session. Reports model-checking and Java graph-extraction costs
 * separately. The existing Environment class is NOT modified or invoked.
 */
public final class PreprocessGraphExplorer {
    private final List<String> variables;

    public record Result(ReachabilityGraph graph, long loadNanos,
                         long nativeCheckNanos, long extractionNanos,
                         long cleanupNanos, long reportedProBNodes,
                         String modelCheckerMessage) {
        public long nativeReadyNanos() { return loadNanos + nativeCheckNanos; }
        public long graphReadyNanos() { return nativeReadyNanos() + extractionNanos; }
        public long endToEndNanos() { return graphReadyNanos() + cleanupNanos; }
    }

    public PreprocessGraphExplorer(List<String> variableNames) {
        this.variables = List.copyOf(variableNames);
    }

    public Result explore(Path sourceRoot, Path relativeMachine) throws Exception {
        long start = System.nanoTime();
        ProBWorkerSession session = ProBWorkerSession.open(sourceRoot, relativeMachine, false);
        long loaded = System.nanoTime();
        ReachabilityGraph graph;
        long checked;
        long graphReady;
        final boolean[] finished = {false};
        final long[] nodes = {-1};
        final String[] message = {""};
        try {
            State initial = session.initialState();
            ConsistencyChecker checker = new ConsistencyChecker(
                    initial.getStateSpace(),
                    new ModelCheckingOptions(Collections.singleton(Options.IGNORE_OTHER_ERRORS)),
                    new IModelCheckListener() {
                        @Override
                        public void updateStats(String jobId, long elapsed,
                                                IModelCheckingResult result, StateSpaceStats stats) {
                            // Intermediate stats are not evidence of completion.
                        }

                        @Override
                        public void isFinished(String jobId, long elapsed,
                                               IModelCheckingResult result, StateSpaceStats stats) {
                            finished[0] = true;
                            if (stats != null) nodes[0] = stats.getNrTotalNodes();
                            if (result != null) message[0] = String.valueOf(result.getMessage());
                        }
                    });
            checker.call();
            checked = System.nanoTime();
            if (!finished[0] || nodes[0] < 1) {
                throw new IllegalStateException(
                        "ProB model checking did not report completion with node statistics");
            }
            // Native ProB checking does not produce a detached Java graph.
            // This separately timed pass materializes it through the Java API.
            graph = new ReferenceGraphExtractor(variables).extract(initial);
            graphReady = System.nanoTime();
        } finally {
            session.close();
        }
        long closed = System.nanoTime();
        return new Result(graph, loaded - start, checked - loaded,
                graphReady - checked, closed - graphReady, nodes[0], message[0]);
    }
}
