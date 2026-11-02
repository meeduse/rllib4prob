package fr.polytech.mnia.exploration.encoding;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.prob.statespace.State;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * Encodes the full caller-declared variable set for one B machine.
 * The caller must supply every state variable, not constants or derived values.
 * A future model metadata adapter may provide this list automatically.
 */
public final class StateKeyEncoder {
    private final List<String> variableNames;

    public StateKeyEncoder(List<String> names) {
        if (names == null || names.isEmpty() || names.stream().anyMatch(n -> n == null || n.isBlank())) {
            throw new IllegalArgumentException("Provide the complete list of B state variables");
        }
        List<String> sorted = names.stream().sorted().distinct().toList();
        if (sorted.size() != names.size()) {
            throw new IllegalArgumentException("Duplicate B variable name");
        }
        this.variableNames = sorted;
    }

    public StateKey key(State state) {
        Map<String, String> valuations = new TreeMap<>();
        for (String name : variableNames) {
            String bValue = state.eval(name).toString();
            try {
                valuations.put(name, CanonicalBValue.encode(bValue));
            } catch (RuntimeException error) {
                throw new IllegalStateException("Cannot canonically encode variable '" + name
                        + "' with value '" + bValue + "'", error);
            }
        }
        return new StateKey(valuations);
    }
}
