package fr.polytech.mnia.exploration.encoding;

import java.util.ArrayList;
import java.util.List;

import de.prob.statespace.Transition;
import fr.polytech.mnia.exploration.model.ActionLabel;

/** An action label is independent of the worker's ProB Transition object. */
public final class ActionLabelEncoder {
    public ActionLabel label(Transition transition) {
        return new ActionLabel(
                transition.getName(),
                encodeAll(transition.getParameterValues()),
                encodeAll(transition.getReturnValues()));
    }

    private static List<String> encodeAll(List<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw new IllegalStateException("Unexpected ProB parameter/return value type: "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            result.add(CanonicalBValue.encode(text));
        }
        return List.copyOf(result);
    }
}
