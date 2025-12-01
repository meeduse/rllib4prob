package fr.polytech.mnia;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import de.prob.statespace.State;
import de.prob.statespace.Transition;

/**
 * A reinforcement learning agent that performs Value Iteration to compute
 * the value function and action-value function (Q-values) for a given environment.
 * <p>
 * This implementation iteratively updates state values until convergence or
 * a maximum number of iterations is reached.
 * </p>
 *
 * @author [Akram Idani (akram.idani@univ-grenoble-alpes.fr)]
 */
public class ValueIteration extends Agent {

    /** Maximum number of iterations to perform during value iteration. */
    private int maxIterations;

    /** Map from states to their estimated value V(s). */
    private Map<State, Double> vValues;

    /** Map from states to their action-value estimates Q(s,a). */
    protected Map<State, Map<Transition, Double>> qValues;    

    /**
     * Constructs a ValueIterationV1 agent with the given parameters.
     *
     * @param env the environment in which the agent learns
     * @param gamma the discount factor (typically between 0 and 1)
     * @param teta the threshold for convergence (minimum delta)
     * @param maxIterations the maximum number of iterations allowed
     */
    public ValueIteration(Environment env, double gamma, double teta, int maxIterations) {
        super(env, gamma, teta);
        this.maxIterations = maxIterations;
        this.vValues = new HashMap<>();
        this.qValues = new HashMap<>();
    }    

    /**
     * Performs value iteration using the given exploration strategy.
     * The method explores the environment, then iteratively updates
     * the value function V(s) and the Q-values Q(s,a) based on the Bellman equation.
     *
     * @param strategy the exploration strategy used to discover the state space
     */
    @Override
    public void learn(ExplorationStrategy eStrategy) {
        
        this.env.explore(eStrategy);

        System.out.println("Start learning");long startTime = System.nanoTime();   
        int iteration = 0;
        double delta;
        do {
            delta = 0.0;
            for (int sID : env.getStateIds()) {
                
                State s = env.gState(sID);                
                qValues.putIfAbsent(s, new HashMap<>());
                vValues.putIfAbsent(s, 0.0);                
                
                double maxQ = vValues.get(s);
                Map<Transition, Double> stateQValues = qValues.get(s);
                
                for (Transition t : s.getOutTransitions()) {
                    State sPrime = t.getDestination();
                    vValues.putIfAbsent(sPrime, 0.0);  

                    double reward = env.reward(s, t, sPrime);                    
                    
                    double qValue = reward + gamma * vValues.get(sPrime);                    
                    stateQValues.put(t, qValue);                  
                    
                    if (qValue > maxQ) {
                        maxQ = qValue;
                    }
                }

                double oldValue = vValues.get(s);
                vValues.put(s, maxQ);
                delta = Math.max(delta, Math.abs(oldValue - maxQ));
            }
            iteration++;
            System.out.println("Iteration: " + iteration + " | delta: " + delta);
        } while (delta > teta && iteration < maxIterations);

        long endTime = System.nanoTime();
        long duration = endTime - startTime; 
        double durationInSeconds = duration / 1_000_000_000.0;
        System.out.println("Execution time: " + durationInSeconds + " seconds");
    }

    @Override
    public Map<Transition, Double> getQValues(State s) {
        return this.qValues.getOrDefault(s, Collections.emptyMap());
    }    
}
