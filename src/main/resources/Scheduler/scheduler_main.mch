MACHINE 
    scheduler_main
INCLUDES
    scheduler
PROMOTES
    delete, activate, swap
DEFINITIONS
    SET_PREF_MAXINT == 10000 ;
    SET_PREF_MININT == -10000 ;
    SET_PREF_MAX_OPERATIONS == 100 ;
    SET_PREF_INVARIANT_CHECKING == FALSE ;
CONSTANTS
    time 
PROPERTIES
    time : PID --> NAT & 
    time = {(process1 |-> 10),
            (process2 |-> 5),
            (process3 |-> 2),
            (process4 |-> 4),
            (process5 |-> 7)}
VARIABLES
    tic, waiting_time, execution_time, latency_time
INVARIANT   
    tic : NAT
    & waiting_time : PID --> NAT
    & execution_time : PID +-> INT
    & latency_time : PID --> NAT
INITIALISATION
    tic := 0 ||
    waiting_time := PID * {0} ||
    execution_time := {} ||
    latency_time := PID * {0}
OPERATIONS
start(pp) = 
    PRE pp : PID THEN
        new(pp)
        || execution_time(pp) := time(pp)
    END ;   
free(pp) =
    PRE 
        pp : active & execution_time(pp) = 0 
    THEN
        execution_time := active <<| execution_time
        || latency_time(pp) := 0
        || deactivate
    END ;
step = 
    BEGIN
        tic := tic + 1
        || execution_time := 
            execution_time <+ {pp, ee | pp : active & ee = execution_time(pp) - 1}
        || waiting_time := 
            waiting_time <+ {pp, ee | pp : (waiting \/ ready) & ee = waiting_time(pp) + 1}
        || latency_time := 
            latency_time <+ {pp, ee | pp /: ((waiting \/ active) \/ ready) & ee = latency_time(pp) + 1}
    END
END