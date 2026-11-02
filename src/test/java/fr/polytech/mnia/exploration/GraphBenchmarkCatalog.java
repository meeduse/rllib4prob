package fr.polytech.mnia.exploration;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Explicit experimental fixtures. Variable lists are the full dynamic state
 * variables, including those declared by an included B machine. Reference
 * counts are taken from the previous audited benchmark campaign and TicTacToe.
 * They are checked, not silently assumed, by GraphConstructionCampaign.
 */
public final class GraphBenchmarkCatalog {
    public record Spec(String id, Path machine, List<String> variables,
                       int expectedStates, int expectedEdges) {
        public Spec {
            variables = List.copyOf(variables);
            if (id.isBlank() || variables.isEmpty() || expectedStates < 1 || expectedEdges < 0) {
                throw new IllegalArgumentException("Invalid benchmark specification");
            }
        }
    }

    private static final List<Spec> MODELS = List.of(
        new Spec("tictactoe", Path.of("TicTacToe/tictac.mch"),
            List.of("square", "turn"), 5478, 16167),
        new Spec("frozenlake4", Path.of("frozen_lake/FrozenLakeGrid_4_4.mch"),
            List.of("posX", "posY", "reachedGoal", "fellInHole", "lastAction"), 43, 167),
        new Spec("frozenlake10", Path.of("frozen_lake/FrozenLakeGrid_10_10.mch"),
            List.of("posX", "posY", "reachedGoal", "fellInHole", "lastAction"), 304, 1316),
        new Spec("taxi5", Path.of("taxi_driver/SafeTaxiDriver_5_5.mch"),
            List.of("taxiX", "taxiY", "passengerLoc", "destination", "passengerInTaxi", "delivered"), 43, 110),
        new Spec("taxi10", Path.of("taxi_driver/SafeTaxiDriver_10_10.mch"),
            List.of("taxiX", "taxiY", "passengerLoc", "destination", "passengerInTaxi", "delivered"), 165, 490),
        new Spec("taxi30", Path.of("taxi_driver/SafeTaxiDriver_30_30.mch"),
            List.of("taxiX", "taxiY", "passengerLoc", "destination", "passengerInTaxi", "delivered"), 1477, 5402),
        new Spec("autov2", Path.of("autoV2/HighwayPerception_Q.mch"),
            List.of("distance_front", "ego_speed", "front_speed"), 2961, 10630),
        new Spec("mountaincar", Path.of("Mountain_Car/MountainCarSmall.mch"),
            List.of("position", "velocity", "done", "lastAction"), 12047, 35586),
        new Spec("interlocking", Path.of("interlocking/interlocking.mch"),
            List.of("acttrn", "trainpos", "traindir", "resss", "restail", "pending"), 100226, 301099),
        new Spec("puzzle", Path.of("puzzle/Puzzle8.mch"),
            List.of("board", "zx", "zy"), 181440, 483840)
    );

    private GraphBenchmarkCatalog() { }

    public static List<Spec> all() { return MODELS; }

    public static Spec require(String id) {
        String key = id.toLowerCase(Locale.ROOT).trim();
        return MODELS.stream().filter(spec -> spec.id().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown benchmark: " + id
                    + ". Valid: " + MODELS.stream().map(Spec::id).toList()));
    }
}
