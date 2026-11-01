package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/** Algorithmic regressions without ProB: real ProB validation remains necessary. */
public final class PVIStandaloneTests {
    private static void yes(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void near(String label, double actual, double expected, double tolerance) {
        yes(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                label + " expected=" + expected + " actual=" + actual);
    }
    private static Transition edge(State from, State to, double reward) {
        Transition t = new Transition(from, to, reward);
        from.add(t);
        return t;
    }
    private static void negativeRewards() {
        Environment e = new Environment();
        State a = new State(1), goal = new State(2);
        Transition t = edge(a, goal, -2);
        e.add(1,a);e.add(2,goal);
        PrioritizedValueIterationV1 p = new PrioritizedValueIterationV1(e,.9,.01,100);
        p.learn(ExplorationStrategy.PREPROCESS);
        yes(p.getStatus() == PrioritizedValueIterationV1.TerminationStatus.CONVERGED,"negative rewards");
        near("negative state value",p.getValue(a),-2,1e-12);
        near("terminal value",p.getValue(goal),0,1e-12);
        near("negative Q",p.getQValues(a).get(t),-2,1e-12);
    }
    private static void propagatesToPredecessorAndRefreshesQ() {
        Environment e = new Environment();
        State a=new State(1), b=new State(2), end=new State(3);
        Transition cheap=edge(a,end,.5), best=edge(a,b,0.0), award=edge(b,end,1.0);
        e.add(1,a); e.add(2,b); e.add(3,end);
        PrioritizedValueIterationV1 p = new PrioritizedValueIterationV1(e,.9,1e-10,100);
        p.learn(ExplorationStrategy.PREPROCESS);
        yes(p.getStatus() == PrioritizedValueIterationV1.TerminationStatus.CONVERGED,"propagation");
        near("V(a)",p.getValue(a),.9,1e-12);
        near("V(b)",p.getValue(b),1.,1e-12);
        near("Q(a,b) final",p.getQValues(a).get(best),.9,1e-12);
        near("Q(a,end) final",p.getQValues(a).get(cheap),.5,1e-12);
        near("Q(b,end) final",p.getQValues(b).get(award),1,1e-12);
        yes(p.getQValues(end).isEmpty(),"terminal Q empty");
    }
    private static void selfLoopConvergesByGlobalResidual() {
        Environment e = new Environment();
        State s = new State(1);edge(s,s,-1);e.add(1,s);
        PrioritizedValueIterationV1 p = new PrioritizedValueIterationV1(e,.9,.01,200);
        p.learn(ExplorationStrategy.PREPROCESS);
        yes(p.getStatus() == PrioritizedValueIterationV1.TerminationStatus.CONVERGED,"cyclic convergence");
        yes(p.getUpdates()>1,"must repeat updates on self-loop");
        yes(p.getLastBellmanResidual()<=.01,"global residual certificate");
        near("self loop approximate V",p.getValue(s),-10,.11);
    }
    private static void updateLimitNotConvergence() {
        Environment e = new Environment();
        State s = new State(1);edge(s,s,-1);e.add(1,s);
        PrioritizedValueIterationV1 p = new PrioritizedValueIterationV1(e,.9,1e-12,2);
        p.learn(ExplorationStrategy.PREPROCESS);
        yes(p.getStatus() == PrioritizedValueIterationV1.TerminationStatus.UPDATE_LIMIT_REACHED,"hard cap not convergence");
        yes(p.getUpdates()==2,"only two updates performed");
        yes(p.getLastBellmanResidual()>1e-12,"residual remains positive");
    }
    private static void emptyAndNone() {
        PrioritizedValueIterationV1 p=new PrioritizedValueIterationV1(new Environment(),.9,.01,5);
        try {p.learn(ExplorationStrategy.NONE);throw new AssertionError("NONE must fail");}
        catch(IllegalArgumentException expected) {}
        p.learn(ExplorationStrategy.PREPROCESS);
        yes(p.getStatus()==PrioritizedValueIterationV1.TerminationStatus.NO_STATES,"empty exploration");
    }
    private static void invalidAndMissingSuccessor() {
        try {new PrioritizedValueIterationV1(new Environment(),1.0,.01,5);
            throw new AssertionError("gamma=1 must be rejected");}
        catch(IllegalArgumentException expected){}
        try {new PrioritizedValueIterationV1(new Environment(),.9,.01,-1);
            throw new AssertionError("negative cap must be rejected");}
        catch(IllegalArgumentException expected){}
        Environment e = new Environment();
        State s = new State(1), missing = new State(2);
        edge(s,missing,1); e.add(1,s);
        PrioritizedValueIterationV1 p = new PrioritizedValueIterationV1(e,.9,.01,5);
        try {p.learn(ExplorationStrategy.PREPROCESS);
            throw new AssertionError("missing successor must be rejected");}
        catch(IllegalStateException expected) {}
    }
    private static void zeroCapAndAlreadyConverged() {
        Environment e = new Environment();
        State s=new State(1);edge(s,s,0);e.add(1,s);
        PrioritizedValueIterationV1 p=new PrioritizedValueIterationV1(e,.9,.01,0);
        p.learn(ExplorationStrategy.PREPROCESS);
        yes(p.getStatus()==PrioritizedValueIterationV1.TerminationStatus.CONVERGED,
            "zero cap must still converge if initial V is Bellman fixed point");
        yes(p.getUpdates()==0,"zero updates");
    }
    public static void main(String[] args) {
        negativeRewards();
        propagatesToPredecessorAndRefreshesQ();
        selfLoopConvergesByGlobalResidual();
        updateLimitNotConvergence();
        emptyAndNone();
        invalidAndMissingSuccessor();
        zeroCapAndAlreadyConverged();
        System.out.println("PVI_STANDALONE PASS 7 tests (without ProB)");
    }
}
