MACHINE
    FrozenLake

CONSTANTS
    maxX, maxY,
    Holes,
    goalX, goalY

DEFINITIONS
    "rewards.def";

PROPERTIES
    maxX : NAT1
    & maxY : NAT1
    & Holes <: (0..maxX)*(0..maxY)
    & goalX : 0..maxX
    & goalY : 0..maxY

VARIABLES
    posX,
    posY,
    reachedGoal,
    fellInHole,
    lastAction

INVARIANT
    posX : 0..maxX
    & posY : 0..maxY
    & reachedGoal : 0..1
    & fellInHole : 0..1
    & lastAction : 0..4

INITIALISATION
    posX := 0 ||
    posY := maxY ||
    reachedGoal := 0 ||
    fellInHole := 0 ||
    lastAction := 0

OPERATIONS

left =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        lastAction := 1 ||
        IF posX > 0 THEN
            posX := posX - 1 ||
            IF (posX - 1 |-> posY) : Holes THEN
                fellInHole := 1
            END
        END
    END;

right =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        lastAction := 2 ||
        IF posX < maxX THEN
            posX := posX + 1 ||
            IF (posX + 1 |-> posY) : Holes THEN
                fellInHole := 1
            END
        END
    END;

up =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        lastAction := 3 ||
        IF posY < maxY THEN
            posY := posY + 1 ||
            IF (posX |-> posY + 1) : Holes THEN
                fellInHole := 1
            END
        END
    END;

down =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        lastAction := 4 ||
        IF posY > 0 THEN
            posY := posY - 1 ||
            IF (posX |-> posY - 1) : Holes THEN
                fellInHole := 1
            END
        END
    END;

check =
    BEGIN
        IF posX = goalX & posY = goalY THEN
            reachedGoal := 1
        END ||
        IF (posX |-> posY) : Holes THEN
            fellInHole := 1
        END 
    END

END