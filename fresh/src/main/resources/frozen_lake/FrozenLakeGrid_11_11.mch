/*
  reward
  gamma : 0,95
  Alpha : 0,7
  Epsilon : 1.0
  Max ep : 50000
  Max steps : 500
  decay : 0.01 - 0.0001
*/

MACHINE
    FrozenLakeGrid_11_11

INCLUDES
    FrozenLake

PROMOTES
    left,right,up,down,check

DEFINITIONS
    "rewards.def";

PROPERTIES
    maxX : NAT1
    & maxY : NAT1
    & maxX = 10
    & maxY = 10

    & goalX : 0..maxX
    & goalY : 0..maxY
    & goalX = 10
    & goalY = 0

    & Holes <: (0..maxX) * (0..maxY)
    & Holes = { 6|->10, 5|->9, 6|->8, 8|->8, 2|->4, 9|->3, 7|->1, 
		5|->4, 5|->3, 3|->1, 5|->0,
		6|->5, 7|->6, 8|->7,
		7|->2, 7|->3, 7|->0
		}

END