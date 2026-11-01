package de.prob.statespace;
public final class Transition {
    private final State source, destination;
    private final double reward;
    public Transition(State source, State destination, double reward) {
        this.source=source;this.destination=destination;this.reward=reward;
    }
    public State getDestination(){return destination;}
    public State getSource(){return source;}
    public double getReward(){return reward;}
}
