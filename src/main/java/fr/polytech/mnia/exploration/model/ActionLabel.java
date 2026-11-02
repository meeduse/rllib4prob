package fr.polytech.mnia.exploration.model;

import java.util.List;

/** Name, evaluated parameters, and return values of one B operation execution. */
public record ActionLabel(String operation, List<String> parameters, List<String> returns) {
    public ActionLabel {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Missing B operation name");
        }
        parameters = List.copyOf(parameters);
        returns = List.copyOf(returns);
    }
}
