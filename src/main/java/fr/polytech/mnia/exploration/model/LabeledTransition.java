package fr.polytech.mnia.exploration.model;

import java.util.Objects;

/** A complete labeled edge, including edges toward previously visited states. */
public record LabeledTransition(StateKey source, ActionLabel action, StateKey destination) {
    public LabeledTransition {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(destination, "destination");
    }
}
