MACHINE
    MountainCar

DEFINITIONS
    "LibraryMath.def";
    "rewards.def";
    
CONSTANTS
    minPosition, maxPosition,
    minVelocity, maxVelocity,
    goalPosition,
    startPosition,
    scale,
    ratio

PROPERTIES
    minPosition : INTEGER
    & maxPosition : INTEGER
    & minVelocity : INTEGER
    & maxVelocity : INTEGER
    & goalPosition : INTEGER
    & startPosition : INTEGER
    & scale : NAT1
    & ratio : NAT1

VARIABLES
    position,
    velocity,
    done,
    lastAction

INVARIANT
    position : minPosition..maxPosition
    & velocity : minVelocity..maxVelocity
    & done : 0..1
    & lastAction : 0..3
    & (done = 1 => position >= goalPosition)

INITIALISATION
    position := startPosition ||
    velocity := 0 ||
    done := 0 ||
    lastAction := 0 

OPERATIONS

/*
   Approximation:
   rawV = velocity + force - 0.0025*cos(3*position_real)

   position is scaled by 1000, so COSx(1000, 3*position)
   approximates 1000*cos(3*position_real).

   gravity term scaled to velocity units:
   0.0025 * 1000 * cos = 2.5*cos
   approximated as (25 * COSx(...)) / 10000.
*/

r <-- push_left =
    SELECT done = 0 THEN
        ANY rawV, clippedV, rawP, clippedP, finalV, newDone WHERE
            rawV = velocity - 1 - ((25 * COSx(scale, 3 * position)) / ratio)
            & clippedV : minVelocity..maxVelocity
            & (   (rawV < minVelocity & clippedV = minVelocity)
               or (rawV > maxVelocity & clippedV = maxVelocity)
               or (rawV >= minVelocity & rawV <= maxVelocity & clippedV = rawV)
              )
            & rawP = position + clippedV
            & clippedP : minPosition..maxPosition
            & (   (rawP < minPosition & clippedP = minPosition)
               or (rawP > maxPosition & clippedP = maxPosition)
               or (rawP >= minPosition & rawP <= maxPosition & clippedP = rawP)
              )
            & finalV : minVelocity..maxVelocity
            & (   (clippedP = minPosition & clippedV < 0 & finalV = 0)
               or (not(clippedP = minPosition & clippedV < 0) & finalV = clippedV)
              )
            & newDone : 0..1
            & (   (clippedP >= goalPosition & newDone = 1)
               or (clippedP < goalPosition & newDone = 0)
              )
	    //& (clippedP /= position or finalV /= velocity or newDone /= done)
        THEN
            position := clippedP ||
            velocity := finalV ||
            done := newDone ||
            lastAction := 1 ||
            r := reward
        END
    END;

r <-- no_push =
    SELECT done = 0 THEN
        ANY rawV, clippedV, rawP, clippedP, finalV, newDone WHERE
            rawV = velocity - ((25 * COSx(scale, 3 * position)) / ratio)
            & clippedV : minVelocity..maxVelocity
            & (   (rawV < minVelocity & clippedV = minVelocity)
               or (rawV > maxVelocity & clippedV = maxVelocity)
               or (rawV >= minVelocity & rawV <= maxVelocity & clippedV = rawV)
              )
            & rawP = position + clippedV
            & clippedP : minPosition..maxPosition
            & (   (rawP < minPosition & clippedP = minPosition)
               or (rawP > maxPosition & clippedP = maxPosition)
               or (rawP >= minPosition & rawP <= maxPosition & clippedP = rawP)
              )
            & finalV : minVelocity..maxVelocity
            & (   (clippedP = minPosition & clippedV < 0 & finalV = 0)
               or (not(clippedP = minPosition & clippedV < 0) & finalV = clippedV)
              )
            & newDone : 0..1
            & (   (clippedP >= goalPosition & newDone = 1)
               or (clippedP < goalPosition & newDone = 0)
              )
	    //& (clippedP /= position or finalV /= velocity or newDone /= done)
        THEN
            position := clippedP ||
            velocity := finalV ||
            done := newDone ||
            lastAction := 2 ||
            r := reward
        END
    END;

r <-- push_right =
    SELECT done = 0 THEN
        ANY rawV, clippedV, rawP, clippedP, finalV, newDone WHERE
            rawV = velocity + 1 - ((25 * COSx(scale, 3 * position)) / ratio)
            & clippedV : minVelocity..maxVelocity
            & (   (rawV < minVelocity & clippedV = minVelocity)
               or (rawV > maxVelocity & clippedV = maxVelocity)
               or (rawV >= minVelocity & rawV <= maxVelocity & clippedV = rawV)
              )
            & rawP = position + clippedV
            & clippedP : minPosition..maxPosition
            & (   (rawP < minPosition & clippedP = minPosition)
               or (rawP > maxPosition & clippedP = maxPosition)
               or (rawP >= minPosition & rawP <= maxPosition & clippedP = rawP)
              )
            & finalV : minVelocity..maxVelocity
            & (   (clippedP = minPosition & clippedV < 0 & finalV = 0)
               or (not(clippedP = minPosition & clippedV < 0) & finalV = clippedV)
              )
            & newDone : 0..1
            & (   (clippedP >= goalPosition & newDone = 1)
               or (clippedP < goalPosition & newDone = 0)
              )
	    //& (clippedP /= position or finalV /= velocity or newDone /= done)
        THEN
            position := clippedP ||
            velocity := finalV ||
            done := newDone ||
            lastAction := 3 ||
            r := reward
        END
    END

END
