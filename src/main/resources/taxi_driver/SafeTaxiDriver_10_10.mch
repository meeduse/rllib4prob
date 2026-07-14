MACHINE
    SafeTaxiDriver_10_10
INCLUDES
	SafeTaxiDriver
PROMOTES
	south, north, east, west, pickup, dropoff

DEFINITIONS
    "rewards.def" ;
        
PROPERTIES
    locCount = 4
    & maxX = 9
    & maxY = 9

    /* Four landmarks on a 10x10 grid: R,G,Y,B */
    & locX = { 2|->6, 1|->3, 3|->0, 4|->8 }
    & locY = { 2|->3, 1|->6, 3|->0, 4|->0 }

    & Danger = {
        1|->1, 2|->2, 3|->1,
        4|->4, 5|->4, 6|->4,
        4|->5, 5|->5, 6|->5,
        5|->1, 5|->2, 5|->3,
        2|->7, 3|->7, //7|->6,
        7|->2, 7|->1, 7|->0,
        8|->6
    }

END

