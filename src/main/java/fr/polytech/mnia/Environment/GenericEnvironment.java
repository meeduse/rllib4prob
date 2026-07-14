package fr.polytech.mnia.Environment;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

public class GenericEnvironment extends Environment {

    public GenericEnvironment(String filePath) {
        super(filePath);
        this.initialise();
    }

    @Override
    protected void prettyPrint() {
        // No interactive pretty-print available for generic environments.
    }

    @Override
    public double reward(State s, Transition a, State sPrime) {
        double reward = tryGetRewardFromState(sPrime);
        if (!Double.isNaN(reward)) {
            return reward;
        }
        reward = tryGetRewardFromTransition(a);
        return Double.isNaN(reward) ? 0.0 : reward;
    }

    private double tryGetRewardFromState(State sPrime) {
        try {
            Object value = sPrime.getValues().get("reward");
            if (value != null) {
                return Double.parseDouble(value.toString());
            }
        } catch (Exception ignored) {
        }

        try {
            Object value = sPrime.eval("reward");
            if (value != null) {
                return Double.parseDouble(value.toString());
            }
        } catch (Exception ignored) {
        }

        return Double.NaN;
    }

    private double tryGetRewardFromTransition(Transition a) {
        try {
            var values = a.getReturnValues();
            if (values != null && !values.isEmpty()) {
                return Double.parseDouble(values.get(0));
            }
        } catch (Exception ignored) {
        }
        return Double.NaN;
    }
}
