Set(i,j,nr) = 
PRE 
  i : 1..fullsize & j : 1..fullsize &
  j /: dom(Sudoku9(i)) & /* case vide */
  nr : NRS &
  nr /: ran(Sudoku9(i)) & /* nr pas déjà dans la même ligne */
  nr /: ran(Column(j)) &  /* nr pas déjà dans la même colonne */
  !(xi, yi).(xi: SUBSqIndx & yi: SUBSqIndx & i:xi & j:yi => nr /: {Sudoku9(i1)(j1) | i1:xi & j1:yi & j1:dom(Sudoku9(i1))}) /* nr pas déjà dans le même sous-carré */
THEN
  Sudoku9(i) := Sudoku9(i) \/ {j |-> nr}
END

6670903752021072936960

6.67×10^21
 