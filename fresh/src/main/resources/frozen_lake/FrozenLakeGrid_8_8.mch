MACHINE
    FrozenLakeGrid_8_8

INCLUDES
    FrozenLake

PROMOTES
    left,right,up,down,check

DEFINITIONS
    "rewards.def";

PROPERTIES

    maxX = 7
    & maxY = 7

    & goalX = 7
    & goalY = 0

    & Holes =
      {
        3|->6,
        5|->6,
        6|->5,
        1|->4,
        4|->4,
        2|->3,
        5|->2,
        0|->1,
        6|->1
      }

END