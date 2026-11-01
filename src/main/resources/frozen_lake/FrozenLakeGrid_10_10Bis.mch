MACHINE
    FrozenLakeGrid_10_10Bis

INCLUDES
    FrozenLakeBis

PROMOTES
    left,right,up,down,check

DEFINITIONS
    "rewards.def";

PROPERTIES
    maxX = 9
    & maxY = 9

    & goalX = 9
    & goalY = 0

    & Holes =
      {
        6|->9,
        5|->9,
        6|->8,
        8|->8,
        2|->4,
        9|->3,
        7|->1,
        5|->4,
        5|->3,
        3|->1,
        5|->0,
        6|->5,
        7|->6,
        8|->7,
        7|->2,
        7|->3,
        7|->0
      }

END
