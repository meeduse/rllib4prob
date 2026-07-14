package fr.polytech.mnia.Environment;

public class TaxiDriverEnvironment extends ModelBasedEnvironment {

    public TaxiDriverEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy);
    }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            Object crashed = sPrime.eval("crashed");
            Object delivered = sPrime.eval("delivered");
            Object illegal = sPrime.eval("illegal");

            if ("1".equals(crashed.toString())) {
                return -1000.0; // Crash
            } else if ("1".equals(delivered.toString()) && "0".equals(illegal.toString())) {
                return 1000.0; // Successful delivery
            } else if ("1".equals(illegal.toString())) {
                return -10.0; // Illegal action
            } else {
                return -1.0; // Each step costs 1
            }
        } catch (Exception e) {
            return 0.0;
        }
    }
}
