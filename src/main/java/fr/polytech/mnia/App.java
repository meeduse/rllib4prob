package fr.polytech.mnia;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.ExplorationStrategy;
import fr.polytech.mnia.Environment.RewardStrategy;
import fr.polytech.mnia.MBRL.AlgorithmId;

public class App {
    Environment env ;

    private static String resolveMachinePath(RewardStrategy rs) {
        if (rs == RewardStrategy.EMBEDDED) {
            return "/TicTacToe/tictac_rewarded.mch";
        }
        return "/TicTacToe/tictac.mch";
    }
    
    public static void main(String[] args) {

        /* Read algorithm + reward strategy */
        AlgorithmId algo = AlgorithmId.BACKWARD_INDUCTION; // default

        RewardStrategy rewardStrategy = RewardStrategy.ONCEANDFORALL; // default

        if (args.length >= 1) {
            try {
                algo = AlgorithmId.valueOf(args[0]);
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown algorithm '" + args[0] + "'. Using default BACKWARD_INDUCTION.");
            }
        }

        if (args.length >= 2) {
            try {
                rewardStrategy = RewardStrategy.valueOf(args[1]);
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown reward strategy '" + args[1] + "'. Using default ONCEANDFORALL.");
            }
        }

        System.out.println("Selected algorithm = " + algo);
        System.out.println("Selected reward strategy = " + rewardStrategy);
        
        // Select B machine according to reward strategy
        String machinePath = resolveMachinePath(rewardStrategy);
        System.out.println("Selected machine = " + machinePath);
        
        // Create environment
        TicTacToe env = new TicTacToe(
                machinePath,
                rewardStrategy
        );

        // Create agent (factory already exists)        
        Agent agent = AgentFactory.create(algo, env);

        if (agent == null) {
            System.err.println("ERROR: Cannot create agent for algorithm " + algo);
            return;
        }
        
        // Run learning with exploration (unless NONE chosen)
        ExplorationStrategy exploration = ExplorationStrategy.PREPROCESS;

        if (args.length >= 3) {
            try {
                exploration = ExplorationStrategy.valueOf(args[2]);
            } catch (Exception e) {
                System.err.println("Unknown exploration strategy '" + args[2] + "'. Using PREPROCESS.");
            }
        }

        System.out.println("Exploration strategy = " + exploration);

        agent.learn(exploration);

        //playStepByStep(agent, env);
        System.exit(0);
    }
    
    static void playStepByStep(Agent agent, TicTacToe env) {
        do{
            try (Scanner scanner = new Scanner(System.in)) {
                State current = env.gState();
                System.out.println("Starting game from initial state:");
      
                while (!current.getOutTransitions().isEmpty()) {
                    List<Transition> actions = current.getOutTransitions();
      
                    System.out.println("\nAvailable actions and Q-values:");
                    Map<Transition, Double> qForState = agent.getQValues(current);
      
                    for (int i = 0; i < actions.size(); i++) {
                        Transition action = actions.get(i);
                        double qValue = qForState.getOrDefault(action, 0.0);
                        System.out.println(i + ": " + action + action.getParameterValues() + " => Q = " + qValue);
                    }
      
                    // Saisie utilisateur pour choisir une action
                    int choice = -1;
                    while (choice < 0 || choice >= actions.size()) {
                        System.out.print("\nChoose action [0-" + (actions.size() - 1) + "], or press ENTER to choose best: ");
                        String input = scanner.nextLine().trim();
      
                        if (input.isEmpty()) {
                            // Choisir la meilleure action automatiquement
                            double maxQ = Double.NEGATIVE_INFINITY;
                            for (Transition a : actions) {
                                double q = qForState.getOrDefault(a, 0.0);
                                if (q > maxQ) {
                                    maxQ = q;
                                    choice = actions.indexOf(a);
                                }
                            }
                            System.out.println("Best action selected automatically.");
                            break;
                        }
      
                        try {
                            choice = Integer.parseInt(input);
                            if (choice < 0 || choice >= actions.size()) {
                                System.out.println("Invalid choice. Try again.");
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid input. Please enter a number.");
                        }
                    }
      
                    Transition selectedAction = actions.get(choice);
                    System.out.println("\nAction chosen: " + selectedAction + selectedAction.getParameterValues());
      
                    // Appliquer l'action
                    current = selectedAction.getDestination();
                    System.out.println("\nNew state: " + current.eval("square").toString());
                    env.prettyPrint(current);
                }
      
                // Fin de partie
                System.out.println("\nGame ended.");
            }
        }while(true) ;
    }  
}
