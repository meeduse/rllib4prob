package fr.polytech.mnia.exploration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import fr.polytech.mnia.exploration.concurrent.ConcurrentStateRegistry;
import fr.polytech.mnia.exploration.model.ActionLabel;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.ReachabilityGraph;
import fr.polytech.mnia.exploration.model.StateKey;

/** Pure-Java concurrency checks, executable without ProB. */
public final class ConcurrentStateRegistryTest {
    private ConcurrentStateRegistryTest() { }

    public static void main(String[] args) throws Exception {
        StateKey root = key("initial");
        StateKey shared = key("shared");
        ConcurrentStateRegistry registry = new ConcurrentStateRegistry(2);
        registry.registerCompletedInitial(root);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = () -> {
                go.await();
                return registry.registerPending(shared, 0);
            };
            Callable<Boolean> second = () -> {
                go.await();
                return registry.registerPending(shared, 1);
            };
            Future<Boolean> a = executor.submit(first);
            Future<Boolean> b = executor.submit(second);
            go.countDown();
            check(a.get() != b.get(), "Exactly one worker must own the new state");
            check(registry.totalRegisteredStates() == 2, "Duplicate logical state");
            check(registry.unfinishedTasks() == 1, "Duplicate pending task");

            // Both distinct incoming edges must exist despite one shared destination.
            LabeledTransition firstEdge = edge(root, "go", shared);
            LabeledTransition secondEdge = edge(root, "return", shared);
            registry.recordEdge(firstEdge);
            registry.recordEdge(secondEdge);
            registry.recordEdge(firstEdge);

            int owner = a.get() ? 0 : 1;
            ConcurrentStateRegistry.WorkItem task = registry.take(owner);
            check(!task.isStop() && task.key().equals(shared), "Incorrect owner queue");
            registry.start(shared, owner);
            registry.complete(shared, owner);
            check(registry.take(0).isStop() && registry.take(1).isStop(),
                    "Completion notification missing");
            ReachabilityGraph graph = registry.completedGraph(root);
            check(graph.states().equals(Set.of(root, shared)), "Incorrect states");
            check(graph.edges().equals(Set.of(firstEdge, secondEdge)), "Incorrect edges");
            check(registry.unfinishedTasks() == 0, "Pending tasks remain");
            System.out.println("PASS: CONCURRENT registry atomic deduplication, complete edges, termination");
        } finally {
            executor.shutdownNow();
        }
    }

    private static StateKey key(String value) {
        return new StateKey(Map.of("key", value));
    }

    private static LabeledTransition edge(StateKey from, String action, StateKey to) {
        return new LabeledTransition(from, new ActionLabel(action, List.of(), List.of()), to);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
