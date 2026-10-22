package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/** Self-contained behavioral regressions; ProB is NOT run by these tests. */
public class VIStandaloneTests {
    private static final ExplorationStrategy EXPLORATION = ExplorationStrategy.PREPROCESS;

    private static void assertClose(String what, double actual, double expected, double eps) {
        if (!Double.isFinite(actual) || Math.abs(actual-expected)>eps) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }
    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
    private static Transition edge(State source, State target, double reward) {
        Transition t = new Transition(source, target, reward);
        source.add(t);
        return t;
    }
    private static void testNegativeRewardAndTerminal() {
        Environment env = new Environment();
        State start = new State(1), terminal = new State(2);
        Transition t = edge(start, terminal, -2.0);
        env.add(1,start); env.add(2,terminal);
        ValueIteration vi = new ValueIteration(env,0.9,0.01,20);
        vi.learn(EXPLORATION);
        require(vi.getStatus()==ValueIteration.TerminationStatus.CONVERGED, "negative cost must converge");
        assertClose("negative V(start)",vi.getValue(start),-2.0,1e-12);
        assertClose("terminal V",vi.getValue(terminal),0.0,1e-12);
        assertClose("Q at final V",vi.getQValues(start).get(t),-2.0,1e-12);
        assertClose("global residual",vi.getLastBellmanResidual(),0.0,1e-12);
    }
    private static void testLimitIsNotConvergence() {
        Environment env = new Environment();
        State s = new State(1);
        Transition t = edge(s,s,-1.0);
        env.add(1,s);
        ValueIteration vi = new ValueIteration(env,0.9,0.01,1);
        vi.learn(EXPLORATION);
        require(vi.getStatus()==ValueIteration.TerminationStatus.ITERATION_LIMIT_REACHED,
                "limit must not be reported as convergence");
        assertClose("first update V",vi.getValue(s),-1.0,1e-12);
        assertClose("nonzero residual",vi.getLastBellmanResidual(),0.9,1e-12);
        assertClose("final Q despite cap",vi.getQValues(s).get(t),-1.9,1e-12);
    }
    private static void testDelayedRewardQFinal() {
        Environment env = new Environment();
        State a = new State(1), b = new State(2), end = new State(3);
        Transition ab = edge(a,b,-0.25), be = edge(b,end,1.0);
        env.add(1,a); env.add(2,b); env.add(3,end);
        ValueIteration vi = new ValueIteration(env,0.9,0.01,20);
        vi.learn(EXPLORATION);
        require(vi.getStatus()==ValueIteration.TerminationStatus.CONVERGED, "chain must converge");
        assertClose("V(b)",vi.getValue(b),1.0,1e-12);
        assertClose("V(a)",vi.getValue(a),0.65,1e-12);
        assertClose("final Q(a,ab)",vi.getQValues(a).get(ab),0.65,1e-12);
        assertClose("final Q(b,be)",vi.getQValues(b).get(be),1.0,1e-12);
    }
    private static void testNegativeSelfLoopConverges() {
        Environment env = new Environment();
        State s = new State(1);
        edge(s,s,-1.0); env.add(1,s);
        ValueIteration vi = new ValueIteration(env,0.9,0.01,100);
        vi.learn(EXPLORATION);
        require(vi.getStatus()==ValueIteration.TerminationStatus.CONVERGED, "negative self-loop converged");
        require(vi.getLastBellmanResidual()<=0.01, "residual must be globally <= theta");
        assertClose("V approx negative fixed point",vi.getValue(s),-10.0,0.1);
    }
    private static void testNoneIsRejected() {
        ValueIteration vi = new ValueIteration(new Environment(),0.9,0.01,5);
        try {
            vi.learn(ExplorationStrategy.NONE);
            throw new AssertionError("NONE should not be accepted");
        } catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        testNegativeRewardAndTerminal();
        testLimitIsNotConvergence();
        testDelayedRewardQFinal();
        testNegativeSelfLoopConverges();
        testNoneIsRejected();
        System.out.println("VI_STANDALONE PASS 5 tests (ProB-independent)");
    }
}
