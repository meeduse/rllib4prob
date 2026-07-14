MACHINE
    FrozenLakeGrid_9_9

INCLUDES
    FrozenLake

PROMOTES
    left,right,up,down,check

DEFINITIONS
    "rewards.def";

PROPERTIES
    maxX : NAT1
    & maxY : NAT1
    & maxX = 8
    & maxY = 8

    & goalX : 0..maxX
    & goalY : 0..maxY
    & goalX = 8
    & goalY = 0

    & Holes <: (0..maxX) * (0..maxY)
    & Holes = { 6|->8, 7|->7, 1|->5, 3|->5, 5|->1 }

END