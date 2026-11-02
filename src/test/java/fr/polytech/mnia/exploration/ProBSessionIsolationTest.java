package fr.polytech.mnia.exploration;

import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import de.prob.statespace.State;
import de.prob.statespace.StateSpace;
import fr.polytech.mnia.exploration.prob.ProBWorkerSession;

/** Run with Maven exec:java using test classpath; no modifications to pom.xml. */
public final class ProBSessionIsolationTest {
    private ProBSessionIsolationTest() {}

    private record Snapshot(Path directory, Path machine, StateSpace space,
                            String initialValuation, Set<String> successors) {}

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        AtomicInteger nextId = new AtomicInteger();
        ConcurrentHashMap<Integer, Path> liveCopies = new ConcurrentHashMap<>();
        CyclicBarrier simultaneous = new CyclicBarrier(2, () -> {
            try {
                Path a = liveCopies.get(0);
                Path b = liveCopies.get(1);
                check(a != null && b != null, "A worker did not load its local copy");
                check(!Files.isSameFile(a, b), "Both ProB sessions load the same physical file");
            } catch (IOException error) {
                throw new IllegalStateException("Cannot inspect model copies", error);
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Snapshot> work = () -> {
                int workerId = nextId.getAndIncrement();
                try (ProBWorkerSession session = ProBWorkerSession.open(source, machine, false)) {
                    Path copy = session.localMachinePath();
                    Path directory = session.workspaceDirectory();
                    StateSpace space = session.stateSpace();
                    liveCopies.put(workerId, copy);
                    State state = session.initialState();
                    String start = state.eval("x") + "," + state.eval("y");
                    simultaneous.await(60, TimeUnit.SECONDS);
                    Set<String> successors = new HashSet<>();
                    state.getOutTransitions().forEach(t -> {
                        State dest = t.getDestination();
                        successors.add(t.getName() + ":" + dest.eval("x") + "," + dest.eval("y"));
                    });
                    return new Snapshot(directory, copy, space, start, successors);
                }
            };
            Future<Snapshot> first = executor.submit(work);
            Future<Snapshot> second = executor.submit(work);
            Snapshot a = first.get(120, TimeUnit.SECONDS);
            Snapshot b = second.get(120, TimeUnit.SECONDS);
            check(!a.machine.equals(b.machine), "Machine copies use the same path");
            check(!a.directory.equals(b.directory), "Workspace directories are not distinct");
            check(a.space != b.space, "StateSpace objects are shared");
            check(a.initialValuation.equals("0,0") && a.initialValuation.equals(b.initialValuation),
                    "Initial valuations differ");
            Set<String> expected = Set.of("inc:1,0", "flip:0,1");
            check(a.successors.equals(expected) && b.successors.equals(expected),
                    "Incorrect or different successors: " + a.successors + " / " + b.successors);
            check(Files.notExists(a.directory) && Files.notExists(b.directory),
                    "Private workspaces were not deleted");
            System.out.println("PASS: two independent ProB sessions, two B copies, equal successors, cleanup");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
