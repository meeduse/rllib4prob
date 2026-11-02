package fr.polytech.mnia.exploration;

import java.util.HashSet;

/** Small no-ProB test for the immutable fixture catalog. */
public final class GraphBenchmarkCatalogTest {
    public static void main(String[] args) {
        var models = GraphBenchmarkCatalog.all();
        if (models.size() != 10 || new HashSet<>(models.stream().map(x -> x.id()).toList()).size() != 10) {
            throw new AssertionError("Expect 10 distinct B benchmark instances");
        }
        if (GraphBenchmarkCatalog.require("TICTACTOE").expectedEdges() != 16167) {
            throw new AssertionError("TicTacToe graph size changed");
        }
        if (GraphBenchmarkCatalog.require("puzzle").expectedStates() != 181440) {
            throw new AssertionError("Puzzle graph size changed");
        }
        for (var spec : models) {
            if (spec.variables().isEmpty() || spec.expectedEdges() <= 0) {
                throw new AssertionError("Missing B model variables/graph counts: " + spec.id());
            }
        }
        System.out.println("PASS: 10 benchmark specifications and reference graph sizes registered");
    }
}
