package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

public final class BIStandaloneTests {
    private static int count;
    private static void eq(double expected, double actual) {
        if (!(Math.abs(expected - actual) < 1e-10)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
    private static Transition connect(State a, State b, double r) {
        Transition t = new Transition(a,b,r);a.add(t);return t;
    }
    private static void test(String name, Runnable action) {
        action.run();count++;System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        test("zero horizon rejected", () -> {
            boolean rejected=false;
            try {new BackwardInductionV1(new Environment(),0.9,0);} catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"horizon zero accepted");
        });
        test("negative reward and terminal value", () -> {
            Environment env=new Environment();State a=new State(1),b=new State(2);
            env.add(1,a);env.add(2,b);Transition t=connect(a,b,-0.25);
            BackwardInductionV1 bi=new BackwardInductionV1(env,0.9,1);
            bi.learn(ExplorationStrategy.PREPROCESS);
            eq(-0.25,bi.getValue(a));eq(0.0,bi.getValue(b));eq(-0.25,bi.getQValues(a).get(t));
            check(bi.getStatus()==BackwardInductionV1.TerminationStatus.HORIZON_COMPLETED,"wrong status");
        });
        test("two-step chain values and horizon", () -> {
            Environment env=new Environment();State a=new State(1),b=new State(2),c=new State(3);
            env.add(1,a);env.add(2,b);env.add(3,c);
            Transition t=connect(a,b,-0.25); connect(b,c,1.0);
            BackwardInductionV1 bi=new BackwardInductionV1(env,0.9,2);
            bi.learn(ExplorationStrategy.RECURSIVE);
            eq(0.65,bi.getValue(a));eq(1.0,bi.getValue(b));eq(0.65,bi.getQValues(a).get(t));
            check(bi.getCompletedHorizon()==2,"wrong completed horizon");
            check(bi.getUpdateBackups()==6,"wrong backup count");
        });
        test("finite-horizon cycle does not claim convergence", () -> {
            Environment env=new Environment();State a=new State(1);env.add(1,a);connect(a,a,1.0);
            BackwardInductionV1 bi=new BackwardInductionV1(env,0.9,3);
            bi.learn(ExplorationStrategy.PREPROCESS);
            eq(1 + 0.9 + 0.81,bi.getValue(a));
            check(bi.getStatus()==BackwardInductionV1.TerminationStatus.HORIZON_COMPLETED,"wrong status");
        });
        test("final Q includes all competing actions", () -> {
            Environment env=new Environment();State a=new State(1),b=new State(2);
            env.add(1,a);env.add(2,b);
            Transition t1=connect(a,b,-1),t2=connect(a,b,-2);
            BackwardInductionV1 bi=new BackwardInductionV1(env,0.9,2);
            bi.learn(ExplorationStrategy.PREPROCESS);
            eq(-1,bi.getValue(a));eq(-1,bi.getQValues(a).get(t1));eq(-2,bi.getQValues(a).get(t2));
        });
        test("unexplored successor rejected", () -> {
            Environment env=new Environment();State a=new State(1),b=new State(2);env.add(1,a);
            connect(a,b,1.0);
            boolean rejected=false;
            try {new BackwardInductionV1(env,0.9,1).learn(ExplorationStrategy.PREPROCESS);}
            catch(IllegalStateException e){rejected=true;}
            check(rejected,"unknown successor was silently assigned zero value");
        });
        test("empty exploration explicitly reported", () -> {
            BackwardInductionV1 bi=new BackwardInductionV1(new Environment(),0.9,1);
            bi.learn(ExplorationStrategy.PREPROCESS);
            check(bi.getStatus()==BackwardInductionV1.TerminationStatus.NO_STATES,"wrong status");
        });
        System.out.println("BI_STANDALONE PASS tests=" + count);
    }
}
