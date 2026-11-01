package fr.polytech.mnia.Environment;

public class FrozenLakeEnvironment extends ModelBasedEnvironment {

    public FrozenLakeEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy);
    }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            Object fellInHole = sPrime.eval("fellInHole");
            Object reachedGoal = sPrime.eval("reachedGoal");

            if ("1".equals(fellInHole.toString())) {
                return -1000.0; // Fell in a hole
            } else if ("1".equals(reachedGoal.toString())) {
                return 1000.0; // Reached the goal
            } else {
                return -1.0; // Each step costs 1
            }
        } catch (Exception e) {
            return 0.0;
        }
    }
}
