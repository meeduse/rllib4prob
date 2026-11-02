package fr.polytech.mnia.exploration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import fr.polytech.mnia.exploration.benchmark.PreprocessGraphExplorer;
import fr.polytech.mnia.exploration.benchmark.RecursiveGraphExplorer;
import fr.polytech.mnia.exploration.concurrent.TimedLocalityAwareReachabilityExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/**
 * Apples-to-apples graph benchmark against native ProB and the legacy DFS.
 *
 * PREPROCESS: timed ProB model checking, then Java API graph materialization.
 * RECURSIVE: isolated reproduction of the legacy state-only DFS, then the
 *            SAME Java API graph materialization as PREPROCESS.
 * CONCURRENT: direct creation of the detached canonical graph.
 *
 * The two baseline nativeReady times are NOT graphReady times. ALL graphReady
 * values refer to an equivalent complete Java graph, with loading included.
 * No existing algorithm, environment, B machine or script is changed.
 *
 * Usage: [repetitions] [workersCsv] [cutDepth] [reassignment]
 *        [csvFile] [warmupRuns]
 */
public final class TicTacToeStrategyComparisonBenchmark {
    private static final Path ROOT = Path.of("src/main/resources");
    private static final Path MACHINE = Path.of("TicTacToe/tictac.mch");
    private static final List<String> VARIABLES = List.of("square", "turn");
    private static final String HEADER = "timestamp,model,strategy,workers,cutDepth,reassignment,"
            + "run,status,states,edges,loadWallMs,nativeExplorationMs,graphExtractionMs,"
            + "prefixMs,parallelExplorationMs,nativeReadyMs,graphReadyMs,cleanupMs,"
            + "endToEndMs,proBReportedNodes,modelCheckerMessage,steals,stealAttempts,"
            + "graphMatchesReference,javaVersion,availableProcessors,error";

    private record Variant(String strategy, int workers) { }
    private record Measurement(ReachabilityGraph graph, String strategy, int workers,
                               long load, long nativeExploration, long extraction,
                               long prefix, long parallelExploration, long cleanup,
                               long nodes, String modelCheckerMessage,
                               long steals, long stealAttempts) {
        long nativeReady() {
            return strategy.equals("CONCURRENT") ? -1L : load + nativeExploration;
        }
        long graphReady() {
            return load + nativeExploration + extraction + prefix + parallelExploration;
        }
        long endToEnd() { return graphReady() + cleanup; }
    }

    private TicTacToeStrategyComparisonBenchmark() { }

    public static void main(String[] args) throws Exception {
        int repetitions = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        List<Integer> counts = args.length > 1 ? Arrays.stream(args[1].split(","))
                .map(String::trim).map(Integer::parseInt).distinct().toList() : List.of(1, 2);
        int cutDepth = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        boolean reassign = args.length > 3 && strictBoolean(args[3]);
        Path output = Path.of(args.length > 4 ? args[4] : "results/concurrent/comparison.csv");
        int warmups = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        if (repetitions < 1 || warmups < 0 || cutDepth < 1 || counts.isEmpty()
                || counts.stream().anyMatch(n -> n < 1)) {
            throw new IllegalArgumentException(
                    "Expected repetitions>=1, warmups>=0, cutDepth>=1 and positive workers");
        }
        if (!Files.isRegularFile(ROOT.resolve(MACHINE))) {
            throw new IOException("Run from Maven project root; file missing: " + ROOT.resolve(MACHINE));
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path complete = Path.of(output.toString() + ".complete");
        // A previous successful run must not be confused with a partial rerun.
        if (Files.exists(output) || Files.exists(complete)) {
            throw new IOException("Refusing to overwrite an existing campaign: " + output);
        }
        Files.writeString(output, HEADER + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("PREPROCESS", 1));
        variants.add(new Variant("RECURSIVE", 1));
        for (int p : counts) variants.add(new Variant("CONCURRENT", p));

        try {
            // An independent, untimed graph oracle; NEVER part of any strategy's
            // reported wall-clock duration. Its graph is retained only as Java data.
            ReachabilityGraph reference = new SequentialReachabilityExplorer(VARIABLES)
                    .explore(ROOT, MACHINE).graph();
            if (reference.states().size() != 5478 || reference.edges().size() != 16167) {
                throw new IllegalStateException("Unexpected TicTacToe reference graph: "
                        + reference.states().size() + " states, "
                        + reference.edges().size() + " edges");
            }
            System.out.printf("REFERENCE TicTacToe states=%d edges=%d%n",
                    reference.states().size(), reference.edges().size());

            for (int warm = 1; warm <= warmups; warm++) {
                for (Variant variant : variants) {
                    Measurement result = measure(variant, cutDepth, reassign);
                    requireEqual(reference, result.graph, variant, "warmup=" + warm);
                    checkPreprocessNodeCount(result);
                    System.out.printf(Locale.ROOT,
                            "WARMUP strategy=%s p=%d graphReadyMs=%.3f (not recorded)%n",
                            variant.strategy, variant.workers, ms(result.graphReady()));
                }
            }
            for (int run = 1; run <= repetitions; run++) {
                List<Variant> rotated = new ArrayList<>(variants);
                Collections.rotate(rotated, -((run - 1) % rotated.size()));
                for (Variant variant : rotated) {
                    Measurement result;
                    try {
                        result = measure(variant, cutDepth, reassign);
                        requireEqual(reference, result.graph, variant, "run=" + run);
                        checkPreprocessNodeCount(result);
                        write(output, row(result, run, cutDepth, reassign));
                    } catch (Exception | Error failure) {
                        write(output, failureRow(variant, run, cutDepth, reassign, failure));
                        throw failure;
                    }
                    if (variant.strategy.equals("CONCURRENT")) {
                        System.out.printf(Locale.ROOT,
                                "PASS: COMPARE strategy=CONCURRENT p=%d run=%d states=%d edges=%d "
                                        + "loadWallMs=%.3f prefixMs=%.3f exploreMs=%.3f "
                                        + "graphReadyMs=%.3f steals=%d/%d%n",
                                variant.workers, run, result.graph.states().size(),
                                result.graph.edges().size(), ms(result.load), ms(result.prefix),
                                ms(result.parallelExploration), ms(result.graphReady()),
                                result.steals, result.stealAttempts);
                    } else {
                        System.out.printf(Locale.ROOT,
                                "PASS: COMPARE strategy=%s run=%d states=%d edges=%d "
                                        + "loadWallMs=%.3f nativeMs=%.3f extractMs=%.3f "
                                        + "nativeReadyMs=%.3f graphReadyMs=%.3f%n",
                                variant.strategy, run, result.graph.states().size(),
                                result.graph.edges().size(), ms(result.load),
                                ms(result.nativeExploration), ms(result.extraction),
                                ms(result.nativeReady()), ms(result.graphReady()));
                    }
                }
            }
            Files.writeString(complete, "PASS " + Instant.now() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("COMPARISON COMPLETE: " + output.toAbsolutePath());
        } catch (Exception | Error failure) {
            System.err.println("COMPARISON INVALID: " + failure);
            System.err.println("No valid .complete marker for " + output.toAbsolutePath());
            throw failure;
        }
    }

    private static Measurement measure(Variant variant, int cutDepth, boolean reassign)
            throws Exception {
        if (variant.strategy.equals("PREPROCESS")) {
            PreprocessGraphExplorer.Result x = new PreprocessGraphExplorer(VARIABLES)
                    .explore(ROOT, MACHINE);
            return new Measurement(x.graph(), "PREPROCESS", 1, x.loadNanos(),
                    x.nativeCheckNanos(), x.extractionNanos(), 0L, 0L,
                    x.cleanupNanos(), x.reportedProBNodes(), x.modelCheckerMessage(), 0, 0);
        }
        if (variant.strategy.equals("RECURSIVE")) {
            RecursiveGraphExplorer.Result x = new RecursiveGraphExplorer(VARIABLES)
                    .explore(ROOT, MACHINE);
            return new Measurement(x.graph(), "RECURSIVE", 1, x.loadNanos(),
                    x.depthFirstSearchNanos(), x.extractionNanos(), 0L, 0L,
                    x.cleanupNanos(), -1, "", 0, 0);
        }
        if (variant.strategy.equals("CONCURRENT")) {
            TimedLocalityAwareReachabilityExplorer.Result x =
                    new TimedLocalityAwareReachabilityExplorer(VARIABLES, variant.workers,
                            cutDepth, reassign).explore(ROOT, MACHINE);
            return new Measurement(x.graph(), "CONCURRENT", variant.workers,
                    x.loadWallNanos(), 0L, 0L, x.prefixNanos(),
                    x.explorationNanos(), x.cleanupNanos(), -1, "",
                    x.successfulReassignments(), x.reassignmentAttempts());
        }
        throw new IllegalArgumentException("Unknown strategy: " + variant.strategy);
    }

    private static void checkPreprocessNodeCount(Measurement m) {
        if (m.strategy.equals("PREPROCESS") && m.nodes != 5479) {
            throw new IllegalStateException("Unexpected PREPROCESS node count: " + m.nodes
                    + " (TicTacToe reference in the current project reports 5479 ProB nodes, "
                    + "including its internal root). Do not treat native exploration as complete.");
        }
    }

    private static void requireEqual(ReachabilityGraph expected, ReachabilityGraph actual,
                                     Variant v, String run) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Graph mismatch strategy=" + v.strategy
                    + " workers=" + v.workers + " " + run
                    + " expected=" + expected.states().size() + "/" + expected.edges().size()
                    + " actual=" + actual.states().size() + "/" + actual.edges().size());
        }
    }

    private static boolean strictBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("Expected true or false, got " + value);
    }

    private static String row(Measurement m, int run, int depth, boolean reassign) {
        return String.join(",", csv(Instant.now().toString()), "TicTacToe", m.strategy,
                Integer.toString(m.workers), Integer.toString(depth), Boolean.toString(reassign),
                Integer.toString(run), "PASS", Integer.toString(m.graph.states().size()),
                Integer.toString(m.graph.edges().size()), time(m.load),
                timeOrBlank(m.nativeExploration, !m.strategy.equals("CONCURRENT")),
                timeOrBlank(m.extraction, !m.strategy.equals("CONCURRENT")),
                timeOrBlank(m.prefix, m.strategy.equals("CONCURRENT")),
                timeOrBlank(m.parallelExploration, m.strategy.equals("CONCURRENT")),
                timeOrBlank(m.nativeReady(), !m.strategy.equals("CONCURRENT")),
                time(m.graphReady()), time(m.cleanup), time(m.endToEnd()),
                m.nodes < 0 ? "" : Long.toString(m.nodes), csv(m.modelCheckerMessage),
                Long.toString(m.steals), Long.toString(m.stealAttempts), "true",
                csv(System.getProperty("java.version")),
                Integer.toString(Runtime.getRuntime().availableProcessors()), csv(""));
    }

    private static String failureRow(Variant v, int run, int depth, boolean reassign,
                                     Throwable error) {
        return String.join(",", csv(Instant.now().toString()), "TicTacToe", v.strategy,
                Integer.toString(v.workers), Integer.toString(depth), Boolean.toString(reassign),
                Integer.toString(run), "FAILED", "", "", "", "", "", "", "", "",
                "", "", "", "", csv(""), "", "", "false",
                csv(System.getProperty("java.version")),
                Integer.toString(Runtime.getRuntime().availableProcessors()), csv(error.toString()));
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }
    private static String time(long nanos) { return String.format(Locale.ROOT, "%.3f", ms(nanos)); }
    private static String timeOrBlank(long nanos, boolean used) { return used ? time(nanos) : ""; }
    private static String csv(String text) {
        return "\"" + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }
    private static void write(Path file, String row) throws IOException {
        Files.writeString(file, row + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
