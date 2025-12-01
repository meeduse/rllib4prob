package fr.polytech.mnia;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.google.inject.Inject;

import de.prob.MainModule;
import de.prob.animator.domainobjects.AbstractEvalResult;
import de.prob.animator.domainobjects.FormulaExpand;
import de.prob.animator.domainobjects.IEvalElement;
import de.prob.scripting.Api;
import de.prob.statespace.State;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Transition;

public class MyProb {
    static Injector INJECTOR = 
        Guice.createInjector(Stage.PRODUCTION, 
            new AbstractModule(){
                @Override
                protected void configure() {
                    install(new MainModule()); 
                    // Install ProB 2.0 Injection bindings
                }
            }
        );
    private Api api;
    private StateSpace stateSpace ;

	@Inject
	public MyProb(Api api, String file) {
		this.api = api;
	}

    public void load(String bMachinePath) throws Exception{
        System.out.println("Load classical B Machine");
        Path path = Paths.get(getClass().getResource(bMachinePath).toURI());
        stateSpace = api.b_load(path.toAbsolutePath().toString());
        System.out.println("Load success");
    }

    public StateSpace getStateSpace(){
        return this.stateSpace ;
    }

    public void printActions(List<Transition> actions){
        System.out.println();
		for (Transition transition : actions) {
			System.out.println(transition.getId() + " : " + transition.getPrettyName() + '(' + transition.getParameterPredicate() + ')');
		}
		System.out.println();
    }

    public void printState(State state){
        System.out.println("State ID :" + state.getId());
        Map<IEvalElement, AbstractEvalResult> values 
                = state.getVariableValues(FormulaExpand.EXPAND) ;
        //System.out.println(values);
		Set<Entry<IEvalElement, AbstractEvalResult>> entrySet = values.entrySet();
		for (Entry<IEvalElement, AbstractEvalResult> entry : entrySet) {
			System.out.println(entry.getKey() + " -> " + entry.getValue());
		}
    }
}
