package fr.polytech.mnia;

import de.prob.statespace.State;
import de.prob.statespace.Transition;

public class TicTacToe extends Environment {
    RewardStrategy rStrategy ;
    public TicTacToe(String filePath, RewardStrategy rStrategy) {
        super(filePath);
        this.rStrategy = rStrategy ;

        if(rStrategy == RewardStrategy.ONCEANDFORALL){
            String rewardFormulaStr =
                "{r | " +
                " (win(0) & not(win(1)) & r = 1.0) or " +
                " (win(1) & not(win(0)) & r = -1.0) or " +
                " (not(win(0) or win(1)) & card(square) = 9 & r = 0.0) or " +
                " (not(win(0) or win(1)) & card(square) /= 9 & r = -0.25)" +
                "}";

            this.registerRewardFormula(rewardFormulaStr);
        }

        this.initialise();
    }  
    
    @Override
    public double reward(State s, Transition a, State sPrime) {
        switch (rStrategy) {
            case ONCEANDFORALL:
                return this.evalFormulas(sPrime);  
            case ONTHEFLY:
                if (sPrime.eval("win(0)").toString().equals("TRUE")) {
                    return 1.0;
                } else if (sPrime.eval("win(1)").toString().equals("TRUE")) {
                    return -1.0;
                } else if (sPrime.getOutTransitions().isEmpty()) { // match nul
                    return 0.0;
                }
                return -0.25; // coût léger pour inciter à terminer rapidement
            case EMBEDDED:
                return Double.parseDouble(a.getReturnValues().get(0));
            default:
                return 0.0;
        }
    }
    
    public void prettyPrint(State state) {
        String input = state.eval("square").toString();
    
        String[][] board = {{" ", " ", " "}, {" ", " ", " "}, {" ", " ", " "}};
    
        // Enlever les accolades extérieures
        input = input.replaceAll("^\\{", "").replaceAll("}$", "");
    
        // Séparer chaque triplet (1↦1↦0)
        String[] entries = input.split("\\),\\(");
    
        for (String entry : entries) {
            // Nettoyer les parenthèses et espaces
            entry = entry.replace("(", "").replace(")", "").replaceAll("\\s+", "");
    
            String[] parts = entry.split("↦");
            if (parts.length != 3) continue;
    
            try {
                int row = Integer.parseInt(parts[0]) - 1;
                int col = Integer.parseInt(parts[1]) - 1;
                int val = Integer.parseInt(parts[2]);
    
                String symbol;
                switch (val) {
                    case 1:
                        symbol = "X";
                        break;
                    case 0:
                        symbol = "O";
                        break;
                    default:
                        symbol = " ";
                        break;
                }
    
                board[row][col] = symbol;
            } catch (Exception e) {
                System.err.println("Failed to parse entry: " + entry);
            }
        }
    
        printBoard(board);
    }
    
    

    private void printBoard(String[][] board) {
        for (int i = 0; i < 3; i++) {
            System.out.println(" " + board[i][0] + " | " + board[i][1] + " | " + board[i][2]);
            if (i < 2) System.out.println("---+---+---");
        }
    }
    

    /*@Override
    double getTransitionProbability(State s, Transition a, State sPrime) {
        return 1.0 ;
    }*/

    /*@Override
    public double getTerminalReward(State s) {
        // Suppose que State possède une méthode isWinningState(), isLosingState() et isDrawState()
        if (s.eval("win(0)").toString().equals("TRUE")) {
            return 1.0;    // Victoire
        } else if (s.eval("win(1)").toString().equals("TRUE")) {
            return -1.0;   // Défaite
        } else {
            return 0.0;    // Match nul
        } 
    }*/

    /*@Override
    public double getInitialReward(State s) {
        if (s.eval("win(0)").toString().equals("TRUE")) {
            return 1.0;    // Victoire
        } else if (s.eval("win(1)").toString().equals("TRUE")) {
            return -1.0;   // Défaite
        } else {
            return 0.0;    // autres états
        } 
    }*/

    @Override
    void prettyPrint() {
        throw new UnsupportedOperationException("Unimplemented method 'prettyPrint'");
    }
    
    
}
