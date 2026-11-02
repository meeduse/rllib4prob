package fr.polytech.mnia.exploration;

import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.polytech.mnia.exploration.concurrent.ConcurrentStateRegistry;
import fr.polytech.mnia.exploration.concurrent.ReplicatedPrefix;
import fr.polytech.mnia.exploration.model.ActionLabel;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.StateKey;

/** Pure Java test of prefix commit and queue invariants (no ProB needed). */
public final class PrefixBootstrapTest {
    private PrefixBootstrapTest() { }

    public static void main(String[] args) throws Exception {
        StateKey root = key(0);
        StateKey left = key(1);
        StateKey right = key(2);
        ActionLabel next = new ActionLabel("next", List.of(), List.of());
        ReplicatedPrefix prefix = new ReplicatedPrefix(root,
                Map.of(root, 0, left, 1, right, 1),
                Set.of(new LabeledTransition(root, next, left),
                       new LabeledTransition(root, next, right)));
        ConcurrentStateRegistry registry = new ConcurrentStateRegistry(2);
        prefix.commitInto(registry, 1, 2);
        check(prefix.completedPrefixStates(1) == 1 && prefix.frontierStates(1) == 2,
                "Wrong committed prefix/frontier counts");
        check(registry.totalRegisteredStates() == 3 && registry.unfinishedTasks() == 2,
                "Unexpected pending task count");

        for (int worker = 0; worker < 2; worker++) {
            ConcurrentStateRegistry.WorkItem task = registry.take(worker);
            check(!task.isStop(), "Frontier task was lost");
            registry.start(task.key(), worker);
            registry.complete(task.key(), worker);
        }
        check(registry.take(0).isStop() && registry.take(1).isStop(),
                "Workers must stop after the final expansion");
        check(registry.completedGraph(root).edges().equals(prefix.edges()),
                "Prefix edges were not committed");

        // If cut depth is deeper than the whole reachable graph, the global
        // graph is already complete during bootstrap, without worker tasks.
        ReplicatedPrefix exhausted = new ReplicatedPrefix(root,
                Map.of(root, 0, left, 1, right, 1), prefix.edges());
        ConcurrentStateRegistry exhaustedRegistry = new ConcurrentStateRegistry(2);
        exhausted.commitInto(exhaustedRegistry, 3, 2);
        check(exhaustedRegistry.unfinishedTasks() == 0,
                "Exhausted prefix must not schedule work");
        check(exhaustedRegistry.completedGraph(root).states().size() == 3,
                "Exhausted prefix missing states");
        check(exhaustedRegistry.take(0).isStop() && exhaustedRegistry.take(1).isStop(),
                "Exhausted prefix must stop all workers");

        System.out.println("PASS: PREFIX bootstrap committed once, frontier queued, exhausted prefix terminates");
    }

    private static StateKey key(int id) {
        return new StateKey(Map.of("x", Integer.toString(id)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
