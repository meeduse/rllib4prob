MACHINE
    FrozenLakeGrid_4_4

INCLUDES
    FrozenLake

PROMOTES
    left,right,up,down,check

DEFINITIONS
    "rewards.def";

PROPERTIES

    maxX = 3
    & maxY = 3

    & goalX = 3
    & goalY = 0

    & Holes =
      {
        1|->2,
        3|->2,
        3|->1,
        0|->0
      }

END