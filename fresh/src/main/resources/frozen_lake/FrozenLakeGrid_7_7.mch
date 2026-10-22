MACHINE
    FrozenLakeGrid_7_7

INCLUDES
    FrozenLake

PROMOTES
    left,right,up,down,check

DEFINITIONS
    "rewards.def";

PROPERTIES
    maxX : NAT1
    & maxY : NAT1
    & maxX = 6
    & maxY = 6

    & goalX : 0..maxX
    & goalY : 0..maxY
    & goalX = 6
    & goalY = 0

    & Holes <: (0..maxX) * (0..maxY)
    & Holes = { 6|->6, 2|->4, 0|->2, 2|->2 }

END