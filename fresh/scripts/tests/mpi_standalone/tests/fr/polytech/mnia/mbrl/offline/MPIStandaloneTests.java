package fr.polytech.mnia.mbrl.offline;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;

/** Local deterministic algorithm regressions; ProB is not invoked. */
public final class MPIStandaloneTests {
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
    private static void near(String desc, double actual, double expected, double tolerance) {
        check(Double.isFinite(actual) && Math.abs(actual-expected) <= tolerance,
              desc+" expected="+expected+" actual="+actual);
    }
    private static Transition connect(State from, State to, double reward) {
        Transition t = new Transition(from, to, reward);
        from.add(t);
        return t;
    }
    private static void negativeTerminal() {
        Environment env = new Environment();
        State a = new State(1), terminal = new State(2);
        Transition e = connect(a,terminal,-2.0);
        env.add(1,a); env.add(2,terminal);
        ModifiedPolicyIteration mpi = new ModifiedPolicyIteration(env,.9,.01,20,1);
        mpi.learn(ExplorationStrategy.PREPROCESS);
        check(mpi.getStatus()==ModifiedPolicyIteration.TerminationStatus.CONVERGED,"negative action convergence");
        near("negative V",mpi.getValue(a),-2,1e-12);
        near("terminal",mpi.getValue(terminal),0,1e-12);
        near("final Q",mpi.getQValues(a).get(e),-2,1e-12);
    }
    private static void improvementAndFinalQ() {
        Environment env = new Environment();
        State a=new State(1), b=new State(2), terminal=new State(3);
        Transition bad=connect(a,terminal,-2), good=connect(a,b,-.25), bg=connect(b,terminal,1);
        env.add(1,a); env.add(2,b); env.add(3,terminal);
        ModifiedPolicyIteration mpi=new ModifiedPolicyIteration(env,.9,.01,20,1);
        mpi.learn(ExplorationStrategy.PREPROCESS);
        check(mpi.getStatus()==ModifiedPolicyIteration.TerminationStatus.CONVERGED,"improvement convergence");
        near("optimal V(a)",mpi.getValue(a),.65,1e-12);
        near("optimal V(b)",mpi.getValue(b),1,1e-12);
        near("good Q",mpi.getQValues(a).get(good),.65,1e-12);
        near("bad Q",mpi.getQValues(a).get(bad),-2,1e-12);
        near("terminal action Q",mpi.getQValues(b).get(bg),1,1e-12);
    }
    private static void stablePolicyNeedsMoreEvaluations() {
        Environment env = new Environment();
        State s = new State(1); connect(s,s,-1.0); env.add(1,s);
        ModifiedPolicyIteration mpi=new ModifiedPolicyIteration(env,.9,.01,100,1);
        mpi.learn(ExplorationStrategy.PREPROCESS);
        check(mpi.getStatus()==ModifiedPolicyIteration.TerminationStatus.CONVERGED,
              "stable policy eventually converges with repeated partial evaluation");
        check(mpi.getIterations()>1,"must NOT stop on stable policy in iteration 1");
        check(mpi.getLastBellmanResidual()<=.01,"certified global residual");
        near("self-loop value",mpi.getValue(s),-10,.1);
    }
    private static void iterationLimitIsNotConvergence() {
        Environment env = new Environment();
        State s = new State(1);connect(s,s,-1);env.add(1,s);
        ModifiedPolicyIteration mpi=new ModifiedPolicyIteration(env,.9,1e-12,2,1);
        mpi.learn(ExplorationStrategy.PREPROCESS);
        check(mpi.getStatus()==ModifiedPolicyIteration.TerminationStatus.ITERATION_LIMIT_REACHED,
              "iteration cap is not convergence");
        check(mpi.getLastBellmanResidual()>1e-12,"incomplete evaluation residual positive");
    }
    private static void policyChangeOnLastStep() {
        Environment env = new Environment();
        State a=new State(1), terminal=new State(2);
        connect(a,terminal,-2);connect(a,terminal,3);
        env.add(1,a);env.add(2,terminal);
        ModifiedPolicyIteration mpi=new ModifiedPolicyIteration(env,.9,.01,1,1);
        mpi.learn(ExplorationStrategy.PREPROCESS);
        check(mpi.getStatus()==ModifiedPolicyIteration.TerminationStatus.ITERATION_LIMIT_REACHED,
              "changed policy at max iteration must not converge");
        near("unreevaluated V",mpi.getValue(a),-2,1e-12);
        near("nonzero residual",mpi.getLastBellmanResidual(),5,1e-12);
    }
    private static void emptyAndNone() {
        ModifiedPolicyIteration mpi=new ModifiedPolicyIteration(new Environment(),.9,.01,5,1);
        try {mpi.learn(ExplorationStrategy.NONE);throw new AssertionError("NONE must fail");}
        catch(IllegalArgumentException expected) {}
        mpi.learn(ExplorationStrategy.PREPROCESS);
        check(mpi.getStatus()==ModifiedPolicyIteration.TerminationStatus.NO_STATES,"empty exploration");
    }
    private static void invalidParameters() {
        try {new ModifiedPolicyIteration(new Environment(),1.,.01,5,1);
            throw new AssertionError("gamma >=1 should fail");}
        catch(IllegalArgumentException expected){}
        try {new ModifiedPolicyIteration(new Environment(),.9,.01,5,0);
            throw new AssertionError("zero partial evaluation budget should fail");}
        catch(IllegalArgumentException expected){}
    }
    public static void main(String[] args) {
        negativeTerminal();
        improvementAndFinalQ();
        stablePolicyNeedsMoreEvaluations();
        iterationLimitIsNotConvergence();
        policyChangeOnLastStep();
        emptyAndNone();
        invalidParameters();
        System.out.println("MPI_STANDALONE PASS 7 tests (without ProB)");
    }
}
