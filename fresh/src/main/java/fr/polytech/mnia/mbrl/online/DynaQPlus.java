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
 * Dyna-Q+ implementation (model-based online RL).
 *
 * Combines:
 *  - Online Q-learning from real interactions
 *  - Planning updates using a learned model (s,a -> s',r)
 *  - Dyna-Q+ exploration bonus for (s,a) pairs not tried recently
 *
 * No exhaustive exploration is required: states are discovered on the fly.
 */
public class DynaQPlus extends Agent {

    /* =========================
     * Hyperparameters
     * ========================= */
    private final double alpha;       // learning rate
    private final double epsilon;     // exploration rate
    private final int planningSteps;  // number of simulated updates per real step
    private final int maxEpisodes;
    private final int maxStepsPerEpisode;

    // Dyna-Q+ exploration bonus coefficient
    private final double kappa;

    // Trace frequency
    private final int logEveryEpisodes;

    /* =========================
     * Q-function and model
     * ========================= */
    private final Map<SAKey, Double> Q = new HashMap<>();
    private final Map<SAKey, ModelEntry> model = new HashMap<>();

    // last time (global step counter) when (s,a) was executed in real experience
    private final Map<SAKey, Integer> lastVisit = new HashMap<>();

    // global time counter (counts REAL interaction steps)
    private int time = 0;

    public DynaQPlus(Environment env,
                     double gamma,
                     double teta,
                     double alpha,
                     double epsilon,
                     int planningSteps,
                     int maxEpisodes,
                     int maxStepsPerEpisode,
                     double kappa,
                     int logEveryEpisodes) {
        super(env, gamma, teta);
        this.alpha = alpha;
        this.epsilon = epsilon;
        this.planningSteps = planningSteps;
        this.maxEpisodes = maxEpisodes;
        this.maxStepsPerEpisode = maxStepsPerEpisode;
        this.kappa = kappa;
        this.logEveryEpisodes = Math.max(1, logEveryEpisodes);
    }

    @Override
    public void learn(ExplorationStrategy strategy) {

        // Dyna-Q+ does NOT require exhaustive exploration;
        // but we still want to initialise ProB and start from the initial state.
        System.out.println("[DynaQ+] Initialiez B Machine");
        env.initialise();

        long t0 = System.nanoTime();

        System.out.println("[DynaQ+] Start learning");
        System.out.println("[DynaQ+] maxEpisodes=" + maxEpisodes
                + ", maxStepsPerEpisode=" + maxStepsPerEpisode
                + ", planningSteps=" + planningSteps
                + ", alpha=" + alpha
                + ", epsilon=" + epsilon
                + ", gamma=" + gamma
                + ", kappa=" + kappa
                + ", logEveryEpisodes=" + logEveryEpisodes);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // (Optional) keep a minimal footprint of discovered initial state
        State init = env.gState();
        if (init != null) {
            tryAddState(init);
        }

        for (int episode = 1; episode <= maxEpisodes; episode++) {

            State s = env.gState(); // always restart from initial
            int steps = 0;

            while (steps < maxStepsPerEpisode) {

                List<Transition> outs = s.getOutTransitions();
                if (outs == null || outs.isEmpty()) break;

                // ε-greedy action selection
                Transition a = epsilonGreedy(s, outs, rnd);

                // IMPORTANT: ensure successor is explored and materialized
                State sPrime = a.getDestination().exploreIfNeeded();

                // IMPORTANT: record discovered state ids (fixes your "0 states discovered" issue)
                tryAddState(s);
                tryAddState(sPrime);

                double r = env.reward(s, a, sPrime);

                SAKey key = SAKey.of(s, a);

                // -------- Q-learning update (real experience)
                double oldQ = Q.getOrDefault(key, 0.0);
                double target = r + gamma * maxQ(sPrime);
                double newQ = oldQ + alpha * (target - oldQ);
                Q.put(key, newQ);

                // -------- Model learning
                model.put(key, new ModelEntry(sPrime, r));

                // -------- Update last visit time for Dyna-Q+
                lastVisit.put(key, time);

                // -------- Planning updates (with Dyna-Q+ bonus)
                for (int i = 0; i < planningSteps; i++) {
                    planningUpdateWithBonus(rnd);
                }

                // advance
                s = sPrime;
                steps++;
                time++;
            }

            // periodic logs
            if (episode % logEveryEpisodes == 0) {
                System.out.println("[DynaQ+] episode=" + episode
                        + " | steps(lastEp)=" + steps
                        + " | states=" + env.getStateIds().size()
                        + " | model=" + model.size()
                        + " | Q=" + Q.size()
                        + " | time=" + time);
            }
        }

        double totalTime = (System.nanoTime() - t0) / 1_000_000_000.0;
        System.out.println("[DynaQ+] Finished.");
        System.out.println("[DynaQ+] Total time: " + String.format("%.3f", totalTime) + " s");
        System.out.println("[DynaQ+] Nb states discovered (env): " + env.getStateIds().size());
        System.out.println("[DynaQ+] Nb (s,a) in model: " + model.size());
        System.out.println("[DynaQ+] Nb Q-values stored: " + Q.size());
    }

    /* =========================================================
     * Helper: add stateId in env.getStateIds()
     * ========================================================= */
    private void tryAddState(State s) {
        if (s == null) return;
        try {
            int id = Integer.parseInt(s.getId());
            env.getStateIds().add(id);
        } catch (Exception ignored) {
            // keep silent; do not break learning
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
     * Planning step with Dyna-Q+ bonus
     * ========================================================= */
    private void planningUpdateWithBonus(ThreadLocalRandom rnd) {

        if (model.isEmpty()) return;

        Object[] keys = model.keySet().toArray();
        SAKey key = (SAKey) keys[rnd.nextInt(keys.length)];
        ModelEntry entry = model.get(key);

        // Dyna-Q+ bonus: kappa * sqrt(time - lastVisit(key))
        int last = lastVisit.getOrDefault(key, 0);
        int dt = Math.max(0, time - last);
        double bonus = kappa * Math.sqrt(dt);

        // occasional trace when bonus is meaningful (avoid spam)
        if (bonus > 0.0 && time % 10_000 == 0) {
            System.out.println("[DynaQ+] planning bonus=" + String.format("%.6f", bonus)
                    + " | dt=" + dt
                    + " | time=" + time);
        }

        double oldQ = Q.getOrDefault(key, 0.0);
        double target = (entry.reward + bonus) + gamma * maxQ(entry.nextState);
        double newQ = oldQ + alpha * (target - oldQ);

        Q.put(key, newQ);
    }

    /* =========================================================
     * Max Q(s)
     * ========================================================= */
    private double maxQ(State s) {
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
     * Access Q-values for a given state
     * ========================================================= */
    @Override
    public Map<Transition, Double> getQValues(State s) {
        if (s == null) return Collections.emptyMap();

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

    /**
     * Key (s,a) for Q and model.
     * Uses state id + transition id (stable, avoids name collisions).
     */
    private static final class SAKey {
        final int stateId;
        final String actionId; // Transition.getId() is a String in ProB

        private SAKey(int stateId, String actionId) {
            this.stateId = stateId;
            this.actionId = actionId;
        }

        static SAKey of(State s, Transition a) {
            return new SAKey(Integer.parseInt(s.getId()), a.getId());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SAKey)) return false;
            SAKey other = (SAKey) o;
            return stateId == other.stateId && Objects.equals(actionId, other.actionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stateId, actionId);
        }
    }
}