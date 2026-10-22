package fr.polytech.mnia.Environment;

public class PuzzleEnvironment extends ModelBasedEnvironment {

    public PuzzleEnvironment(String filePath, RewardStrategy rewardStrategy) {
        super(filePath, rewardStrategy);
    }

    @Override
    protected double calculateRewardOnTheFly(de.prob.statespace.State s, de.prob.statespace.Transition a, de.prob.statespace.State sPrime) {
        try {
            // For puzzle, check if GOAL is satisfied
            boolean goalReached = sPrime.getOutTransitions().stream()
                .anyMatch(t -> t.getName().contains("Goal"));
            
            if (goalReached || sPrime.getOutTransitions().isEmpty()) {
                return 1000.0; // Puzzle solved
            } else {
                return -1.0; // Step cost
            }
        } catch (Exception e) {
            return 0.0;
        }
    }
}
