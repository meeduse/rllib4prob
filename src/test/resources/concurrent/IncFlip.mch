MACHINE IncFlip
VARIABLES x, y
INVARIANT x : 0..2 & y : 0..1
INITIALISATION x := 0 || y := 0
OPERATIONS
  inc = PRE x < 2 THEN x := x + 1 END;
  flip = BEGIN y := 1 - y END
END
