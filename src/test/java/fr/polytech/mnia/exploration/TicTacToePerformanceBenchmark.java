package fr.polytech.mnia.exploration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import fr.polytech.mnia.exploration.concurrent.TimedLocalityAwareReachabilityExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/**
 * Step 8: controlled TicTacToe graph benchmark, *not* an RL learning benchmark.
 *
 * Usage: TicTacToePerformanceBenchmark [repetitions] [workersCSV] [cutDepth]
 *        [reassignmentEnabled] [outputCsv]
 * e.g. 2 1,2,4 1 false results/concurrent/tictactoe.csv
 *
 * Each timed run must equal the complete canonical sequential reference graph.
 * A CSV is append-only; the .complete marker is written ONLY if every run succeeds.
 */
public final class TicTacToePerformanceBenchmark {
    private static final Path ROOT = Path.of("src/main/resources");
    private static final Path MACHINE = Path.of("TicTacToe/tictac.mch");
    private static final List<String> VARIABLES = List.of("square", "turn");
    private static final String HEADER = "timestamp,model,strategy,workers,cutDepth,reassignment,"
            + "run,status,states,edges,loadWallMs,prefixMs,explorationMs,graphReadyMs,"
            + "cleanupMs,endToEndMs,workerLoadMs,workerPrefixMs,expansions,steals,"
            + "stealAttempts,gateways,exactGraphMatch,javaVersion,availableProcessors,error";

    private TicTacToePerformanceBenchmark() { }

    public static void main(String[] args) throws Exception {
        int repetitions = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        List<Integer> counts = args.length > 1 ? Arrays.stream(args[1].split(","))
                .map(Integer::parseInt).distinct().toList() : List.of(1, 2);
        int cutDepth = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        boolean reassign = args.length > 3 && parseBooleanStrict(args[3]);
        Path output = Path.of(args.length > 4 ? args[4] : "results/concurrent/tictactoe.csv");
        if (repetitions < 1 || counts.isEmpty() || counts.stream().anyMatch(p -> p < 1)
                || cutDepth < 1) {
            throw new IllegalArgumentException("repetitions >= 1, workers >= 1, cutDepth >= 1 required");
        }
        if (!Files.isRegularFile(ROOT.resolve(MACHINE))) {
            throw new IOException("TicTacToe machine not found: " + ROOT.resolve(MACHINE)
                    + " (run from the Maven project root)");
        }
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path complete = Path.of(output.toString() + ".complete");
        Files.deleteIfExists(complete);
        if (Files.exists(output)) {
            throw new IOException("Refusing to overwrite existing CSV: " + output
                    + "; use a new output name for each campaign");
        }
        Files.writeString(output, HEADER + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        // The sequential run is the equality oracle and is itself timed.
        // No ProB objects or IDs are shared with the concurrent runs.
        ReachabilityGraph reference = null;
        try {
            for (int run = 1; run <= repetitions; run++) {
                SequentialReachabilityExplorer.Result seq =
                        new SequentialReachabilityExplorer(VARIABLES).explore(ROOT, MACHINE);
                if (reference == null) {
                    reference = seq.graph();
                    System.out.printf("REFERENCE TicTacToe states=%d edges=%d%n",
                            reference.states().size(), reference.edges().size());
                }
                requireEqual(reference, seq.graph(), "SEQUENTIAL", run);
                double seqReady = ms(seq.loadNanos() + seq.explorationNanos());
                writeRow(output, row("SEQUENTIAL", 1, 0, false, run, "PASS", seq.graph(),
                        ms(seq.loadNanos()), 0.0, ms(seq.explorationNanos()), seqReady,
                        ms(seq.cleanupNanos()), ms(seq.totalNanos()), "", "", "", 0, 0, 0,
                        true, ""));
                System.out.printf("PASS: BENCH TicTacToe strategy=SEQUENTIAL run=%d "
                                + "states=%d edges=%d loadWallMs=%.3f explorationMs=%.3f "
                                + "graphReadyMs=%.3f%n", run, seq.graph().states().size(),
                        seq.graph().edges().size(), ms(seq.loadNanos()),
                        ms(seq.explorationNanos()), seqReady);

                // Rotate order between repetitions to reduce systematic warming bias.
                List<Integer> rotated = new ArrayList<>(counts);
                java.util.Collections.rotate(rotated, -(run - 1) % counts.size());
                for (int workers : rotated) {
                    try {
                        TimedLocalityAwareReachabilityExplorer.Result result =
                                new TimedLocalityAwareReachabilityExplorer(VARIABLES, workers,
                                        cutDepth, reassign).explore(ROOT, MACHINE);
                        requireEqual(reference, result.graph(), "CONCURRENT(p=" + workers + ")", run);
                        writeRow(output, row("CONCURRENT", workers, cutDepth, reassign, run,
                                "PASS", result.graph(), ms(result.loadWallNanos()),
                                ms(result.prefixNanos()), ms(result.explorationNanos()),
                                ms(result.graphReadyNanos()), ms(result.cleanupNanos()),
                                ms(result.endToEndNanos()), joinNanos(result.workerLoadNanos()),
                                joinNanos(result.workerPrefixNanos()),
                                result.workerExpansions().toString(),
                                result.successfulReassignments(), result.reassignmentAttempts(),
                                result.gatewayRegenerations(), true, ""));
                        System.out.printf("PASS: BENCH TicTacToe strategy=CONCURRENT p=%d "
                                        + "d=%d run=%d states=%d edges=%d "
                                        + "loadWallMs=%.3f prefixMs=%.3f explorationMs=%.3f "
                                        + "graphReadyMs=%.3f cleanupMs=%.3f steals=%d/%d%n",
                                workers, cutDepth, run, result.graph().states().size(),
                                result.graph().edges().size(), ms(result.loadWallNanos()),
                                ms(result.prefixNanos()), ms(result.explorationNanos()),
                                ms(result.graphReadyNanos()), ms(result.cleanupNanos()),
                                result.successfulReassignments(), result.reassignmentAttempts());
                    } catch (Exception | Error failure) {
                        writeRow(output, row("CONCURRENT", workers, cutDepth, reassign, run,
                                "FAILED", null, Double.NaN, Double.NaN, Double.NaN,
                                Double.NaN, Double.NaN, Double.NaN, "", "", "", 0, 0, 0,
                                false, failure.toString()));
                        throw failure;
                    }
                }
            }
            Files.writeString(complete, "PASS " + Instant.now() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("BENCHMARK COMPLETE: " + output.toAbsolutePath());
        } catch (Exception | Error failure) {
            System.err.println("BENCHMARK INVALID: " + failure);
            System.err.println("Incomplete CSV has NO .complete marker: " + output.toAbsolutePath());
            throw failure;
        }
    }

    private static void requireEqual(ReachabilityGraph expected, ReachabilityGraph actual,
                                     String strategy, int run) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Graph mismatch for " + strategy + " run=" + run
                    + ": expected states=" + expected.states().size()
                    + ", edges=" + expected.edges().size()
                    + ", actual states=" + actual.states().size()
                    + ", edges=" + actual.edges().size());
        }
    }

    private static boolean parseBooleanStrict(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("reassignmentEnabled must be true or false");
    }

    private static double ms(long value) { return value / 1_000_000.0; }

    private static String joinNanos(List<Long> nanos) {
        return nanos.stream().map(n -> String.format(java.util.Locale.ROOT, "%.3f", ms(n)))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String row(String strategy, int workers, int depth, boolean reassign,
                              int run, String status, ReachabilityGraph graph,
                              double load, double prefix, double exploration,
                              double graphReady, double cleanup, double total,
                              String workerLoad, String workerPrefix, String expansions,
                              long steals, long attempts, long gateways,
                              boolean graphMatch, String error) {
        int states = graph == null ? -1 : graph.states().size();
        int edges = graph == null ? -1 : graph.edges().size();
        return String.join(",", csv(Instant.now().toString()), "TicTacToe", strategy,
                Integer.toString(workers), Integer.toString(depth), Boolean.toString(reassign),
                Integer.toString(run), status, Integer.toString(states), Integer.toString(edges),
                number(load), number(prefix), number(exploration), number(graphReady),
                number(cleanup), number(total), csv(workerLoad), csv(workerPrefix),
                csv(expansions), Long.toString(steals), Long.toString(attempts),
                Long.toString(gateways), Boolean.toString(graphMatch),
                csv(System.getProperty("java.version")),
                Integer.toString(Runtime.getRuntime().availableProcessors()), csv(error));
    }

    private static String number(double value) {
        return Double.isFinite(value)
                ? String.format(java.util.Locale.ROOT, "%.3f", value) : "";
    }

    private static String csv(String text) {
        return "\"" + text.replace("\"", "\"\"").replace("\r", " ")
                .replace("\n", " ") + "\"";
    }

    private static void writeRow(Path target, String row) throws IOException {
        Files.writeString(target, row + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }
}
