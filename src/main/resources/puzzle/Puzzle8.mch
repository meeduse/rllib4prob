MACHINE Puzzle8

// Exploration finished. Explored states: 181442 | time : 29155 ms
// MPI finished | core time = 16.370s | total time = 73.077s
// Total pipeline | states = 181441 | Cache states = 49444 ms | Cache rewards = 194945 ms | total = 317471 ms = 5,29mn


DEFINITIONS
   SET_PREF_INVARIANT_CHECKING == FALSE ;

  goalVal(i,j) == (j - 1 + (i - 1) * dim);

  GOAL ==
    !(i,j).(i:1..dim & j:1..dim => board(i|->j) = goalVal(i,j));

  correctCount ==
    card({i,j | i:1..dim & j:1..dim & board(i|->j) = goalVal(i,j)});

  reward ==
      (1.0 * real(correctCount))
    + (IF GOAL THEN 5.0 ELSE 0.0 END)
    - 0.05
  ;

CONSTANTS dim, nmax

PROPERTIES
  dim : NATURAL1 & dim = 3
  & nmax : NATURAL1 & nmax = dim * dim - 1

VARIABLES
  board,
  zx, zy

INVARIANT
  board : ((1..dim)*(1..dim)) --> (0..nmax)
  & zx : 1..dim
  & zy : 1..dim
  & board(zx|->zy) = 0
  & card(board~[{0}]) = 1
  & ran(board) = 0..nmax

INITIALISATION
  board := { x, y, z | x:1..dim & y:1..dim & z = goalVal(x,y) } ||
  zx := 1 ||
  zy := 1

OPERATIONS

  r <-- MoveDown =
    PRE zx > 1 THEN
      board := board <+ {
        (zx|->zy) |-> board((zx-1)|->zy),
        ((zx-1)|->zy) |-> 0
      } ||
      zx := zx - 1
      r := reward
    END;

  r <-- MoveUp =
    PRE zx < dim THEN
      board := board <+ {
        (zx|->zy) |-> board((zx+1)|->zy),
        ((zx+1)|->zy) |-> 0
      } ||
      zx := zx + 1
      r := reward 
    END;

  r <-- MoveRight =
    PRE zy > 1 THEN
      board := board <+ {
        (zx|->zy) |-> board(zx|->(zy-1)),
        (zx|->(zy-1)) |-> 0
      } ||
      zy := zy - 1
      r := reward
    END;

  r <-- MoveLeft =
    PRE zy < dim THEN
      board := board <+ {
        (zx|->zy) |-> board(zx|->(zy+1)),
        (zx|->(zy+1)) |-> 0
      } ||
      zy := zy + 1
      r := reward
    END

END