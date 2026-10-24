MACHINE
    FrozenLakeBis

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

r <-- left =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        BEGIN
            lastAction := 1 ||
            IF posX > 0 THEN
                posX := posX - 1 ||
                IF (posX - 1 |-> posY) : Holes THEN
                    fellInHole := 1
                END
            END
        END;
        r := reward
    END;

r <-- right =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        BEGIN
            lastAction := 2 ||
            IF posX < maxX THEN
                posX := posX + 1 ||
                IF (posX + 1 |-> posY) : Holes THEN
                    fellInHole := 1
                END
            END
        END;
        r := reward
    END;

r <-- up =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        BEGIN
            lastAction := 3 ||
            IF posY < maxY THEN
                posY := posY + 1 ||
                IF (posX |-> posY + 1) : Holes THEN
                    fellInHole := 1
                END
            END
        END;
        r := reward
    END;

r <-- down =
    SELECT reachedGoal = 0 & fellInHole = 0 THEN
        BEGIN
            lastAction := 4 ||
            IF posY > 0 THEN
                posY := posY - 1 ||
                IF (posX |-> posY - 1) : Holes THEN
                    fellInHole := 1
                END
            END
        END;
        r := reward
    END;

r <-- check =
    BEGIN
        BEGIN
            IF posX = goalX & posY = goalY THEN
                reachedGoal := 1
            END ||
            IF (posX |-> posY) : Holes THEN
                fellInHole := 1
            END
        END;
        r := reward
    END

END
