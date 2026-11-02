package fr.polytech.mnia.exploration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import fr.polytech.mnia.exploration.concurrent.BoundedStateRegistry;
import fr.polytech.mnia.exploration.model.ActionLabel;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.StateKey;

/** Pure Java barrier, atomic registration, collision, back-edge and boundary tests. */
public final class BoundedStateRegistryTest {
    private BoundedStateRegistryTest() { }

    public static void main(String[] args) throws Exception {
        StateKey root = key(0), left = key(1), right = key(2), shared = key(3);
        BoundedStateRegistry registry = new BoundedStateRegistry(2, 2);
        LabeledTransition p0 = edge(root, "goL", left);
        LabeledTransition p1 = edge(root, "goR", right);
        registry.initialize(root, Map.of(root, 0, left, 1, right, 1), Set.of(p0, p1), 1);
        List<BoundedStateRegistry.Task> a = registry.tasksForLevel(1, 0);
        List<BoundedStateRegistry.Task> b = registry.tasksForLevel(1, 1);
        check(a.size() == 1 && b.size() == 1, "Frontier not divided between workers");
        try {
            registry.finishLevel(1);
            throw new AssertionError("BFS barrier must reject unfinished tasks");
        } catch (IllegalStateException expected) { }

        CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger registered = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t0 = worker(registry, a.get(0), shared, ready, release, registered, failure);
        Thread t1 = worker(registry, b.get(0), shared, ready, release, registered, failure);
        t0.start(); t1.start(); ready.await(); release.countDown();
        t0.join(); t1.join();
        if (failure.get() != null) throw new AssertionError("Concurrent registration failed", failure.get());
        check(registered.get() == 1, "Duplicate logical successor registration");
        registry.finishLevel(1);
        check(registry.levels().get(shared) == 2, "Wrong boundary depth");
        check(registry.completedGraph().states().size() == 4, "Missing state");
        check(registry.completedGraph().edges().size() == 5,
                "Missing distinct incoming or back transition");
        System.out.println("PASS: BOUNDED registry CAS-like deduplication, BFS barrier, back-edges, boundary");
    }

    private static Thread worker(BoundedStateRegistry registry, BoundedStateRegistry.Task task,
                                 StateKey shared, CountDownLatch ready, CountDownLatch release,
                                 AtomicInteger registered, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                ready.countDown();
                release.await();
                registry.start(task);
                if (registry.registerSuccessor(shared, 2, task.owner())) registered.incrementAndGet();
                registry.recordEdge(edge(task.key(), "join", shared));
                // A previously discovered destination never suppresses its incoming edge.
                if (task.owner() == 1) registry.recordEdge(edge(task.key(), "back", key(0)));
                registry.complete(task);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "registry-test-" + task.owner());
    }

    private static StateKey key(int id) { return new StateKey(Map.of("x", Integer.toString(id))); }
    private static LabeledTransition edge(StateKey from, String op, StateKey to) {
        return new LabeledTransition(from, new ActionLabel(op, List.of(), List.of()), to);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
