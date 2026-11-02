package fr.polytech.mnia.exploration.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Semantic B variable valuations; never contains ProB-local IDs or objects. */
public record StateKey(Map<String, String> variables) {
    public StateKey {
        if (variables == null || variables.isEmpty()) {
            throw new IllegalArgumentException("State valuation must be nonempty");
        }
        variables = Collections.unmodifiableMap(new TreeMap<>(variables));
    }
}
