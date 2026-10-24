package fr.polytech.mnia.Environment;

public class MountainCarEnvironment extends ModelBasedEnvironment {

    public MountainCarEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy);
    }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            Object done = sPrime.eval("done");
            
            if ("1".equals(done.toString())) {
                return 10000.0; // Reached the goal
            } else {
                return -0.1; // Small cost per step to encourage efficiency
            }
        } catch (Exception e) {
            return 0.0;
        }
    }
}
