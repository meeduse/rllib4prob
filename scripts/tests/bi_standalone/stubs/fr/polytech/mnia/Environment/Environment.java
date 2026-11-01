package fr.polytech.mnia.Environment;
import de.prob.statespace.State;
import de.prob.statespace.Transition;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
public class Environment {
    private final Map<Integer, State> states = new HashMap<>();
    public void add(int id, State state){states.put(id,state);}
    public Set<Integer> getStateIds(){return states.keySet();}
    public State gState(int id){return states.get(id);}
    public void explore(ExplorationStrategy strategy){}
    public double reward(State s, Transition a, State successor){return a.getReward();}
}
