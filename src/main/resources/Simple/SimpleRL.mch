MACHINE
    SimpleRL
DEFINITIONS
    SET_PREF_MAX_OPERATIONS == 10 ;
SETS
    Films = {A, B, C};
    Result = {OK, KO}
VARIABLES
    res
INVARIANT
    res : Result
INITIALISATION
    res :: Result
OPERATIONS
    choose(film) = 
        PRE 
            film : Films
        THEN
            SELECT film = A THEN
                res := OK 
            WHEN film = B THEN
                res := OK 
            WHEN film = C THEN
                res := KO
            END
        END 
END
