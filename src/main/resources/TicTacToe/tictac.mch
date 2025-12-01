MACHINE 
    tictac
CONCRETE_VARIABLES  
    square, turn
INVARIANT  
	turn : (0..1) & square : ((1..3) * (1..3)) +-> (0..1) 
DEFINITIONS
	SET_PREF_MAX_OPERATIONS == 15 ;
	SET_PREF_INVARIANT_CHECKING == FALSE ;
win(p) == 
    (#x.(x : 1..3 & !y.(y : 1..3 => (x|->y)|->p : square)))     /* Ligne */
    or (#y.(y : 1..3 & !x.(x : 1..3 => (x|->y)|->p : square)))  /* Colonne */
    or (!x.(x : 1..3 => (x|->x)|->p : square))                  /* Diagonale 1 */
    or (!x.(x : 1..3 => (x|->(4-x))|->p : square));             /* Diagonale 2 */

INITIALISATION 
    turn,square := 0,{}
OPERATIONS
place(xx,yy) =
    PRE	
        xx : 1..3 
        & yy : 1..3 
        & (xx |-> yy) /: dom(square)
	    & not(win(1 - turn))        
    THEN
        square(xx|->yy) := turn || turn := 1 - turn
    END
END

