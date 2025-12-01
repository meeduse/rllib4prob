package fr.polytech.mnia;

import java.util.Map;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

public abstract class Agent {
    protected Environment env ;
    protected double gamma, teta ;

    public Agent(Environment env, double gamma, double teta){
        this.env = env ;
        this.gamma = gamma ;
        this.teta = teta ;
    }
    public abstract void learn(ExplorationStrategy strategy) ;
    public abstract Map<Transition, Double> getQValues(State s) ;
}
