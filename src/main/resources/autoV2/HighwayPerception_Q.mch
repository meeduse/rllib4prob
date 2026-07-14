MACHINE
    HighwayPerception_Q

DEFINITIONS

    vmax == 50; //120
    comfort_speed == 30; //40

    safe_distance == 20;
    target_distance == 30;
    max_distance == 95;

    slow_step == 1;
    brake_step == 10;

    relative_speed_front == front_speed - ego_speed;

    collision == bool(distance_front = 0);

    safe == bool(distance_front >= safe_distance);

    danger ==
        bool(distance_front < safe_distance &
             relative_speed_front < 0);

    distance_error ==
        IF distance_front > target_distance THEN
            distance_front - target_distance
        ELSE
            target_distance - distance_front
        END;
  
    road_speed == ego_speed ;

    goal ==
        bool(
            distance_front >= target_distance - 2 &
            distance_front <= target_distance + 2 &
            ego_speed = front_speed
        );

speed_error ==
    IF ego_speed > comfort_speed THEN
        ego_speed - comfort_speed
    ELSE
        comfort_speed - ego_speed
    END;

    clamp_dist(d) ==
        IF d < 0 THEN
            0
        ELSIF d > max_distance THEN
            max_distance
        ELSE
            d
        END;

    clamp_speed(v) ==
        IF v < 0 THEN
            0
        ELSIF v > vmax THEN
            vmax
        ELSE
            v
        END ;

reward ==
    IF collision = TRUE THEN
        -10000
    ELSE
        1000
        - (30 * distance_error)
        - (20 * speed_error)
        - (IF distance_front < safe_distance THEN 2000 ELSE 0 END)
    END ;

/* Safety definitions */
next_speed_faster == clamp_speed(ego_speed + 1);

next_distance_faster ==
    distance_front + (front_speed - next_speed_faster);

next_distance_idle ==
    distance_front + (front_speed - ego_speed);

safe_after_faster ==
    bool(next_distance_faster >= safe_distance);

safe_after_idle ==
    bool(next_distance_idle >= safe_distance)


VARIABLES

    distance_front,
    ego_speed,
    front_speed

INVARIANT

    distance_front : 0..max_distance &
    ego_speed : 0..vmax &
    front_speed : 0..vmax

INITIALISATION

    distance_front, ego_speed, front_speed := 100, 60, 25 //15

/*
gamma 0.8, alpha 0.1 (SARSA) 0.7 (QLearning), epsilon 0.1, 
ep 30000 (20000 Qlearning), step 100 (80 Qlearning), decay false
*/
/*
Intersting initial values :
    distance_front, ego_speed, front_speed := 100, 60, 25
*/
OPERATIONS

r <-- FASTER =
    PRE
        collision = FALSE &
        ego_speed < vmax &
        safe_after_faster = TRUE
    THEN
	BEGIN
        	ego_speed := next_speed_faster ||
        	distance_front := clamp_dist(next_distance_faster)
	END;
        r := reward
    END;

r <-- IDLE =
    PRE
        collision = FALSE &
        safe_after_idle = TRUE
    THEN
	BEGIN
        	distance_front := clamp_dist(next_distance_idle) 
	END ;
        r := reward
    END;

r <-- SLOWER =
    PRE
        collision = FALSE &
        ego_speed > 0
    THEN
	BEGIN
        ego_speed :=
            clamp_speed(ego_speed - slow_step)

        ||

        distance_front :=
            clamp_dist(
                distance_front +
                (front_speed -
                 clamp_speed(ego_speed - slow_step))
            )

        END;

        r := reward

    END;

r <-- BRAKE =
    PRE
        collision = FALSE &
        ego_speed > 0
    THEN
	BEGIN
        ego_speed :=
            clamp_speed(ego_speed - brake_step)

        ||

        distance_front :=
            clamp_dist(
                distance_front +
                (front_speed -
                 clamp_speed(ego_speed - brake_step))
            )

        END;

        r := reward

    END

END