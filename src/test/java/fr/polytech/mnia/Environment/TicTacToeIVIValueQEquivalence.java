package fr.polytech.mnia.Environment;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.mbrl.offline.IncrementalValueIteration;
import fr.polytech.mnia.mbrl.offline.IncrementalValueIteration.TerminationStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Integration test against real ProB 4.16.1, no production file changes. */
public final class TicTacToeIVIValueQEquivalence {
    private static final double GAMMA = 0.9;
    private static final double THETA = 0.01;
    private static final int MAX_ITERATIONS = 100;
    private static final int UPDATES_PER_ITERATION = 500;
    private static final long SEED = 0L;
    private static final double EPS = 1e-9;
    private static final int EXPECTED_STATES = 5478;
    private static final int EXPECTED_TRANSITIONS = 16167;
    private static final Pattern CELL = Pattern.compile("([1-3])↦([1-3])↦([01])");

    private record Snapshot(Map<String, Double> values,
                            Map<String, Map<String, Double>> q,
                            long transitions, int iterations, double residual) { }

    private TicTacToeIVIValueQEquivalence() { }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("This test takes no arguments");
        System.out.printf(Locale.ROOT,
            "IVI_VQ_START kernel=4.16.1 seed=%d gamma=%.6f theta=%.6f maxIterations=%d updatesPerIteration=%d%n",
            SEED, GAMMA, THETA, MAX_ITERATIONS, UPDATES_PER_ITERATION);
        Snapshot once = run("ONCEANDFORALL", "/TicTacToe/tictac.mch", RewardStrategy.ONCEANDFORALL);
        Snapshot fly = run("ONTHEFLY", "/TicTacToe/tictac.mch", RewardStrategy.ONTHEFLY);
        Snapshot embedded = run("EMBEDDED", "/TicTacToe/tictacEmbedded.mch", RewardStrategy.EMBEDDED);
        compare("ONCEANDFORALL", once, "ONTHEFLY", fly);
        compare("ONCEANDFORALL", once, "EMBEDDED", embedded);
        System.out.printf(Locale.ROOT,
            "IVI_VQ_EQUIVALENCE PASS states=%d transitions=%d seed=%d gamma=%.6f theta=%.6f%n",
            once.values.size(), once.transitions, SEED, GAMMA, THETA);
    }

    private static Snapshot run(String name, String machine, RewardStrategy strategy) {
        System.out.println("IVI_VQ_PHASE start=" + name + " machine=" + machine);
        TicTacToeEnvironment env = new TicTacToeEnvironment(machine, strategy);
        IncrementalValueIteration agent = new IncrementalValueIteration(
            env, GAMMA, THETA, MAX_ITERATIONS, UPDATES_PER_ITERATION, SEED);
        agent.learn(ExplorationStrategy.PREPROCESS);
        if (agent.getStatus() != TerminationStatus.CONVERGED ||
            !Double.isFinite(agent.getLastBellmanResidual()) ||
            agent.getLastBellmanResidual() > THETA) {
            fail(name + " did not converge: status=" + agent.getStatus()
                 + " residual=" + agent.getLastBellmanResidual());
        }
        Map<String, Double> values = new HashMap<>();
        Map<String, Map<String, Double>> q = new HashMap<>();
        long transitions = 0;
        List<Integer> ids = new ArrayList<>(env.getStateIds());
        Collections.sort(ids);
        for (int id : ids) {
            State state = env.gState(id);
            String stateKey = stateKey(state);
            double value = agent.getValue(state);
            if (!Double.isFinite(value)) fail("Non-finite V in " + name + " at " + stateKey);
            if (values.putIfAbsent(stateKey, value) != null) {
                fail("Duplicate B state valuation in " + name + ": " + stateKey);
            }
            Map<Transition, Double> computedQ = agent.getQValues(state);
            Map<String, Double> mappedQ = new HashMap<>();
            for (Transition action : state.getOutTransitions()) {
                String actionKey = actionKey(action);
                Double actionValue = computedQ.get(action);
                if (actionValue == null || !Double.isFinite(actionValue)) {
                    fail("Missing/non-finite Q in " + name + " at " + stateKey + " / " + actionKey);
                }
                if (mappedQ.putIfAbsent(actionKey, actionValue) != null) {
                    fail("Duplicate action in " + name + " at " + stateKey + " / " + actionKey);
                }
                transitions++;
            }
            if (mappedQ.size() != computedQ.size()) {
                fail("Unexpected extra Q entries in " + name + " at " + stateKey);
            }
            q.put(stateKey, mappedQ);
            if (values.size() % 1000 == 0) {
                System.out.printf("IVI_VQ_PROGRESS strategy=%s checked=%d qEdges=%d%n",
                    name, values.size(), transitions);
            }
        }
        if (values.size() != EXPECTED_STATES || transitions != EXPECTED_TRANSITIONS) {
            fail(name + ": wrong graph size: states=" + values.size() + " transitions=" + transitions);
        }
        System.out.printf(Locale.ROOT,
            "IVI_VQ_SNAPSHOT strategy=%s states=%d transitions=%d iterations=%d residual=%.9g%n",
            name, values.size(), transitions, agent.getCompletedIterations(),
            agent.getLastBellmanResidual());
        return new Snapshot(values, q, transitions,
                            agent.getCompletedIterations(), agent.getLastBellmanResidual());
    }

    private static void compare(String refName, Snapshot ref, String otherName, Snapshot other) {
        if (!ref.values.keySet().equals(other.values.keySet())) {
            fail("Different B states between " + refName + " and " + otherName);
        }
        if (ref.transitions != other.transitions) {
            fail("Different transition counts: " + refName + " vs " + otherName);
        }
        double maxVDiff = 0.0, maxQDiff = 0.0;
        long comparedQ = 0;
        for (String stateKey : ref.values.keySet()) {
            double vd = Math.abs(ref.values.get(stateKey) - other.values.get(stateKey));
            maxVDiff = Math.max(maxVDiff, vd);
            if (vd > EPS) fail("V mismatch at " + stateKey + " " + refName + "="
                + ref.values.get(stateKey) + " " + otherName + "=" + other.values.get(stateKey));
            Map<String, Double> ra = ref.q.get(stateKey), rb = other.q.get(stateKey);
            if (rb == null || !ra.keySet().equals(rb.keySet())) {
                fail("Different actions at " + stateKey + " between " + refName + " and " + otherName);
            }
            for (String action : ra.keySet()) {
                double qd = Math.abs(ra.get(action) - rb.get(action));
                maxQDiff = Math.max(maxQDiff, qd);
                if (qd > EPS) fail("Q mismatch at " + stateKey + " action=" + action
                    + " " + refName + "=" + ra.get(action) + " " + otherName + "=" + rb.get(action));
                comparedQ++;
            }
        }
        System.out.printf(Locale.ROOT,
            "IVI_VQ_COMPARE PASS first=%s second=%s states=%d qValues=%d maxVDiff=%.12g maxQDiff=%.12g%n",
            refName, otherName, ref.values.size(), comparedQ, maxVDiff, maxQDiff);
    }

    private static String actionKey(Transition action) {
        return action.getName() + "(" + String.valueOf(action.getParameterValues()) + ")";
    }

    private static String stateKey(State state) {
        String turn = state.eval("turn").toString().trim();
        if (!turn.equals("0") && !turn.equals("1")) fail("Invalid turn=" + turn);
        String square = state.eval("square").toString().trim();
        if (square.equals("∅") || square.equals("{}")) return "turn=" + turn + ";square={}";
        String cleaned = square.replace("|->", "↦").replaceAll("[\\s(){}]", "");
        if (cleaned.isEmpty()) return "turn=" + turn + ";square={}";
        List<String> cells = new ArrayList<>();
        Set<String> positions = new HashSet<>();
        for (String entry : cleaned.split(",", -1)) {
            Matcher match = CELL.matcher(entry);
            if (!match.matches()) fail("Unexpected square='" + square + "' entry='" + entry + "'");
            String pos = match.group(1) + "↦" + match.group(2);
            if (!positions.add(pos)) fail("Duplicate cell " + pos + " in " + square);
            cells.add(pos + "↦" + match.group(3));
        }
        if (cells.size() > 9) fail("Too many cells: " + square);
        Collections.sort(cells);
        return "turn=" + turn + ";square={" + String.join(",", cells) + "}";
    }

    private static void fail(String description) {
        throw new AssertionError("IVI_VQ_EQUIVALENCE FAIL " + description);
    }
}
