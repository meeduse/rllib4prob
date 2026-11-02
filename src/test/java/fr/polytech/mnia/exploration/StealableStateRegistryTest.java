package fr.polytech.mnia.exploration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import fr.polytech.mnia.exploration.concurrent.ReplicatedPrefix;
import fr.polytech.mnia.exploration.concurrent.StealableStateRegistry;
import fr.polytech.mnia.exploration.model.ActionLabel;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.StateKey;

/** Regression tests for atomic, generation-checked task reassignment. */
public final class StealableStateRegistryTest {
    private StealableStateRegistryTest() { }

    private static StateKey key(int x) {
        return new StateKey(Map.of("x", "integer#1:1:" + x));
    }

    private static LabeledTransition edge(StateKey source, String name, StateKey target) {
        return new LabeledTransition(source, new ActionLabel(name, List.of(), List.of()), target);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        StateKey root = key(0), a = key(1), b = key(2);
        LabeledTransition ra = edge(root, "a", a);
        LabeledTransition rb = edge(root, "b", b);
        ReplicatedPrefix prefix = new ReplicatedPrefix(root, Map.of(root, 0, a, 1, b, 1), Set.of(ra, rb));
        StealableStateRegistry registry = new StealableStateRegistry(2);
        registry.commitPrefix(prefix, 1);

        // Receiver 0 has (in a real worker) retained the target locally before this CAS.
        var candidate = registry.pendingFor(0).stream().filter(c -> c.key().equals(b))
                .findFirst().orElseThrow();
        check(candidate.owner() == 1 && candidate.generation() == 0, "Invalid initial assignment");
        check(candidate.predecessors().contains(root), "Missing gateway predecessor");
        check(registry.tryReassign(candidate, 0), "First CAS should transfer pending task");
        check(!registry.tryReassign(candidate, 0), "Stale generation must reject second CAS");

        StealableStateRegistry.WorkItem stale = registry.poll(1, 1);
        check(stale != null && !stale.stop() && !registry.start(stale, 1),
                "Old owner's queue item must be harmless");
        var taskA = registry.poll(0, 1);
        check(taskA != null && taskA.key().equals(a) && registry.start(taskA, 0),
                "Original receiver task cannot start");
        // A transition to an already registered destination is never suppressed.
        LabeledTransition ab = edge(a, "toB", b);
        registry.recordEdge(ab);
        registry.recordEdge(ab);
        registry.complete(taskA, 0);
        var taskB = registry.poll(0, 1);
        check(taskB != null && taskB.key().equals(b) && taskB.generation() == 1,
                "New owner must receive generation 1");
        check(registry.start(taskB, 0), "Transferred task did not start");
        LabeledTransition br = edge(b, "back", root);
        registry.recordEdge(br);
        registry.complete(taskB, 0);
        registry.awaitCompletion();
        var graph = registry.completedGraph(root);
        check(graph.states().size() == 3 && graph.edges().size() == 4,
                "Incomplete graph or duplicate edge");
        check(registry.unfinished() == 0 && registry.successes() == 1 && registry.attempts() == 2,
                "Incorrect completion or transfer counters");

        // A started expansion cannot be reassigned.
        ReplicatedPrefix oneFrontier = new ReplicatedPrefix(root, Map.of(root, 0, a, 1), Set.of(ra));
        StealableStateRegistry activeRegistry = new StealableStateRegistry(2);
        activeRegistry.commitPrefix(oneFrontier, 1);
        var pending = activeRegistry.pendingFor(1).get(0);
        var owned = activeRegistry.poll(0, 1);
        check(activeRegistry.start(owned, 0), "Owner could not start");
        check(!activeRegistry.tryReassign(pending, 1), "ACTIVE task must never be stolen");
        activeRegistry.complete(owned, 0);
        activeRegistry.completedGraph(root);

        // Force a CAS race: two thieves observe the SAME pending generation.
        for (int run = 0; run < 100; run++) {
            StealableStateRegistry contested = new StealableStateRegistry(3);
            contested.commitPrefix(oneFrontier, 1);
            var snapshot1 = contested.pendingFor(1).get(0);
            var snapshot2 = contested.pendingFor(2).get(0);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            Thread t1 = new Thread(() -> {
                try {
                    go.await();
                    if (contested.tryReassign(snapshot1, 1)) wins.incrementAndGet();
                } catch (InterruptedException e) { throw new AssertionError(e); }
            });
            Thread t2 = new Thread(() -> {
                try {
                    go.await();
                    if (contested.tryReassign(snapshot2, 2)) wins.incrementAndGet();
                } catch (InterruptedException e) { throw new AssertionError(e); }
            });
            t1.start();
            t2.start();
            go.countDown();
            t1.join();
            t2.join();
            check(wins.get() == 1 && contested.successes() == 1,
                    "Race permitted two successful owners");
            check(!contested.start(contested.poll(0, 1), 0),
                    "Old owner could start after transfer");
            var winnerItem1 = contested.poll(1, 1);
            var winnerItem2 = contested.poll(2, 1);
            check((winnerItem1 == null) != (winnerItem2 == null),
                    "Exactly one receiver must get a new work item");
            int winner = winnerItem1 != null ? 1 : 2;
            var token = winnerItem1 != null ? winnerItem1 : winnerItem2;
            check(token.generation() == 1 && contested.start(token, winner),
                    "Winning receiver did not get a valid generation");
            contested.complete(token, winner);
            contested.completedGraph(root);
        }
        System.out.println("PASS: STEAL registry CAS, stale tasks, completed edges, ACTIVE protection, 100 races");
    }
}
