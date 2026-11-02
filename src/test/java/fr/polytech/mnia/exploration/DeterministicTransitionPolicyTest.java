package fr.polytech.mnia.exploration;

import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.polytech.mnia.exploration.concurrent.DeterministicTransitionPolicy;
import fr.polytech.mnia.exploration.model.ActionLabel;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.StateKey;

/** Pure Java checks: pi_B acts on distinct labeled edges, not successor states. */
public final class DeterministicTransitionPolicyTest {
    private DeterministicTransitionPolicyTest() { }

    public static void main(String[] args) {
        StateKey root = key(0), a = key(1), b = key(2);
        LabeledTransition flip = edge(root, "flip", b);
        LabeledTransition inc = edge(root, "inc", a);
        LabeledTransition again = edge(root, "again", a);
        Set<LabeledTransition> unordered = Set.of(inc, flip, again);
        List<LabeledTransition> chosen = DeterministicTransitionPolicy.select(unordered, 2);
        check(chosen.equals(List.of(again, flip)), "Selection depends on enumeration order");
        check(DeterministicTransitionPolicy.select(List.of(inc, inc, flip), 3).size() == 2,
                "Identical labeled triplets must be deduplicated");
        check(DeterministicTransitionPolicy.select(unordered, 0).isEmpty(), "B=0 should select no edges");
        check(DeterministicTransitionPolicy.select(unordered, 10).size() == 3, "B >= outdegree");
        try {
            DeterministicTransitionPolicy.select(unordered, -1);
            throw new AssertionError("Negative B should be rejected");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: BOUNDED deterministic pi_B, duplicate edges, B=0");
    }

    private static StateKey key(int id) { return new StateKey(Map.of("x", Integer.toString(id))); }
    private static LabeledTransition edge(StateKey from, String op, StateKey to) {
        return new LabeledTransition(from, new ActionLabel(op, List.of(), List.of()), to);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
