package fr.polytech.mnia.Environment;

public class AutoV2Environment extends ModelBasedEnvironment {

    public AutoV2Environment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy);
    }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            Object collision = sPrime.eval("collision");
            
            if ("TRUE".equals(collision.toString())) {
                return -10000.0; // Collision
            }
            
            // Composite reward based on distance and speed errors
            Object distanceError = sPrime.eval("distance_error");
            Object speedError = sPrime.eval("speed_error");
            Object safe = sPrime.eval("safe");
            
            double dist = Double.parseDouble(distanceError.toString());
            double speed = Double.parseDouble(speedError.toString());
            double safetyPenalty = "FALSE".equals(safe.toString()) ? 2000.0 : 0.0;
            
            return 1000.0 - (30.0 * dist) - (20.0 * speed) - safetyPenalty;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
