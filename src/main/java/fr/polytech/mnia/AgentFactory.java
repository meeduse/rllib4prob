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
    }

    public static Agent create(AlgorithmId id, Environment env, String envName) {
        AgentParameterCatalog.AgentSettings settings = AgentParameterCatalog.settings(envName, id);
        AgentParameterCatalog.OfflineParams offline = settings.offline();
        AgentParameterCatalog.OnlineParams online = settings.online();

        return switch (id) {
            case VALUE_ITERATION -> new ValueIteration(env, offline.gamma(), offline.teta(), offline.maxIterations());
            case POLICY_ITERATION -> new PolicyIteration(env, offline.gamma(), offline.teta(), offline.maxIterations());
            case MODIFIED_POLICY_ITERATION -> new ModifiedPolicyIteration(env, offline.gamma(), offline.teta(), offline.maxIterations(), offline.evalIterations());
            case INCREMENTAL_VALUE_ITERATION -> new IncrementalValueIteration(env, offline.gamma(), offline.teta(), offline.maxIterations(), offline.updatesPerIteration());
            case BACKWARD_INDUCTION -> new BackwardInductionV1(env, offline.gamma(), offline.horizon());
            case PRIORITIZED_VALUE_ITERATION -> new PrioritizedValueIterationV1(env, offline.gamma(), offline.teta(), offline.maxUpdates());
            case DYNA_Q -> new DynaQ(
                    env,
                    online.gamma(),
                    online.teta(),
                    online.alpha(),
                    online.epsilon(),
                    online.planningSteps(),
                    online.maxEpisodes(),
                    online.maxStepsPerEpisode()
            );
            case DYNA_Q_PLUS -> new DynaQPlus(
                    env,
                    online.gamma(),
                    online.teta(),
                    online.alpha(),
                    online.epsilon(),
                    online.planningSteps(),
                    online.maxEpisodes(),
                    online.maxStepsPerEpisode(),
                    online.kappa(),
                    online.logEveryEpisodes()
            );
        };
    }
}
