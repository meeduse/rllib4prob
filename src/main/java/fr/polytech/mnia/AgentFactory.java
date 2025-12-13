package fr.polytech.mnia;

import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.mbrl.offline.BackwardInductionV1;
import fr.polytech.mnia.mbrl.offline.IncrementalValueIteration;
import fr.polytech.mnia.mbrl.offline.ModifiedPolicyIteration;
import fr.polytech.mnia.mbrl.offline.PolicyIteration;
import fr.polytech.mnia.mbrl.offline.PrioritizedValueIterationV1;
import fr.polytech.mnia.mbrl.offline.ValueIteration;
import fr.polytech.mnia.mbrl.online.DynaQ;
import fr.polytech.mnia.mbrl.online.DynaQPlus;

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
            case DYNA_Q -> new DynaQ(
                env,
                0.9,    // gamma
                0.0,   // teta (pas utilisé par DynaQ)
                0.1,    // alpha
                0.1,    // epsilon
                20,     // planningSteps
                10000,   // maxEpisodes
                50      // maxStepsPerEpisode
            );
            case DYNA_Q_PLUS -> new DynaQPlus(
                env,
                0.9,        // gamma
                0.0,        // teta (non utilisé)
                0.1,        // alpha
                0.2,        // epsilon
                20,         // planningSteps
                20_000,     // maxEpisodes
                30,  // maxStepsPerEpisode (>= 9 pour Tic-Tac-Toe)
                5e-4,       // kappa (bonus Dyna-Q+)
                1_000       // logEveryEpisodes
            );
        };
    }
}