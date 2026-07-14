package fr.polytech.mnia.Environment;

public final class EnvironmentFactory {

    private EnvironmentFactory() {
    }

    public static Environment create(String envName, RewardStrategy rewardStrategy) {
        return switch (envName.toLowerCase()) {
            case "autov2" -> new AutoV2Environment("/autoV2/HighwayPerception_Q.mch", rewardStrategy);
            case "frozen_lake", "frozenlake" -> new FrozenLakeEnvironment("/frozen_lake/FrozenLakeGrid_4_4.mch", rewardStrategy);
            case "interlocking", "interloking" -> new InterlockingEnvironment("/interlocking/interlocking.mch", rewardStrategy);
            case "puzzle" -> new PuzzleEnvironment("/puzzle/Puzzle8.mch", rewardStrategy);
            case "taxi-driver", "taxi_driver", "taxi" -> new TaxiDriverEnvironment("/taxi_driver/SafeTaxiDriver_5_5.mch", rewardStrategy);
            case "mountain_car", "mountaincar" -> new MountainCarEnvironment("/Mountain_Car/MountainCarSmall.mch", rewardStrategy);
            case "tictactoe", "tic tac toe", "tic_tac_toe" -> new TicTacToeEnvironment("/TicTacToe/tictac.mch", rewardStrategy);
            default -> new TicTacToeEnvironment("/TicTacToe/tictac.mch", rewardStrategy);
        };
    }
}
