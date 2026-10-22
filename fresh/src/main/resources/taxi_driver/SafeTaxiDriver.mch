MACHINE
    SafeTaxiDriver
     
CONSTANTS
    maxX, maxY,
    locCount, locX, locY,
    Danger

PROPERTIES
    maxX : NAT1
    & maxY : NAT1
    & locCount : NAT1
    & locCount >= 2
    & locX : 1..locCount --> 0..maxX
    & locY : 1..locCount --> 0..maxY
    & Danger : (0..maxX) <-> (0..maxY)
    & !(ii).(ii : 1..locCount => (locX(ii) |-> locY(ii)) /: Danger)
    & 0|->0 /: Danger
    
VARIABLES
    taxiX, taxiY,
    passengerLoc,
    destination,
    passengerInTaxi,
    delivered

INVARIANT
    taxiX : 0..maxX
    & taxiY : 0..maxY
    & passengerLoc : 1..locCount
    & destination : 1..locCount
    & passengerInTaxi : 0..1
    & delivered : 0..1

    /* safety invariant: the taxi is never on a dangerous cell */
    & (taxiX |-> taxiY) /: Danger

    /* consistency: once delivered, passenger is no longer in taxi */
    & (delivered = 1 => passengerInTaxi = 0)

    /* consistency: if passenger is in taxi, delivery is not completed */
    & (passengerInTaxi = 1 => delivered = 0)

    /* passenger and destination are distinct before delivery */
    & (delivered = 0 => passengerLoc /= destination)

INITIALISATION
    taxiX, taxiY := 0, 0 ||
    passengerLoc := 1 ||
    destination := 2 ||
    passengerInTaxi := 0 ||
    delivered := 0 

OPERATIONS

south =
PRE
    delivered = 0 & taxiY > 0
    & (taxiX |-> (taxiY - 1)) /: Danger
THEN
    taxiY := taxiY - 1 
END;

north =
PRE
    delivered = 0 & taxiY < maxY
    & (taxiX |-> (taxiY + 1)) /: Danger
THEN
    taxiY := taxiY + 1 
END;

east =
PRE
    delivered = 0 & taxiX < maxX
    & ((taxiX + 1) |-> taxiY) /: Danger
THEN
    taxiX := taxiX + 1 
END;

west =
PRE
    delivered = 0 & taxiX > 0
    & ((taxiX - 1) |-> taxiY) /: Danger
THEN
    taxiX := taxiX - 1 
END;

pickup =
PRE
    delivered = 0 & passengerInTaxi = 0
    & taxiX = locX(passengerLoc)
    & taxiY = locY(passengerLoc)
THEN
    passengerInTaxi := 1 
END;

dropoff =
PRE
    delivered = 0 & passengerInTaxi = 1
    & taxiX = locX(destination)
    & taxiY = locY(destination)
THEN
    passengerInTaxi := 0 ||
    delivered := 1 
END

END