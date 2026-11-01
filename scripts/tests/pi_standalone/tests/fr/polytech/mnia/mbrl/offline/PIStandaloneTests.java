package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/** Isolated deterministic regressions. Does not start ProB. */
public final class PIStandaloneTests {
    private static final ExplorationStrategy PRE = ExplorationStrategy.PREPROCESS;
    private static void ok(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }
    private static void near(String description, double actual, double wanted, double tol) {
        ok(Double.isFinite(actual) && Math.abs(actual-wanted)<=tol,
                description + ": wanted=" + wanted + ", actual=" + actual);
    }
    private static Transition edge(State from, State to, double reward) {
        Transition t = new Transition(from, to, reward);
        from.add(t);
        return t;
    }
    private static void testNegativeTerminalAndFinalQ() {
        Environment env = new Environment();
        State a = new State(1), end = new State(2);
        Transition ae = edge(a,end,-2.0);
        env.add(1,a); env.add(2,end);
        PolicyIteration pi = new PolicyIteration(env,0.9,0.01,20,50);
        pi.learn(PRE);
        ok(pi.getStatus()==PolicyIteration.TerminationStatus.CONVERGED,"negative terminal must converge");
        near("negative V",pi.getValue(a),-2,1e-12);
        near("terminal V",pi.getValue(end),0,1e-12);
        near("final Q",pi.getQValues(a).get(ae),-2,1e-12);
        near("Bellman residual",pi.getLastBellmanResidual(),0,1e-12);
    }
    private static void testPolicyImprovementAndChain() {
        Environment env = new Environment();
        State a=new State(1), b=new State(2), end=new State(3);
        Transition bad=edge(a,end,-2), good=edge(a,b,-0.25), be=edge(b,end,1.0);
        env.add(1,a);env.add(2,b);env.add(3,end);
        PolicyIteration pi=new PolicyIteration(env,0.9,0.01,20,100);
        pi.learn(PRE);
        ok(pi.getStatus()==PolicyIteration.TerminationStatus.CONVERGED,"must improve suboptimal initial policy");
        near("a V",pi.getValue(a),0.65,1e-12);
        near("b V",pi.getValue(b),1,1e-12);
        near("Q good",pi.getQValues(a).get(good),0.65,1e-12);
        near("Q bad",pi.getQValues(a).get(bad),-2,1e-12);
        near("Q be",pi.getQValues(b).get(be),1,1e-12);
    }
    private static void testEvaluationLimitNoFalseConvergence() {
        Environment env=new Environment();
        State s=new State(1);edge(s,s,-1);env.add(1,s);
        PolicyIteration pi=new PolicyIteration(env,0.9,1e-12,5,1);
        pi.learn(PRE);
        ok(pi.getStatus()==PolicyIteration.TerminationStatus.EVALUATION_LIMIT_REACHED,
                "evaluation sweep cap is not convergence");
        ok(pi.getLastBellmanResidual()>1e-12,"residual not small");
    }
    private static void testIterationLimitNoFalseConvergence() {
        Environment env=new Environment();
        State a=new State(1),end=new State(2);
        edge(a,end,-2);edge(a,end,3);
        env.add(1,a);env.add(2,end);
        PolicyIteration pi=new PolicyIteration(env,0.9,0.01,1,50);
        pi.learn(PRE);
        ok(pi.getStatus()==PolicyIteration.TerminationStatus.ITERATION_LIMIT_REACHED,
                "policy changed on last iteration, must NOT report converged");
        near("V before policy reevaluation", pi.getValue(a),-2,1e-12);
        near("optimality residual nonzero",pi.getLastBellmanResidual(),5,1e-12);
    }
    private static void testNegativeSelfLoopConverges() {
        Environment env=new Environment();
        State s=new State(1);edge(s,s,-1);env.add(1,s);
        PolicyIteration pi=new PolicyIteration(env,0.9,0.01,20,1000);
        pi.learn(PRE);
        ok(pi.getStatus()==PolicyIteration.TerminationStatus.CONVERGED,"self loop must converge");
        near("self loop approximate value",pi.getValue(s),-10,0.1);
        ok(pi.getLastBellmanResidual()<=0.01,"global residual bound");
    }
    private static void testRejectNone() {
        PolicyIteration pi=new PolicyIteration(new Environment(),0.9,0.01,5,20);
        try {
            pi.learn(ExplorationStrategy.NONE);
            throw new AssertionError("NONE must be rejected");
        } catch (IllegalArgumentException expected) {}
    }
    public static void main(String[] args) {
        testNegativeTerminalAndFinalQ();
        testPolicyImprovementAndChain();
        testEvaluationLimitNoFalseConvergence();
        testIterationLimitNoFalseConvergence();
        testNegativeSelfLoopConverges();
        testRejectNone();
        System.out.println("PI_STANDALONE PASS 6 tests (without ProB)");
    }
}
