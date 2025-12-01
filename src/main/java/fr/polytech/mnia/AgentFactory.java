package fr.polytech.mnia;

public final class AgentFactory {

    private AgentFactory() {
        // util class
    }

    public static Agent create(AlgorithmId id, Environment env) {
        return switch (id) {
            case VALUE_ITERATION -> new ValueIteration(
                    env,
                    0.9,   // gamma
                    0.01,  // teta
                    10     // maxIterations
            );
            case POLICY_ITERATION -> new PolicyIteration(
                    env,
                    0.9,   // gamma
                    0.01,  // teta
                    100    // maxIterations
            );
            case MODIFIED_POLICY_ITERATION -> new ModifiedPolicyIteration(
                    env,
                    0.9,   // gamma
                    0.01,  // teta
                    100,   // maxIterations
                    5      // evalIterations
            );
            case INCREMENTAL_VALUE_ITERATION -> new IncrementalValueIteration(
                    env,
                    0.9,    // gamma
                    0.001,  // teta
                    200,    // maxIterations
                    500     // updatesPerIteration 
            );
            case BACKWARD_INDUCTION -> new BackwardInductionV1(
                    env,
                    0.9,   // gamma
                    9      // horizon (Tic-Tac-Toe)
            );
            case PRIORITIZED_VALUE_ITERATION -> new PrioritizedValueIterationV1(
                env,
                0.9,   // gamma
                0.01,  // teta (erreur de Bellman seuil)
                100_000 // maxUpdates (maxUpdates = k * |S| (k : 10..50)
            );
        };
    }
}