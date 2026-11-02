package fr.polytech.mnia.exploration.concurrent;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import fr.polytech.mnia.exploration.model.ActionLabel;
import fr.polytech.mnia.exploration.model.LabeledTransition;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * Fixed width policy pi_B. Compare canonical action components, then the
 * canonical destination valuation. Neither ProB transition enumeration order
 * nor local ProB identifiers influence the selection.
 *
 * This orders *distinct labeled edges* (not successor states). Outgoing edges
 * with different labels to the same state count separately toward width B.
 */
public final class DeterministicTransitionPolicy {
    private DeterministicTransitionPolicy() { }

    public static final Comparator<StateKey> STATE_ORDER = (left, right) -> {
        var a = left.variables().entrySet().iterator();
        var b = right.variables().entrySet().iterator();
        while (a.hasNext() && b.hasNext()) {
            Map.Entry<String, String> x = a.next();
            Map.Entry<String, String> y = b.next();
            int c = x.getKey().compareTo(y.getKey());
            if (c != 0) return c;
            c = x.getValue().compareTo(y.getValue());
            if (c != 0) return c;
        }
        return Boolean.compare(a.hasNext(), b.hasNext());
    };

    private static int compareLists(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    public static final Comparator<ActionLabel> ACTION_ORDER = (a, b) -> {
        int c = a.operation().compareTo(b.operation());
        if (c != 0) return c;
        c = compareLists(a.parameters(), b.parameters());
        if (c != 0) return c;
        return compareLists(a.returns(), b.returns());
    };

    public static final Comparator<LabeledTransition> EDGE_ORDER =
            Comparator.comparing(LabeledTransition::source, STATE_ORDER)
                    .thenComparing(LabeledTransition::action, ACTION_ORDER)
                    .thenComparing(LabeledTransition::destination, STATE_ORDER);

    public static List<LabeledTransition> select(Collection<LabeledTransition> outgoing, int width) {
        Objects.requireNonNull(outgoing, "outgoing");
        if (width < 0) throw new IllegalArgumentException("Width B must be >= 0");
        return outgoing.stream().distinct().sorted(EDGE_ORDER).limit(width).toList();
    }
}
