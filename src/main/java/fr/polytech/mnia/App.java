package fr.polytech.mnia;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

import de.prob.statespace.State;
import de.prob.statespace.Transition;
import fr.polytech.mnia.Environment.Environment;
import fr.polytech.mnia.Environment.EnvironmentFactory;
import fr.polytech.mnia.Environment.ExplorationStrategy;
import fr.polytech.mnia.Environment.RewardStrategy;

public class App {
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

        String envName = "tictactoe";
        if (args.length >= 4) {
            envName = args[3];
        }

        System.out.println("Selected environment = " + envName);

        // Create environment from factory
        Environment env = EnvironmentFactory.create(envName, rewardStrategy);

        // Create agent (factory already exists)
        Agent agent = AgentFactory.create(algo, env, envName);

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
        
        agent.learn(exploration); // Run learning with exploration

        System.out.println("Nb states discovered (env): " + env.getStateIds().size());

        if (env instanceof TicTacToe ticTacToe) {
            playStepByStep(agent, ticTacToe);
        } else {
            System.out.println("Interactive play is only supported for TicTacToe. Skipping step-by-step display.");
        }
        System.exit(0);
    }
    
    static void playStepByStep(Agent agent, TicTacToe env) {
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
    }  
}
