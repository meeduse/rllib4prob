MACHINE
    SafeTaxiDriver_5_5
INCLUDES
	SafeTaxiDriver
PROMOTES
	south, north, east, west, pickup, dropoff

DEFINITIONS
    "rewards.def" ;
        
PROPERTIES

    locCount = 4
    & maxX = 4
    & maxY = 4   
    & locX = { 1|->0, 2|->4, 3|->0, 4|->3 }
    & locY = { 1|->4, 2|->4, 3|->0, 4|->0 }
    & Danger = { 2|->4, 1|->2, 2|->2, 2|->0 }  

END

