package fr.polytech.mnia.exploration;

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

import fr.polytech.mnia.exploration.GraphBenchmarkCatalog.Spec;
import fr.polytech.mnia.exploration.benchmark.PreprocessGraphExplorer;
import fr.polytech.mnia.exploration.benchmark.RecursiveGraphExplorer;
import fr.polytech.mnia.exploration.concurrent.TimedLocalityAwareReachabilityExplorer;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.sequential.SequentialReachabilityExplorer;

/**
 * One B machine per JVM invocation; compare complete Java graphs under all
 * strategies. A separate reference graph is built ONCE, outside all measured
 * durations. The PREPROCESS/RECURSIVE Java extraction is explicitly timed.
 *
 * Usage: <modelId> <repetitions> <workersCsv> <cutDepth> <reassignment>
 *        <csvFile> <warmupRuns>
 *
 * An output .complete marker is created only after EVERY measured run succeeds.
 * If the process is killed or times out, the CSV is partial and must not be
 * used for published aggregate statistics.
 */
public final class GraphConstructionCampaign {
    private static final Path ROOT = Path.of("src/main/resources");
    private static final String HEADER = "timestamp,benchmark,machine,strategy,workers,cutDepth,reassignment,"
        + "run,status,states,edges,loadWallMs,nativeExplorationMs,graphExtractionMs,"
        + "prefixMs,parallelExplorationMs,nativeReadyMs,graphReadyMs,cleanupMs,"
        + "endToEndMs,proBReportedNodes,modelCheckerMessage,steals,stealAttempts,"
        + "gatewayRegenerations,graphMatchesReference,javaVersion,availableProcessors,error";

    private record Variant(String name, int workers) {
        @Override public String toString() { return name + (name.equals("CONCURRENT") ? "-" + workers : ""); }
    }

    private record Measurement(ReachabilityGraph graph, Variant variant,
                               long loadNanos, long nativeNanos, long extractionNanos,
                               long prefixNanos, long concurrentNanos, long cleanupNanos,
                               long reportedNodes, String checkerMessage,
                               long steals, long attempts, long gatewayRegenerations) {
        long nativeReadyNanos() { return variant.name.equals("CONCURRENT") ? -1 : loadNanos + nativeNanos; }
        long graphReadyNanos() { return loadNanos + nativeNanos + extractionNanos + prefixNanos + concurrentNanos; }
        long endToEndNanos() { return graphReadyNanos() + cleanupNanos; }
    }

    private GraphConstructionCampaign() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException("Usage: modelId repetitions workersCsv cutDepth"
                + " reassignment outputCsv warmupRuns");
        }
        Spec spec = GraphBenchmarkCatalog.require(args[0]);
        int repetitions = Integer.parseInt(args[1]);
        List<Integer> counts = Arrays.stream(args[2].split(","))
            .map(String::trim).map(Integer::parseInt).distinct().toList();
        int depth = Integer.parseInt(args[3]);
        boolean reassignment = switch (args[4].toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("reassignment must be true/false");
        };
        Path csv = Path.of(args[5]);
        int warmups = Integer.parseInt(args[6]);
        if (repetitions < 1 || warmups < 0 || depth < 1 || counts.isEmpty()
                || counts.stream().anyMatch(x -> x < 1 || x > 64)) {
            throw new IllegalArgumentException("Invalid repetition, worker, or prefix settings");
        }
        if (!Files.isRegularFile(ROOT.resolve(spec.machine()))) {
            throw new IllegalArgumentException("Missing B machine: " + ROOT.resolve(spec.machine()));
        }
        Files.createDirectories(csv.toAbsolutePath().getParent());
        Path complete = Path.of(csv + ".complete");
        if (Files.exists(csv) || Files.exists(complete)) {
            throw new IllegalArgumentException("Refusing to overwrite existing results: " + csv);
        }
        Files.writeString(csv, HEADER + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("PREPROCESS", 1));
        variants.add(new Variant("RECURSIVE", 1));
        for (int p : counts) variants.add(new Variant("CONCURRENT", p));
        try {
            long referenceStart = System.nanoTime();
            ReachabilityGraph reference = new SequentialReachabilityExplorer(spec.variables())
                .explore(ROOT, spec.machine()).graph();
            requireExpected(spec, reference, "SEQUENTIAL REFERENCE");
            System.out.printf(Locale.ROOT,
                "REFERENCE model=%s states=%d edges=%d referenceMs=%.3f (excluded from measurements)%n",
                spec.id(), reference.states().size(), reference.edges().size(),
                ms(System.nanoTime() - referenceStart));

            for (int warm = 1; warm <= warmups; warm++) {
                List<Variant> order = new ArrayList<>(variants);
                Collections.rotate(order, -(warm % order.size()));
                for (Variant v : order) {
                    Measurement measurement = measure(spec, v, depth, reassignment);
                    validate(spec, reference, measurement, "warmup=" + warm);
                    System.out.printf(Locale.ROOT,
                        "WARMUP model=%s strategy=%s graphReadyMs=%.3f (not recorded)%n",
                        spec.id(), v, ms(measurement.graphReadyNanos()));
                }
            }
            for (int run = 1; run <= repetitions; run++) {
                List<Variant> order = new ArrayList<>(variants);
                Collections.rotate(order, -((run - 1) % order.size()));
                for (Variant v : order) {
                    try {
                        Measurement m = measure(spec, v, depth, reassignment);
                        validate(spec, reference, m, "run=" + run);
                        append(csv, csvRow(spec, m, run, depth, reassignment));
                        System.out.printf(Locale.ROOT,
                            "PASS: model=%s strategy=%s run=%d states=%d edges=%d "
                                + "loadWallMs=%.3f nativeMs=%s extractMs=%s "
                                + "prefixMs=%s exploreMs=%s graphReadyMs=%.3f cleanupMs=%.3f%n",
                            spec.id(), v, run, m.graph.states().size(), m.graph.edges().size(),
                            ms(m.loadNanos), optionalMs(m.nativeNanos, !v.name.equals("CONCURRENT")),
                            optionalMs(m.extractionNanos, !v.name.equals("CONCURRENT")),
                            optionalMs(m.prefixNanos, v.name.equals("CONCURRENT")),
                            optionalMs(m.concurrentNanos, v.name.equals("CONCURRENT")),
                            ms(m.graphReadyNanos()), ms(m.cleanupNanos));
                    } catch (Exception | Error failure) {
                        append(csv, failedRow(spec, v, run, depth, reassignment, failure));
                        throw failure;
                    }
                }
            }
            // This signal, and never merely the CSV's existence, indicates success.
            Files.writeString(complete, "PASS " + Instant.now() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.printf("CAMPAIGN COMPLETE model=%s csv=%s%n", spec.id(), csv.toAbsolutePath());
        } catch (Exception | Error failure) {
            System.err.println("CAMPAIGN INVALID model=" + spec.id() + ": " + failure);
            System.err.println("No valid completion marker for " + csv.toAbsolutePath());
            throw failure;
        }
    }

    private static Measurement measure(Spec spec, Variant v, int depth, boolean reassign) throws Exception {
        if (v.name.equals("PREPROCESS")) {
            var x = new PreprocessGraphExplorer(spec.variables()).explore(ROOT, spec.machine());
            return new Measurement(x.graph(), v, x.loadNanos(), x.nativeCheckNanos(),
                x.extractionNanos(), 0, 0, x.cleanupNanos(), x.reportedProBNodes(),
                x.modelCheckerMessage(), 0, 0, 0);
        }
        if (v.name.equals("RECURSIVE")) {
            var x = new RecursiveGraphExplorer(spec.variables()).explore(ROOT, spec.machine());
            return new Measurement(x.graph(), v, x.loadNanos(), x.depthFirstSearchNanos(),
                x.extractionNanos(), 0, 0, x.cleanupNanos(), -1, "", 0, 0, 0);
        }
        var x = new TimedLocalityAwareReachabilityExplorer(
            spec.variables(), v.workers, depth, reassign).explore(ROOT, spec.machine());
        return new Measurement(x.graph(), v, x.loadWallNanos(), 0, 0,
            x.prefixNanos(), x.explorationNanos(), x.cleanupNanos(), -1, "",
            x.successfulReassignments(), x.reassignmentAttempts(), x.gatewayRegenerations());
    }

    private static void validate(Spec spec, ReachabilityGraph oracle, Measurement m, String invocation) {
        requireExpected(spec, m.graph, m.variant + " " + invocation);
        if (!oracle.equals(m.graph)) {
            throw new IllegalStateException("Graph mismatch model=" + spec.id() + " strategy=" + m.variant
                + " " + invocation + " expected " + oracle.states().size() + "/" + oracle.edges().size()
                + " observed " + m.graph.states().size() + "/" + m.graph.edges().size());
        }
        if (m.variant.name.equals("PREPROCESS") && m.reportedNodes < spec.expectedStates()) {
            throw new IllegalStateException("Incomplete model checker statistics: " + m.reportedNodes);
        }
    }

    private static void requireExpected(Spec spec, ReachabilityGraph g, String context) {
        if (g.states().size() != spec.expectedStates() || g.edges().size() != spec.expectedEdges()) {
            throw new IllegalStateException("Unexpected graph size for " + spec.id() + " (" + context + "): "
                + g.states().size() + " states, " + g.edges().size() + " edges; previous audited counts "
                + spec.expectedStates() + "/" + spec.expectedEdges()
                + ". Investigate configuration and variable lists before benchmarking.");
        }
    }

    private static String csvRow(Spec spec, Measurement m, int run, int depth, boolean reassign) {
        boolean concurrent = m.variant.name.equals("CONCURRENT");
        return String.join(",", csv(Instant.now().toString()), spec.id(), csv(spec.machine().toString()),
            m.variant.name, Integer.toString(m.variant.workers), Integer.toString(depth),
            Boolean.toString(reassign), Integer.toString(run), "PASS",
            Integer.toString(m.graph.states().size()), Integer.toString(m.graph.edges().size()),
            nanos(m.loadNanos), concurrent ? "" : nanos(m.nativeNanos),
            concurrent ? "" : nanos(m.extractionNanos),
            concurrent ? nanos(m.prefixNanos) : "", concurrent ? nanos(m.concurrentNanos) : "",
            concurrent ? "" : nanos(m.nativeReadyNanos()), nanos(m.graphReadyNanos()),
            nanos(m.cleanupNanos), nanos(m.endToEndNanos()),
            m.reportedNodes < 0 ? "" : Long.toString(m.reportedNodes), csv(m.checkerMessage),
            Long.toString(m.steals), Long.toString(m.attempts),
            Long.toString(m.gatewayRegenerations), "true", csv(System.getProperty("java.version")),
            Integer.toString(Runtime.getRuntime().availableProcessors()), csv(""));
    }

    private static String failedRow(Spec spec, Variant v, int run, int depth, boolean reassign,
                                    Throwable failure) {
        String[] fields = new String[29];
        Arrays.fill(fields, "");
        fields[0] = csv(Instant.now().toString());
        fields[1] = spec.id();
        fields[2] = csv(spec.machine().toString());
        fields[3] = v.name;
        fields[4] = Integer.toString(v.workers);
        fields[5] = Integer.toString(depth);
        fields[6] = Boolean.toString(reassign);
        fields[7] = Integer.toString(run);
        fields[8] = "FAILED";
        fields[25] = "false";
        fields[26] = csv(System.getProperty("java.version"));
        fields[27] = Integer.toString(Runtime.getRuntime().availableProcessors());
        fields[28] = csv(failure.toString());
        return String.join(",", fields);
    }

    private static void append(Path path, String line) throws Exception {
        Files.writeString(path, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private static String nanos(long value) {
        return String.format(Locale.ROOT, "%.3f", ms(value));
    }

    private static String optionalMs(long value, boolean available) {
        return available ? nanos(value) : "-";
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }
}
