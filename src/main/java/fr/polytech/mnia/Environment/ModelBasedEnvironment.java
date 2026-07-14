package fr.polytech.mnia.Environment;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

public class ModelBasedEnvironment extends Environment {

    protected final RewardStrategy rewardStrategy;

    public ModelBasedEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath);
        this.rewardStrategy = rewardStrategy;
        this.initialise();
    }

    @Override
    protected void prettyPrint() {
        // No model-specific visualization for generic environments.
    }

    @Override
    public double reward(State s, Transition a, State sPrime) {
        return switch (rewardStrategy) {
            case ONCEANDFORALL -> getRewardFromState(sPrime);
            case EMBEDDED -> getRewardFromTransition(a);
            case ONTHEFLY -> calculateRewardOnTheFly(s, a, sPrime);
            default -> 0.0;
        };
    }

    /**
     * Calculate reward on-the-fly based on state variables.
     * Override this method in subclasses to implement domain-specific reward logic.
     */
    protected double calculateRewardOnTheFly(State s, Transition a, State sPrime) {
        // Default: no on-the-fly reward available
        return 0.0;
    }

    protected double getRewardFromState(State sPrime) {
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

        return 0.0;
    }

    protected double getRewardFromTransition(Transition a) {
        try {
            var values = a.getReturnValues();
            if (values != null && !values.isEmpty()) {
                return Double.parseDouble(values.get(0));
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }
}
