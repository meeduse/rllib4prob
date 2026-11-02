package fr.polytech.mnia.exploration.concurrent;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.exploration.model.StateKey;

/**
 * One-step local ProB state materialization for a pending transfer.
 * Called only by the receiving worker, with its OWN thread-confined ProB state
 * handles. State and Transition objects are never sent to the coordinator.
 */
public final class LocalStateGateway {
    private LocalStateGateway() { }

    public record Acquisition(boolean available, long predecessorExpansions) { }

    public static Acquisition acquire(StealableStateRegistry.Candidate candidate,
                                      Map<StateKey, State> localStates,
                                      Function<State, StateKey> exactKey) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(localStates, "localStates");
        Objects.requireNonNull(exactKey, "exactKey");
        if (localStates.containsKey(candidate.key())) {
            return new Acquisition(true, 0);
        }
        long enumerations = 0;
        for (StateKey sourceKey : candidate.predecessors()) {
            State source = localStates.get(sourceKey);
            if (source == null) {
                continue;
            }
            enumerations++;
            for (Transition transition : source.getOutTransitions()) {
                State destination = transition.getDestination();
                StateKey resultKey = exactKey.apply(destination);
                if (candidate.key().equals(resultKey)) {
                    localStates.putIfAbsent(resultKey, destination);
                    return new Acquisition(true, enumerations);
                }
            }
        }
        return new Acquisition(false, enumerations);
    }
}
