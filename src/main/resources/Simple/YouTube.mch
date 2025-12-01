MACHINE 
    YouTube
SETS
    videos = {
                Tutoriel_Python,
                Vlog_de_voyage,
                Musique_populaire,
                Gaming
            }
DEFINITIONS
    SET_PREF_MAX_OPERATIONS == 100 ;
    SET_PREF_MAXINT == 10000 ;
    SET_PREF_INVARIANT_CHECKING == FALSE ;
VARIABLES
    step
INVARIANT
    step : NAT
INITIALISATION
    step := 0 
OPERATIONS
duration <-- choose(vv) =
    PRE vv : videos THEN
        SELECT vv = Tutoriel_Python THEN
            SELECT step : 0..100 THEN
                duration := 25
            WHEN step : 101..200 THEN
                duration := 70
            ELSE 
                duration := 100
            END
        WHEN vv = Vlog_de_voyage THEN
            SELECT step : 0..300 THEN
                duration := 50
            WHEN step : 301..600 THEN
                duration := 0
            ELSE 
                duration := 20
            END
        WHEN vv = Musique_populaire THEN
            SELECT step : 0..200 THEN
                duration := 100
            WHEN step : 201..500 THEN
                duration := 50
            ELSE 
                duration := 25
            END
        WHEN vv = Gaming THEN
            duration := 100
        END
        || step := step + 1
    END 
END