package de.prob.statespace;
import java.util.ArrayList;
import java.util.List;
public final class State {
    private final int id;
    private final List<Transition> actions = new ArrayList<>();
    public State(int id) {this.id=id;}
    public String getId(){return Integer.toString(id);}
    public List<Transition> getOutTransitions(){return actions;}
    public void add(Transition transition){actions.add(transition);}
}
