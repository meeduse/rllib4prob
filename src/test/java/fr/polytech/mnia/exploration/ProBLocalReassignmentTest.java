package fr.polytech.mnia.exploration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.exploration.concurrent.ReplicatedPrefix;
import fr.polytech.mnia.exploration.concurrent.LocalStateGateway;
import fr.polytech.mnia.exploration.concurrent.StealableStateRegistry;
import fr.polytech.mnia.exploration.encoding.ActionLabelEncoder;
import fr.polytech.mnia.exploration.encoding.StateKeyEncoder;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.StateKey;
import fr.polytech.mnia.exploration.prob.ProBWorkerSession;

/**
 * Real ProB integration test: a pending task is transferred to a worker that
 * ALREADY has an independent, usable local handle for the same semantic state.
 */
public final class ProBLocalReassignmentTest {
    private ProBLocalReassignmentTest() { }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Set<LabeledTransition> outgoing(State state, StateKey source,
                                                   StateKeyEncoder keys, ActionLabelEncoder actions) {
        Set<LabeledTransition> result = new HashSet<>();
        for (Transition t : state.getOutTransitions()) {
            result.add(new LabeledTransition(source, actions.label(t), keys.key(t.getDestination())));
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/test/resources");
        Path machine = Path.of("concurrent/IncFlip.mch");
        StateKeyEncoder keys = new StateKeyEncoder(List.of("x", "y"));
        ActionLabelEncoder actions = new ActionLabelEncoder();
        Path leftWorkspace;
        Path rightWorkspace;
        try (ProBWorkerSession left = ProBWorkerSession.open(source, machine, false);
             ProBWorkerSession right = ProBWorkerSession.open(source, machine, false)) {
            leftWorkspace = left.workspaceDirectory();
            rightWorkspace = right.workspaceDirectory();
            check(!Files.isSameFile(left.localMachinePath(), right.localMachinePath()),
                    "Workers must load physically distinct B files");
            State leftRoot = left.initialState();
            State rightRoot = right.initialState();
            StateKey initial = keys.key(leftRoot);
            check(initial.equals(keys.key(rightRoot)), "Initial B valuations differ");
            Map<StateKey, State> leftLocal = new HashMap<>();
            Map<StateKey, State> rightLocal = new HashMap<>();
            Map<StateKey, Integer> levels = new HashMap<>();
            Set<LabeledTransition> edges = new HashSet<>();
            levels.put(initial, 0);
            for (Transition transition : leftRoot.getOutTransitions()) {
                State destination = transition.getDestination();
                StateKey key = keys.key(destination);
                leftLocal.put(key, destination);
                levels.put(key, 1);
                edges.add(new LabeledTransition(initial, actions.label(transition), key));
            }
            for (Transition transition : rightRoot.getOutTransitions()) {
                State destination = transition.getDestination();
                rightLocal.put(keys.key(destination), destination);
            }
            check(levels.size() == 3 && edges.size() == 2, "Unexpected IncFlip first layer");

            StealableStateRegistry registry = new StealableStateRegistry(2);
            registry.commitPrefix(new ReplicatedPrefix(initial, levels, edges), 1);
            var pending = registry.pendingFor(0).stream().filter(c -> c.owner() == 1)
                    .findFirst().orElseThrow();
            State originalOwnerState = rightLocal.get(pending.key());
            check(originalOwnerState != null, "Owner must hold its own ProB state");
            // Force one-step regeneration through the receiving instance's root,
            // rather than reuse its already discovered frontier handle.
            leftLocal.remove(pending.key());
            leftLocal.put(initial, leftRoot);
            LocalStateGateway.Acquisition acquisition =
                    LocalStateGateway.acquire(pending, leftLocal, keys::key);
            State locallyAvailable = leftLocal.get(pending.key());
            check(acquisition.available() && acquisition.predecessorExpansions() == 1
                            && locallyAvailable != null,
                    "Receiver could not regenerate target using its own ProB predecessor");

            // This CAS is valid only because receiver 0 now holds locallyAvailable.
            check(registry.tryReassign(pending, 0), "Pending task could not be reassigned");
            var obsolete = registry.poll(1, 1);
            check(obsolete != null && !registry.start(obsolete, 1),
                    "The old owner unexpectedly started an obsolete assignment");
            var originalTask = registry.poll(0, 1);
            var reassignedTask = registry.poll(0, 1);
            check(originalTask != null && reassignedTask != null &&
                            reassignedTask.key().equals(pending.key()) &&
                            reassignedTask.generation() == 1 && registry.start(reassignedTask, 0),
                    "New owner's work item is invalid");
            Set<LabeledTransition> observed = outgoing(locallyAvailable, pending.key(), keys, actions);
            Set<LabeledTransition> expected = outgoing(originalOwnerState, pending.key(), keys, actions);
            check(observed.equals(expected), "Reassigned worker sees different ProB successors");
            for (LabeledTransition edge : observed) {
                registry.registerPending(edge.destination(), 0);
                registry.recordEdge(edge);
            }
            registry.complete(reassignedTask, 0);
            check(registry.successes() == 1 && registry.attempts() == 1,
                    "Incorrect real ProB reassignment counters");
        }
        check(!Files.exists(leftWorkspace) && !Files.exists(rightWorkspace),
                "ProB workspaces were not cleaned");
        System.out.println("PASS: PROB local gateway regeneration, independent handles, CAS generation, equal successors, cleanup");
    }
}
