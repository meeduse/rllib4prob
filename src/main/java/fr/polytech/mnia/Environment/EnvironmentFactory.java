package fr.polytech.mnia.Environment;

public final class EnvironmentFactory {

    private EnvironmentFactory() {
    }

    public static Environment create(String envName, RewardStrategy rewardStrategy) {
        return switch (envName.toLowerCase()) {
            case "autov2" -> new AutoV2Environment("/autoV2/HighwayPerception_Q.mch", rewardStrategy);
            case "autov2embedded" -> new AutoV2Environment("/autoV2/HighwayPerception_QBis.mch", rewardStrategy);
            case "frozen_lake", "frozenlake" -> new FrozenLakeEnvironment("/frozen_lake/FrozenLakeGrid_4_4.mch", rewardStrategy);
            case "frozen_lakeembedded", "frozenlakeembedded" -> new FrozenLakeEnvironment("/frozen_lake/FrozenLakeGrid_4_4Bis.mch", rewardStrategy);
            case "interlocking", "interloking" -> new InterlockingEnvironment("/interlocking/interlocking.mch", rewardStrategy);
            case "interlockingembedded", "interlokingembedded" -> new InterlockingEnvironment("/interlocking/interlockingBis.mch", rewardStrategy);
            case "puzzle" -> new PuzzleEnvironment("/puzzle/Puzzle8.mch", rewardStrategy);
            case "puzzleembedded" -> new PuzzleEnvironment("/puzzle/Puzzle8Bis.mch", rewardStrategy);
            case "taxi-driver", "taxi_driver", "taxi" -> new TaxiDriverEnvironment("/taxi_driver/SafeTaxiDriver_5_5.mch", rewardStrategy);
            case "taxi-driverembedded", "taxi_driverembedded", "taxiembedded" -> new TaxiDriverEnvironment("/taxi_driver/SafeTaxiDriver_5_5Bis.mch", rewardStrategy);
            case "mountain_car", "mountaincar" -> new MountainCarEnvironment("/Mountain_Car/MountainCarSmall.mch", rewardStrategy);
            case "mountain_carembedded", "mountaincarembedded" -> new MountainCarEnvironment("/Mountain_Car/MountainCarSmallBis.mch", rewardStrategy);
            //case "tictactoe", "tic tac toe", "tic_tac_toe" -> new TicTacToeEnvironment("/TicTacToe/tictac.mch", rewardStrategy);
            default -> new TicTacToeEnvironment("/TicTacToe/tictac.mch", rewardStrategy);
        };
    }
}
