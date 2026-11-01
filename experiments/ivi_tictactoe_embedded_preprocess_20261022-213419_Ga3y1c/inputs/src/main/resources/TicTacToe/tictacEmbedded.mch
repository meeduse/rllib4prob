MACHINE
    tictacEmbedded

CONCRETE_VARIABLES
    square, turn

INVARIANT
    turn : (0..1) &
    square : ((1..3) * (1..3)) +-> (0..1)

DEFINITIONS
    win(p) ==
        bool((#x.(x : 1..3 & !y.(y : 1..3 => (x|->y)|->p : square)))
        or (#y.(y : 1..3 & !x.(x : 1..3 => (x|->y)|->p : square)))
        or (!x.(x : 1..3 => (x|->x)|->p : square))
        or (!x.(x : 1..3 => (x|->(4-x))|->p : square)));

    "rewards.def"

INITIALISATION
    turn, square := 0, {}

OPERATIONS
    r <-- place(xx, yy) =
        PRE
            xx : 1..3 &
            yy : 1..3 &
            (xx |-> yy) /: dom(square) &
            (win(1 - turn)) = FALSE
        THEN
            BEGIN
                square(xx |-> yy) := turn ||
                turn := 1 - turn
            END;
            r := reward
        END
END