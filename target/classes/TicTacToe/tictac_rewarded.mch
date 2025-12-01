MACHINE
    tictac_rewarded
INCLUDES
    tictac
DEFINITIONS
	SET_PREF_MAX_OPERATIONS == 30 ;
	SET_PREF_INVARIANT_CHECKING == FALSE ;
    SET_PREF_SHOW_EVENTB_ANY_VALUES == TRUE ;
    
win(p) == 
    (#x.(x : 1..3 & !y.(y : 1..3 => (x|->y)|->p : square)))     /* Ligne */
    or (#y.(y : 1..3 & !x.(x : 1..3 => (x|->y)|->p : square)))  /* Colonne */
    or (!x.(x : 1..3 => (x|->x)|->p : square))                  /* Diagonale 1 */
    or (!x.(x : 1..3 => (x|->(4-x))|->p : square));             /* Diagonale 2 */

OPERATIONS
    r <-- play(xx,yy) = 
        PRE	
            xx : 1..3 
            & yy : 1..3 
            & (xx |-> yy) /: dom(square)
            & not(win(1 - turn))
        THEN
            place(xx,yy) ; 
            /* Compute reward deterministically */
            IF win(0) THEN
                r := 1.0
            ELSIF win(1) THEN
                r := -1.0
            ELSIF card(square) = 9 THEN
                r := 0.0
            ELSE
                r := -0.25
            END
        END
END