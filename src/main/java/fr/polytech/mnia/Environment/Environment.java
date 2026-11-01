package fr.polytech.mnia.Environment;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import de.prob.animator.domainobjects.IEvalElement;
import de.prob.check.ConsistencyChecker;
import de.prob.check.IModelCheckListener;
import de.prob.check.IModelCheckingResult;
import de.prob.check.ModelCheckingOptions;
import de.prob.check.ModelCheckingOptions.Options;
import de.prob.check.StateSpaceStats;
import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * Abstract class representing a learning environment for reinforcement learning agents.
 * It manages model loading, state exploration, and reward computation.
 * <p>
 * This environment is designed to work with ProB state spaces.
 * It provides multiple exploration strategies and interfaces with model checking when needed.
 * </p>
 * 
 * Subclasses must implement a reward function and a pretty printer for debugging or visualization.
 * 
 * @author [Akram Idani (akram.idani@univ-grenoble-alpes.fr)]
 */
public abstract class Environment {

    /** ProB animator used to manage the state space. */
    private MyProb animator = MyProb.INJECTOR.getInstance(MyProb.class);

    /** Initial state of the system. */
    private State initial;

    /** Current state (can be updated during execution or interaction). */
    private State state;

    /** Set of discovered state IDs during exploration. */
    private Set<Integer> stateIds;

    private IEvalElement rewardFormula ;

    /**
     * Constructs the environment from a given B machine file path.
     *
     * @param filePath the path to the .mch file to load
     */
    public Environment(String filePath) {
        try {
            animator.load(filePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.initial = animator.getStateSpace().getRoot();
        this.stateIds = new HashSet<>();  
    }

    public void addStateID(Integer id){
        this.stateIds.add(id) ;
    }
    public void registerRewardFormula(String rewardFormulaStr){
        this.rewardFormula = this.animator.getStateSpace().getModel().parseFormula(rewardFormulaStr);   
        this.animator.getStateSpace().subscribe(null,Collections.singleton(rewardFormula)) ;
    }

    public Double evalFormulas(State s){
        return Double.parseDouble(s.getValues().get(this.rewardFormula).toString().replaceAll("[\\{\\}]", ""));
    }

    /**
     * Initializes the state machine by executing SETUP_CONSTANTS and INITIALISE_MACHINE transitions.
     */
    public void initialise() {
        Transition setup = initial.findTransition(Transition.SETUP_CONSTANTS_NAME);
        if (setup != null) {
            initial = setup.getDestination();
        }

        Transition initialisation = initial.findTransition(Transition.INITIALISE_MACHINE_NAME);
        if (initialisation != null) {
            initial = initialisation.getDestination();
        }

        this.state = initial.exploreIfNeeded();
    }

    /**
     * Returns the set of explored state IDs.
     *
     * @return a set of state IDs
     */
    public Set<Integer> getStateIds() {
        return this.stateIds;
    }

    /**
     * Retrieves a state object by its ID.
     *
     * @param id the identifier of the state
     * @return the corresponding State object
     */
    public State gState(Integer id) {
        return this.animator.getStateSpace().getState(id);
    }

    /**
     * Returns the current state.
     *
     * @return the current State object
     */
    public State gState() {
        return this.state;
    }

    /**
     * Explores the state space using the specified strategy.
     *
     * @param strategy the exploration strategy to use
     */
    public void explore(ExplorationStrategy eStrategy) {
        System.out.println("Start exploration");
        long startTime = System.nanoTime();       

        switch (eStrategy) {
            case PREPROCESS:
                this.modelCheck();
                break;
            case RECURSIVE:
                this.recursive(this.initial, new HashSet<>(), -1, -1);
                break;
            case NONE:
                break;
        }

        double duration = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.out.println("\nEnd of exploration " + this.stateIds.size() + " | Exploration time: " + duration + " seconds");
    }

    /**
     * Recursively explores the state space from a given state.
     * Depth and breadth can be limited by setting maxDepth or maxBreadth.
     * Use -1 for unlimited depth or breadth.
     *
     * @param state the current state to explore from
     * @param visited set of already visited states
     * @param maxDepth maximum depth allowed (-1 for unlimited)
     * @param maxBreadth maximum number of children per state (-1 for unlimited)
     */
    private void recursive(State root, Set<State> visited,
                       int maxDepth, int maxBreadth) {

    class Frame {
        final int depth;
        final Iterator<Transition> transitions;
        int count = 0;

        Frame(State state, int depth) {
            this.depth = depth;
            this.transitions = (depth != -1 && depth <= 0)
                ? Collections.emptyIterator()
                : state.getOutTransitions().iterator();
        }
    }

    Deque<Frame> stack = new ArrayDeque<>();

    if (!visited.add(root)) return;

    this.stateIds.add(Integer.parseInt(root.getId()));
    stack.push(new Frame(root, maxDepth));

    while (!stack.isEmpty()) {
        Frame frame = stack.peek();

        if ((maxBreadth != -1 && frame.count >= maxBreadth)
                || !frame.transitions.hasNext()) {
            stack.pop();
            continue;
        }

        Transition transition = frame.transitions.next();
        frame.count++;

        State next = transition.getDestination();

        if (visited.add(next)) {
            this.stateIds.add(Integer.parseInt(next.getId()));

            int nextDepth = frame.depth == -1
                ? -1 : frame.depth - 1;

            stack.push(new Frame(next, nextDepth));
        }
    }
}

    /**
     * Performs a model check to explore the entire state space.
     * Updates the state ID set based on the result of the model-checking.
     */
    private void modelCheck() {
        final int[] nodes = {0};
        ConsistencyChecker modelChecker = new ConsistencyChecker(
            initial.getStateSpace(),
            new ModelCheckingOptions(Collections.singleton(Options.IGNORE_OTHER_ERRORS)),
            new IModelCheckListener() {
                @Override
                public void updateStats(String jobId, long timeElapsed, IModelCheckingResult result,
                                        StateSpaceStats stats) {
                    if (stats == null) return;
                    System.out.print(".");
                }

                @Override
                public void isFinished(String jobId, long timeElapsed, IModelCheckingResult result,
                                       StateSpaceStats stats) {
                    System.out.println(result.getMessage() + "Total states: " + stats.getNrTotalNodes());
                    nodes[0] = stats.getNrTotalNodes();
                }
            }
        );
        modelChecker.call();
        if (nodes[0] < 2) {
            throw new IllegalStateException(
                "ProB returned no usable nodes during preprocessing");
        }

        // ProB's total node count includes the synthetic root and, depending on
        // the B machine, intermediate SETUP_CONSTANTS / INITIALISE_MACHINE states.
        // The learner must only receive states AFTER machine initialisation.
        // Preserve the fast model-checker traversal: do not enumerate all edges
        // again through the Java API to discover the reachable state set.
        Set<Integer> administrativeIds = new HashSet<>();
        State cursor = animator.getStateSpace().getRoot();
        for (String transitionName : new String[] {
                Transition.SETUP_CONSTANTS_NAME,
                Transition.INITIALISE_MACHINE_NAME }) {
            Transition administrative = cursor.findTransition(transitionName);
            if (administrative == null) continue;
            cursor = administrative.getDestination();
            if (!cursor.getId().equals(initial.getId())) {
                try {
                    administrativeIds.add(Integer.parseInt(cursor.getId()));
                } catch (NumberFormatException ignored) {
                    // The synthetic root has a non-numeric identifier.
                }
            }
        }
        if (!cursor.getId().equals(initial.getId())) {
            throw new IllegalStateException(
                "Cannot identify the initialized ProB game state: expected "
                + initial.getId() + " but found " + cursor.getId());
        }

        // State IDs are sequential in the ProB state-space representation used
        // by this backend. Unlike the previous implementation, explicitly remove
        // any numeric setup/initialisation states instead of subtracting only
        // the synthetic root from the total count.
        this.stateIds = IntStream.rangeClosed(0, nodes[0] - 2)
                                 .filter(id -> !administrativeIds.contains(id))
                                 .boxed()
                                 .collect(Collectors.toSet());
        int initializedId = Integer.parseInt(initial.getId());
        if (!stateIds.contains(initializedId)) {
            throw new IllegalStateException(
                "Preprocessing omitted the initialized B state " + initializedId);
        }
    }

    /**
     * Pretty prints the environment.
     * This method must be implemented by concrete subclasses.
     */
    protected abstract void prettyPrint();

    /**
     * Computes the reward associated with a given transition.
     *
     * @param s the current state
     * @param a the transition taken
     * @param sPrime the resulting state after the transition
     * @return the numerical reward for the transition
     */
    public abstract double reward(State s, Transition a, State sPrime);

    // Optional: define if using stochastic transitions
    // public abstract double getTransitionProbability(State s, Transition a, State sPrime);
}
