MACHINE InterlockingReq

SETS
  TRAIN   = {tt01, tt02, tt03} ;
  SECTION = {ss01, ss02, ss03, ss04, ss05, ss06, ss07, ss08,
             sb01, sb02, sb03} ;
  DIR       = {FW, BW} ;
  STOPMODE  = {STOP, NOSTOP} ;
  SPEEDMODE = {SLOW, FAST}

DEFINITIONS
  SET_PREF_MAXINT == 100 ;
  SET_PREF_SHOW_EVENTB_ANY_VALUES == TRUE ;
  SET_PREF_MAX_OPERATIONS == 20 ;
  SET_PREF_INVARIANT_CHECKING == FALSE ;

  REQ_IN(tt)  == prj1(reqtab(tt));
  REQ_DIR(tt) == prj1(prj2(reqtab(tt)));
  REQ_OUT(tt) == prj1(prj2(prj2(reqtab(tt))));

  NbDone == card(dom(reqtab) - pending);

  Goal == pending = {} & acttrn = {};

NbAtGoal == card({
  tt | tt:acttrn &
       trainpos(tt) = REQ_OUT(tt)
});

HeadToHeadBad ==
  #(t1,t2).(
    t1:acttrn & t2:acttrn & t1 /= t2 &
    trainpos(t1) /: (pltf1 \/ pltf2) &
    trainpos(t2) /: (pltf1 \/ pltf2) &
    traindir(t1) = FW &
    traindir(t2) = BW &
    (trainpos(t1) |-> trainpos(t2)) : nextss
  )
;

/*
[QLearning] episode=49900 | epsilon=0,0161 | steps(lastEp)=48 | states=70487 | goals=46420
Q-learning finished. Total states visited: 70487 after 50000 episodes.
Total pipeline | episodes = 50000 | total = 211711 ms
Total states : 100 228
Total states visited: 9950 after 40000 episodes.
Q-learning :
  gamma : 0.95
  alpha : 0.1
  epsilon : 1.0
  episodes : 30000
  steps : 150
  decay : true
  min : 0.01
  rate : 0.0001
*/

reward ==
IF Goal THEN
    5000.0 /*si on met 50 il s'arrete sur un deadlock*/
ELSE
    -1.0
END
;


reward1 ==
IF Goal THEN
    5000.0 /*si on met 50 il s'arrete sur un deadlock*/
ELSE
    -1.0
    + (5.0 * real(NbDone))
END
;

/*
VI : 
gamma : 0.95, Theta : 0.001, Max Iter : 250
Génial : les trains bougent ensemble et ont une section entre les 2 pour la safety
*/
reward2 ==
  IF Goal THEN
      50000.0
  ELSE
      -1.0
      + (50.0 * real(NbDone))
      - (10.0 * real(card(pending)))
      - (2.0 * real(card(acttrn)))
      - (0.2 * real(card(dom(resss))))
      - (IF HeadToHeadBad THEN 10000.0 ELSE 0.0 END)
  END
;

CONSTANTS
  nextss, pltf1, pltf2, reqtab

PROPERTIES
  nextss = {
    ss01|->ss02,
    ss02|->ss03,
    ss03|->ss04,
    ss04|->ss05,
    ss05|->ss06,
    ss06|->ss07,
    ss07|->ss08,
    ss03|->sb01,
    sb01|->sb02,
    sb02|->sb03
  } &

  pltf1 = {ss03, ss08} &
  pltf2 = {sb03} &

  reqtab = {
    tt01 |-> (ss01 |-> (FW |-> (ss08 |-> (NOSTOP |-> FAST))))
    //, tt02 |-> (ss01 |-> (FW |-> (sb03 |-> (STOP   |-> SLOW))))
    , tt03 |-> (sb03 |-> (BW |-> (ss08 |-> (STOP   |-> SLOW))))
  } &

  nextss : SECTION <-> SECTION &
  pltf1 <: SECTION &
  pltf2 <: SECTION &
  reqtab : TRAIN +-> (SECTION * (DIR * (SECTION * (STOPMODE * SPEEDMODE))))

VARIABLES
  acttrn, trainpos, traindir,
  resss,
  restail,
  pending

INVARIANT
  acttrn <: dom(reqtab) &
  trainpos : acttrn >-> SECTION &
  traindir : acttrn --> DIR &

  resss : SECTION +-> TRAIN &
  ran(resss) <: acttrn &
  restail : acttrn --> SECTION &
  pending <: dom(reqtab) &

  dom(trainpos) = acttrn &
  dom(traindir) = acttrn &
  dom(restail) = acttrn &

  !(tt).(tt:acttrn =>
      trainpos(tt):dom(resss) &
      resss(trainpos(tt)) = tt) &

  !(tt).(tt:acttrn =>
      restail(tt):dom(resss) &
      resss(restail(tt)) = tt) &

  trainpos : acttrn >-> SECTION

INITIALISATION
  acttrn := {} ||
  trainpos := {} ||
  traindir := {} ||
  resss := {} ||
  restail := {} ||
  pending := dom(reqtab)

OPERATIONS

  AddTrain(tt) =
  PRE
    tt:pending &
    tt/:acttrn &
    REQ_IN(tt)/:ran(trainpos) &
    REQ_IN(tt)/:dom(resss)
  THEN
    acttrn := acttrn \/ {tt} ||
    trainpos := trainpos \/ {tt |-> REQ_IN(tt)} ||
    traindir := traindir \/ {tt |-> REQ_DIR(tt)} ||
    resss := resss <+ {REQ_IN(tt) |-> tt} ||
    restail := restail <+ {tt |-> REQ_IN(tt)}
  END;

  ReserveNext(tt,ss) =
  PRE
    tt:acttrn &
    ss:SECTION &
    (
      (traindir(tt)=FW & (restail(tt) |-> ss) : nextss) or
      (traindir(tt)=BW & (ss |-> restail(tt)) : nextss)
    ) &
    ss/:dom(resss) &
    ss/:ran(trainpos)
  THEN
    resss := resss <+ {ss |-> tt} ||
    restail(tt) := ss
  END;

MoveStepT(TT, mv) =
PRE
  TT <: acttrn &
  TT /= {} &
  mv : TT >-> SECTION &

  /* les destinations sont distinctes */
  !(t1,t2).(t1:TT & t2:TT & t1 /= t2 => mv(t1) /= mv(t2)) &

  /* chaque train va vers une section libre ou libérée par un train de TT */
  !(tt).(tt:TT =>
    mv(tt):SECTION &
    mv(tt) /: (ran(trainpos) - trainpos[TT]) &
    (
      (traindir(tt)=FW & (trainpos(tt)|->mv(tt)):nextss) or
      (traindir(tt)=BW & (mv(tt)|->trainpos(tt)):nextss)
    ) &
    mv(tt):dom(resss) &
    resss(mv(tt)) = tt
  )
THEN
  resss := trainpos[TT] <<| resss ||
  trainpos := trainpos <+ mv ||
  restail := restail <+ mv
END;

/*
MoveStep(tt, ss) =
PRE
  tt:acttrn &
  ss:SECTION &
  ss/:ran(trainpos) &
  (
    (traindir(tt)=FW & (trainpos(tt)|->ss):nextss) or
    (traindir(tt)=BW & (ss|->trainpos(tt)):nextss)
  ) &
  ss:dom(resss) &
  resss(ss)=tt
THEN
  resss := {trainpos(tt)} <<| resss ||
  trainpos(tt) := ss ||
  restail(tt) := ss
END;
*/

  TurnDir(tt,dd) =
  PRE
    tt:acttrn &
    dd:DIR &
    dd /= traindir(tt) &
    trainpos(tt) : (pltf1 \/ pltf2)
  THEN
    traindir(tt) := dd
  END;

  ExitTrain(tt) =
  PRE
    tt:acttrn &
    trainpos(tt) = REQ_OUT(tt)
  THEN
    resss := {trainpos(tt)} <<| resss ||
    acttrn := acttrn - {tt} ||
    trainpos := {tt} <<| trainpos ||
    traindir := {tt} <<| traindir ||
    restail := {tt} <<| restail ||
    pending := pending - {tt}
  END

END