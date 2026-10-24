package fr.polytech.mnia.Environment;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diagnosis only: compares PREPROCESS IDs against reachable B-operation IDs.
 * Does not change Environment, B specifications or any planning algorithm.
 */
public final class FrozenLakeStateSpaceAudit {
    private static String id(State s) {
        return s == null ? "<none>" : s.getId();
    }

    private static void audit(String machine, RewardStrategy strategy) {
        System.out.println("\n====== " + machine + " ======");
        FrozenLakeEnvironment env = new FrozenLakeEnvironment(machine, strategy);
        State gameInitial = env.gState();
        State root = gameInitial.getStateSpace().getRoot();
        Transition setup = root.findTransition(Transition.SETUP_CONSTANTS_NAME);
        State afterSetup = setup == null ? root : setup.getDestination();
        Transition init = afterSetup.findTransition(Transition.INITIALISE_MACHINE_NAME);
        System.out.printf("rootId=%s afterSetupId=%s initialGameId=%s initDestinationId=%s%n",
                id(root), id(afterSetup), id(gameInitial),
                init == null ? "<none>" : id(init.getDestination()));

        env.explore(ExplorationStrategy.PREPROCESS);
        Set<Integer> preprocess = new TreeSet<>(env.getStateIds());
        System.out.println("PREPROCESS count=" + preprocess.size()
                + " first=" + (preprocess.isEmpty() ? "-" : ((TreeSet<Integer>)preprocess).first())
                + " last=" + (preprocess.isEmpty() ? "-" : ((TreeSet<Integer>)preprocess).last()));

        Set<Integer> reachable = new TreeSet<>();
        ArrayDeque<State> todo = new ArrayDeque<>();
        todo.add(gameInitial);
        long transitions = 0;
        while (!todo.isEmpty()) {
            State s = todo.removeFirst();
            int numericId = Integer.parseInt(s.getId());
            if (!reachable.add(numericId)) continue;
            for (Transition t : s.getOutTransitions()) {
                if (Transition.INITIALISE_MACHINE_NAME.equals(t.getPrettyName())
                        || Transition.SETUP_CONSTANTS_NAME.equals(t.getPrettyName())) {
                    throw new IllegalStateException("Administrative transition among B operation transitions: "
                            + t.getPrettyName() + " from state " + s.getId());
                }
                transitions++;
                todo.addLast(t.getDestination());
            }
        }

        Set<Integer> missing = new TreeSet<>(reachable);
        missing.removeAll(preprocess);
        Set<Integer> unexpected = new TreeSet<>(preprocess);
        unexpected.removeAll(reachable);
        System.out.println("REACHABLE gameStateCount=" + reachable.size()
                + " BTransitionCount=" + transitions);
        System.out.println("REACHABLE min=" + (reachable.isEmpty() ? "-" : ((TreeSet<Integer>)reachable).first())
                + " max=" + (reachable.isEmpty() ? "-" : ((TreeSet<Integer>)reachable).last()));
        System.out.println("PREPROCESS_MISSING_GAME_IDS=" + missing);
        System.out.println("PREPROCESS_EXTRA_IDS=" + unexpected);
        System.out.println("GRAPH_ID_MATCH=" + preprocess.equals(reachable));
    }

    public static void main(String[] args) {
        audit("/frozen_lake/FrozenLakeGrid_4_4.mch", RewardStrategy.ONCEANDFORALL);
        audit("/frozen_lake/FrozenLakeGrid_4_4Bis.mch", RewardStrategy.EMBEDDED);
    }
}
