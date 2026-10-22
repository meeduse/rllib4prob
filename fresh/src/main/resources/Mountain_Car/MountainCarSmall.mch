MACHINE 	MountainCarSmall
INCLUDES 	MountainCar
PROMOTES	push_left, no_push, push_right

DEFINITIONS 	"rewards.def";

/*
Try : reward_success
  gamma : 0.99
  alpha : 0.2
  epsilon : 0.2 (decay false)
  max ep. : 60000
  max step : 600
*/

PROPERTIES
    minPosition = -120		
    & maxPosition = 60		
    & minVelocity = -14 
    & maxVelocity = 14		
    & goalPosition = 50		
    & startPosition = -50	
    & scale = 100		
    & ratio = 1000		

END
