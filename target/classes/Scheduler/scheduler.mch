MACHINE scheduler
SETS
	PID = {process1, process2, process3, process4, process5}
VARIABLES 
        active, ready, waiting
INVARIANT  
        active <: PID 
        & ready <: PID   
        & waiting <: PID 
        & (ready /\ waiting) = {} 
        & active /\ (ready \/ waiting) = {} 
        & card(active) <= 1 
        & ((active = {}) => (ready = {}))       
INITIALISATION  
	active := {} || ready := {} || waiting := {}
OPERATIONS
new(pp) =
	PRE 
                pp : (PID - (active \/ (ready \/ waiting)))
	THEN
		waiting := waiting \/ {pp}
	END;
delete(pp) =
	PRE pp : waiting THEN
		waiting := waiting - {pp}
	END;	
activate(rr) =
        PRE rr : waiting THEN
                waiting := waiting - {rr} ||
                IF (active = {}) THEN
                   active := {rr}
                ELSE
                   ready := ready \/ {rr} 
                END
        END;  
swap(rr) = 
    PRE active /= {} & rr : ready THEN
        waiting := waiting \/ active ||
        ready := ready - {rr} ||
        active := {rr}
    END ;   	
deactivate =
        PRE active /= {} THEN
                IF (ready = {}) THEN
                        active := {}
                ELSE
                        ANY pp WHERE pp : ready THEN
                                active := {pp} ||
                                ready := ready - {pp} 
                        END
                END
        END       
END