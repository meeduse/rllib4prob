package fr.polytech.mnia.Environment;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the reachable, labelled transition systems of two B machines.
 * States are matched by their B variable valuations, never their ProB IDs.
 * Only adds a Java test: no modification to agents, B machines or rewards.
 *
 * For this particular benchmark, rewards depend only on the successor state.
 */
public final class TicTacToeBEquivalence {
    private static final String NORMAL = "/TicTacToe/tictac.mch";
    private static final String EMBEDDED = "/TicTacToe/tictacEmbedded.mch";
    private static final int EXPECTED_STATES = 5478;
    private static final int EXPECTED_TRANSITIONS = 16167;
    private static final double EPS = 1e-12;
    private static final Pattern CELL = Pattern.compile("([1-3])↦([1-3])↦([01])");

    private TicTacToeBEquivalence() { }

    private record Edge(String target, double reward) { }
    private record Graph(String initial, Map<String, Map<String, Edge>> edges,
                         int transitions, int subscribedVsOnTheFly) { }

    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("Usage: TicTacToeBEquivalence (no arguments)");
        }
        long start = System.nanoTime();
        System.out.println("B_EQUIVALENCE_START normal=" + NORMAL + " embedded=" + EMBEDDED);
        System.out.println("B_EQUIVALENCE_PHASE load normal with subscribed rewards");
        TicTacToeEnvironment normal = new TicTacToeEnvironment(NORMAL, RewardStrategy.ONCEANDFORALL);
        Graph a = explore(normal, false, "normal", start);

        System.out.println("B_EQUIVALENCE_PHASE load embedded with operation return values");
        TicTacToeEnvironment embedded = new TicTacToeEnvironment(EMBEDDED, RewardStrategy.EMBEDDED);
        Graph b = explore(embedded, true, "embedded", start);

        if (!a.initial.equals(b.initial)) {
            fail("Initial states differ: normal=" + a.initial + " embedded=" + b.initial);
        }
        Set<String> aStates = a.edges.keySet();
        Set<String> bStates = b.edges.keySet();
        if (!aStates.equals(bStates)) {
            Set<String> onlyA = new TreeSet<>(aStates);
            onlyA.removeAll(bStates);
            Set<String> onlyB = new TreeSet<>(bStates);
            onlyB.removeAll(aStates);
            fail("State sets differ. onlyNormal=" + sample(onlyA)
                + " onlyEmbedded=" + sample(onlyB));
        }
        if (a.transitions != b.transitions) {
            fail("Different numbers of transitions: " + a.transitions + " vs " + b.transitions);
        }
        int comparedEdges = 0;
        for (String source : aStates) {
            Map<String, Edge> na = a.edges.get(source);
            Map<String, Edge> nb = b.edges.get(source);
            if (!na.keySet().equals(nb.keySet())) {
                Set<String> missing = new TreeSet<>(na.keySet());
                missing.removeAll(nb.keySet());
                Set<String> extra = new TreeSet<>(nb.keySet());
                extra.removeAll(na.keySet());
                fail("Different enabled actions at state=" + source
                    + " missingEmbedded=" + sample(missing) + " extraEmbedded=" + sample(extra));
            }
            for (Map.Entry<String, Edge> entry : na.entrySet()) {
                Edge left = entry.getValue();
                Edge right = nb.get(entry.getKey());
                if (!left.target.equals(right.target)) {
                    fail("Different successor at state=" + source + " action=" + entry.getKey()
                        + " normal=" + left.target + " embedded=" + right.target);
                }
                if (!equivalent(left.reward, right.reward)) {
                    fail("Different reward at state=" + source + " action=" + entry.getKey()
                        + " normal=" + left.reward + " embedded=" + right.reward);
                }
                comparedEdges++;
            }
        }

        if (aStates.size() != EXPECTED_STATES || bStates.size() != EXPECTED_STATES
                || comparedEdges != EXPECTED_TRANSITIONS) {
            fail("Graph size differs from validated Tic-Tac-Toe reference: "
                 + aStates.size() + " / " + bStates.size() + " states, "
                 + comparedEdges + " edges (expected " + EXPECTED_STATES
                 + " and " + EXPECTED_TRANSITIONS + ")");
        }
        long misses = normal.getRewardCacheMisses();
        long hits = normal.getRewardCacheHits();
        int cacheSize = normal.getCachedRewardStateCount();
        if (misses + hits != a.transitions || cacheSize != misses) {
            fail("Inconsistent normal subscribed cache accounting: hits=" + hits
                + " misses=" + misses + " cacheSize=" + cacheSize
                + " transitions=" + a.transitions);
        }
        System.out.printf(Locale.ROOT,
                "B_EQUIVALENCE PASS normalStates=%d embeddedStates=%d "
                + "normalTransitions=%d embeddedTransitions=%d comparedTransitions=%d "
                + "missingTransitions=0 rewardMismatches=0 onTheFlyMismatches=0 "
                + "subscribedVsOnTheFly=%d cacheHits=%d cacheMisses=%d cachedStates=%d "
                + "elapsedSeconds=%.3f%n",
                aStates.size(), bStates.size(), a.transitions, b.transitions,
                comparedEdges, a.subscribedVsOnTheFly, hits, misses, cacheSize,
                (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static Graph explore(TicTacToeEnvironment env, boolean embedded,
                                  String name, long start) {
        State initial = env.gState();
        String initialKey = stateKey(initial);
        Queue<State> pending = new ArrayDeque<>();
        Set<String> seenProBIds = new HashSet<>();
        Map<String, String> keyById = new HashMap<>();
        Map<String, Map<String, Edge>> edges = new LinkedHashMap<>();
        Set<String> onTheFlyChecked = new HashSet<>();
        pending.add(initial);
        seenProBIds.add(initial.getId());
        keyById.put(initial.getId(), initialKey);
        int transitions = 0;

        while (!pending.isEmpty()) {
            State source = pending.remove();
            String key = keyById.computeIfAbsent(source.getId(), id -> stateKey(source));
            Map<String, Edge> actions = new HashMap<>();
            for (Transition t : source.getOutTransitions()) {
                State destination = t.getDestination();
                String destKey = keyById.computeIfAbsent(destination.getId(), id -> stateKey(destination));
                String action = actionKey(t);
                double reward;
                if (embedded) {
                    List<?> returns = t.getReturnValues();
                    if (returns == null || returns.size() != 1) {
                        fail("Embedded action has no unique return reward at " + key
                             + " action=" + action + " returns=" + returns);
                    }
                    try {
                        reward = Double.parseDouble(returns.get(0).toString());
                    } catch (RuntimeException ex) {
                        throw new IllegalStateException("Invalid embedded reward for " + action, ex);
                    }
                } else {
                    reward = env.reward(source, t, destination); // Subscribed, NOT State.eval("reward")
                    if (onTheFlyChecked.add(destination.getId())) {
                        double onTheFly = env.calculateRewardOnTheFly(source, t, destination);
                        if (!equivalent(reward, onTheFly)) {
                            fail("ONCEANDFORALL / ONTHEFLY mismatch at " + destKey
                                 + ": subscribed=" + reward + " onthefly=" + onTheFly);
                        }
                    }
                }
                if (!Double.isFinite(reward)) {
                    fail("Non-finite reward at " + key + " action=" + action);
                }
                if (actions.putIfAbsent(action, new Edge(destKey, reward)) != null) {
                    fail("Duplicate action label for same state: state=" + key + " action=" + action);
                }
                transitions++;
                if (seenProBIds.add(destination.getId())) {
                    pending.add(destination);
                }
            }
            if (edges.putIfAbsent(key, actions) != null) {
                fail("Two distinct ProB state IDs represent the same B valuation: " + key);
            }
            if (edges.size() % 250 == 0) {
                System.out.printf(Locale.ROOT,
                        "B_EQUIVALENCE_PROGRESS model=%s checked=%d discovered=%d "
                        + "queued=%d transitions=%d elapsedSeconds=%.2f%n",
                        name, edges.size(), seenProBIds.size(), pending.size(), transitions,
                        (System.nanoTime() - start) / 1_000_000_000.0);
            }
        }
        if (seenProBIds.size() != edges.size()) {
            fail("BFS mismatch for " + name + ": discovered=" + seenProBIds.size()
                 + " checked=" + edges.size());
        }
        System.out.printf(Locale.ROOT,
                "B_EQUIVALENCE_GRAPH model=%s states=%d transitions=%d%n",
                name, edges.size(), transitions);
        return new Graph(initialKey, edges, transitions, onTheFlyChecked.size());
    }

    private static boolean equivalent(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= EPS;
    }

    private static String actionKey(Transition t) {
        // The operation name and parameter values identify a player's move.
        // Embedded return values deliberately do NOT enter this key.
        return t.getName() + "(" + String.valueOf(t.getParameterValues()) + ")";
    }

    private static String stateKey(State state) {
        String turn = state.eval("turn").toString().trim();
        if (!turn.equals("0") && !turn.equals("1")) {
            fail("Invalid value for turn=" + turn + " (ProB state ID " + state.getId() + ")");
        }
        String square = state.eval("square").toString().trim();
        if (square.equals("∅") || square.equals("{}")) {
            return "turn=" + turn + ";square={}";
        }
        String cleaned = square.replace("|->", "↦").replaceAll("[\\s(){}]", "");
        if (cleaned.isEmpty()) {
            return "turn=" + turn + ";square={}";
        }
        List<String> cells = new ArrayList<>();
        Set<String> positions = new HashSet<>();
        for (String entry : cleaned.split(",", -1)) {
            Matcher m = CELL.matcher(entry);
            if (!m.matches()) {
                fail("Unexpected ProB square representation: '" + square
                    + "' (entry='" + entry + "')");
            }
            String pos = m.group(1) + "↦" + m.group(2);
            if (!positions.add(pos)) {
                fail("Duplicate board position in ProB state: " + square);
            }
            cells.add(pos + "↦" + m.group(3));
        }
        if (cells.size() > 9) {
            fail("Too many board cells: " + square);
        }
        Collections.sort(cells);
        return "turn=" + turn + ";square={" + String.join(",", cells) + "}";
    }

    private static String sample(Set<String> values) {
        List<String> items = new ArrayList<>(values);
        return items.subList(0, Math.min(3, items.size())).toString()
                + (items.size() > 3 ? " ... total=" + items.size() : "");
    }

    private static void fail(String message) {
        throw new AssertionError("B_EQUIVALENCE FAIL " + message);
    }
}
