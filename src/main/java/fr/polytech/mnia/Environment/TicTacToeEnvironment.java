package fr.polytech.mnia.Environment;

public class TicTacToeEnvironment extends ModelBasedEnvironment {

    public TicTacToeEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy);
    }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            if ("TRUE".equals(sPrime.eval("win(0)").toString())) {
                return 1.0; // Player 0 wins
            } else if ("TRUE".equals(sPrime.eval("win(1)").toString())) {
                return -1.0; // Player 1 wins
            } else if (sPrime.getOutTransitions().isEmpty()) {
                return 0.0; // Draw
            }
            return -0.25; // Small cost per step
        } catch (Exception e) {
            return 0.0;
        }
    }
}
