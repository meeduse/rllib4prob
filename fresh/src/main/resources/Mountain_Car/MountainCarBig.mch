MACHINE 	MountainCarBig
INCLUDES 	MountainCar
PROMOTES	push_left, no_push, push_right

DEFINITIONS 	"rewards.def";
/*  
   Experiments with: 
        gamma : 0.99
	alpha : 0.2
	epsilon : 0.35 (decay true)
	max episodes : 100000
	max steps : 500
   - reward_goal :
	=> Q-learning converged after 40900 episodes.
	=> Q-learning finished. Total states visited: 219315 after 40901 episodes.
	=> Total pipeline | episodes = 100000 | total = 1221558 ms (20mn)
	=> Good learning!
   - reward_balanced : 
	=> Q-learning converged after 65900 episodes.
	=> Q-learning finished. Total states visited: 226161 after 65900 episodes.
	=> Total pipeline | episodes = 100000 | total = 1093287 ms (18mn)
	=> Good learning!
   - reward_smooth :
	gamma = 0.99
	alpha = 0.1 ou 0.2
	epsilon = 0.20
	epsilon decay
	SARSA
	essayer SARSA (0.2,0.99,0.20 / 0.1,0.99,0.20/ 0.1,0.995,0.15)

   Scaling:
     position = real_position * 1000
     velocity = real_velocity * 1000

   Gymnasium reference:
     position in [-1.2, 0.6]
     velocity in [-0.07, 0.07]
     actions: push left, no push, push right
     v' = v + force - 0.0025*cos(3*x)
     x' = x + v'
     done when position >= 0.5

   Here:
     position : -1200..600
     velocity : -70..70
     goalPosition = 500

   This is suitable for a Q-table because the state space is finite.
*/

PROPERTIES
    minPosition = -1200		/* -120 */
    & maxPosition = 600		/*  60  */
    & minVelocity = -70 	/* -14  */
    & maxVelocity = 70		/*  14  */
    & goalPosition = 500	/*  50  */
    & startPosition = -500	/* -50  */
    & scale = 1000		/*  100 */
    & ratio = 10000		/*  1000 */

END
