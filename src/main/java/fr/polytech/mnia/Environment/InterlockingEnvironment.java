package fr.polytech.mnia.Environment;

public class InterlockingEnvironment extends ModelBasedEnvironment {

    public InterlockingEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy);
    }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            // Check if goal is reached: pending = {} & acttrn = {}
            Object pending = sPrime.eval("pending");
            Object acttrn = sPrime.eval("acttrn");
            
            boolean goal = "{}".equals(pending.toString()) && "{}".equals(acttrn.toString());
            
            if (goal) {
                return 5000.0; // Goal reached
            } else {
                return -1.0; // Step cost
            }
        } catch (Exception e) {
            return 0.0;
        }
    }
}
