package fr.polytech.mnia.mbrl.online;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Agent;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/**
 * Dyna-Q implementation.
 *
 * Combines:
 *  - Online Q-learning from real interactions
 *  - Planning updates using a learned model (s,a -> s',r)
 *
 * No exhaustive exploration is required.
 */
public class DynaQ extends Agent {

    /* =========================
     * Hyperparameters
     * ========================= */
    private final double alpha;      // learning rate
    private final double epsilon;    // exploration rate
    private final int planningSteps; // number of simulated updates per real step
    private final int maxEpisodes;
    private final int maxStepsPerEpisode;

    /* =========================
     * Q-function and model
     * ========================= */
    private final Map<SAKey, Double> Q = new HashMap<>();
    private final Map<SAKey, ModelEntry> model = new HashMap<>();

    public DynaQ(Environment env,
                 double gamma,
                 double teta,
                 double alpha,
                 double epsilon,
                 int planningSteps,
                 int maxEpisodes,
                 int maxStepsPerEpisode) {
        super(env, gamma, teta);
        this.alpha = alpha;
        this.epsilon = epsilon;
        this.planningSteps = planningSteps;
        this.maxEpisodes = maxEpisodes;
        this.maxStepsPerEpisode = maxStepsPerEpisode;
    }

    @Override
    public void learn(ExplorationStrategy strategy) {

        // Dyna-Q does NOT require exhaustive exploration.
        // We only need an initialized root state.
        env.initialise();

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int episode = 0; episode < maxEpisodes; episode++) {

            State s = env.gState();
            if (s == null) break;
            s = s.exploreIfNeeded(); // important: ensure outgoing transitions are populated

            int step = 0;

            while (step < maxStepsPerEpisode) {

                List<Transition> outs = s.getOutTransitions();
                if (outs == null || outs.isEmpty()) break;

                // ε-greedy action selection
                Transition a = epsilonGreedy(s, outs, rnd);

                // Important: force exploration of destination
                State sPrime = a.getDestination().exploreIfNeeded();

                // track visited states if to maintain counters/stats
                try {
                    env.addStateID(Integer.parseInt(s.getId()));
                    env.addStateID(Integer.parseInt(sPrime.getId()));
                } catch (Exception ignored) {
                    // if addStateID is not available or ids not numeric, ignore
                }

                double r = env.reward(s, a, sPrime);

                SAKey key = SAKey.of(s, a);

                // -------- Q-learning update (real experience)
                double oldQ = Q.getOrDefault(key, 0.0);
                double target = r + gamma * maxQ(sPrime);
                double newQ = oldQ + alpha * (target - oldQ);
                Q.put(key, newQ);

                // -------- Model learning
                model.put(key, new ModelEntry(sPrime, r));

                // -------- Planning updates
                for (int i = 0; i < planningSteps; i++) {
                    planningUpdate(rnd);
                }

                s = sPrime;
                step++;
            }
        }
    }

    /* =========================================================
     * ε-greedy policy
     * ========================================================= */
    private Transition epsilonGreedy(State s, List<Transition> actions, ThreadLocalRandom rnd) {

        // Exploration
        if (rnd.nextDouble() < epsilon) {
            return actions.get(rnd.nextInt(actions.size()));
        }

        // Exploitation
        Transition best = actions.get(0);
        double bestQ = Q.getOrDefault(SAKey.of(s, best), 0.0);

        for (Transition a : actions) {
            double q = Q.getOrDefault(SAKey.of(s, a), 0.0);
            if (q > bestQ) {
                bestQ = q;
                best = a;
            }
        }
        return best;
    }

    /* =========================================================
     * Planning step
     * ========================================================= */
    private void planningUpdate(ThreadLocalRandom rnd) {

        if (model.isEmpty()) return;

        Object[] keys = model.keySet().toArray();
        SAKey key = (SAKey) keys[rnd.nextInt(keys.length)];
        ModelEntry entry = model.get(key);

        // Ensure the stored nextState is explored before querying maxQ
        State sPrime = entry.nextState == null ? null : entry.nextState.exploreIfNeeded();

        double oldQ = Q.getOrDefault(key, 0.0);
        double target = entry.reward + gamma * (sPrime == null ? 0.0 : maxQ(sPrime));
        double newQ = oldQ + alpha * (target - oldQ);

        Q.put(key, newQ);
    }

    /* =========================================================
     * Max Q(s)
     * ========================================================= */
    private double maxQ(State s) {
        if (s == null) return 0.0;

        s = s.exploreIfNeeded();
        List<Transition> outs = s.getOutTransitions();
        if (outs == null || outs.isEmpty()) return 0.0;

        double best = Double.NEGATIVE_INFINITY;
        for (Transition a : outs) {
            double q = Q.getOrDefault(SAKey.of(s, a), 0.0);
            if (q > best) best = q;
        }
        return best == Double.NEGATIVE_INFINITY ? 0.0 : best;
    }

    /* =========================================================
     * Access Q-values
     * ========================================================= */
    @Override
    public Map<Transition, Double> getQValues(State s) {
        if (s == null) return Collections.emptyMap();

        s = s.exploreIfNeeded();
        List<Transition> outs = s.getOutTransitions();
        if (outs == null || outs.isEmpty()) return Collections.emptyMap();

        Map<Transition, Double> res = new LinkedHashMap<>();
        for (Transition a : outs) {
            res.put(a, Q.getOrDefault(SAKey.of(s, a), 0.0));
        }
        return res;
    }

    /* =========================================================
     * Internal helper classes
     * ========================================================= */

    private static final class ModelEntry {
        final State nextState;
        final double reward;

        ModelEntry(State nextState, double reward) {
            this.nextState = nextState;
            this.reward = reward;
        }
    }

    private static final class SAKey {
        final int stateId;
        final String transitionId;

        private SAKey(int stateId, String transitionId) {
            this.stateId = stateId;
            this.transitionId = transitionId;
        }

        static SAKey of(State s, Transition a) {
            return new SAKey(
                Integer.parseInt(s.getId()),
                a.getId()
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SAKey)) return false;
            SAKey other = (SAKey) o;
            return stateId == other.stateId &&
                Objects.equals(transitionId, other.transitionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stateId, transitionId);
        }
    }
}