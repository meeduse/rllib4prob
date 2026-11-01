#!/usr/bin/env python3
"""Self-contained parser tests based on real output field layouts, no ProB needed."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from summarize import collect
from run_campaign import extract_environment_mapping

BASE_EXPL='End of exploration 5478 | Exploration time: 4.714481042 seconds\n'
BASE_CACHE='REWARD_CACHE strategy=ONCEANDFORALL hits={hits} misses=5477 cachedStates=5477\n'
CASES={
    'VALUE_ITERATION': 'VI_RESULT status=CONVERGED gamma=0.9 theta=0.01 maxIterations=100 iterations=5 exploredStates=5478 updateBackups=27390 residualBackups=5478 totalBackups=32868 rewardLookups=97002 bellmanResidual=0 learningSeconds=44.100383',
    'POLICY_ITERATION': 'PI_RESULT status=CONVERGED gamma=0.9 theta=0.01 maxIterations=100 maxEvaluationSweeps=10000 iterations=3 evaluationSweeps=16 exploredStates=5478 evaluationBackups=72320 improvementBackups=13560 residualBackups=27390 totalBackups=113270 rewardLookups=166715 policyStable=true policyResidual=0 bellmanResidual=0 learningSeconds=45.427136',
    'MODIFIED_POLICY_ITERATION': 'MPI_RESULT status=CONVERGED gamma=0.9 theta=0.01 maxIterations=100 evalIterations=5 iterations=3 evaluationSweeps=12 exploredStates=5478 evaluationBackups=54240 improvementBackups=13560 residualBackups=21912 totalBackups=89712 rewardLookups=144115 policyStable=true evaluationDelta=0 policyResidual=0 bellmanResidual=0 learningSeconds=45.305023',
    'INCREMENTAL_VALUE_ITERATION': 'IVI_RESULT status=CONVERGED seed=0 gamma=0.9 theta=0.01 maxIterations=100 updatesPerIteration=500 iterations=33 exploredStates=5478 distinctUpdatedStates=4520 updateBackups=16500 residualBackups=21912 totalBackups=38412 rewardLookups=113366 bellmanResidual=0 orderingSeconds=33.538935 learningSeconds=41.408659',
    'PRIORITIZED_VALUE_ITERATION': 'PVI_RESULT status=CONVERGED gamma=0.9 theta=0.01 maxUpdates=100000 updates=4515 exploredStates=5478 transitions=16167 priorityBackups=23196 residualBackups=5478 finalQBackups=5478 totalBackups=34152 rewardLookups=122072 bellmanResidual=0 learningSeconds=45.169091',
    'BACKWARD_INDUCTION': 'BI_RESULT status=HORIZON_COMPLETED gamma=0.9 horizon=9 completedHorizon=9 exploredStates=5478 updateBackups=49302 totalBackups=49302 rewardLookups=145503 lastHorizonDelta=0 learningSeconds=44.160538',
}

class OfflineParser(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.folder=Path(self.tmp.name)
        (self.folder/'logs').mkdir()
    def tearDown(self):
        self.tmp.cleanup()
    def campaign(self, algo, line, cache=True, code=0, seed=0):
        text=BASE_EXPL
        if algo=='INCREMENTAL_VALUE_ITERATION':
            text+='IVI_ORDERING method=BFS_ACTIONS states=5478 transitions=16167 orderingSeconds=33.538935\n'
        text+=line+'\n'
        if cache:
            lookups=int(line.split('rewardLookups=')[1].split()[0])
            text+=BASE_CACHE.format(hits=lookups-5477)
        text+=f'RUN_EXIT_CODE={code}\n'
        (self.folder/'logs/run_000.log').write_text(text)
        (self.folder/'protocol.json').write_text(json.dumps({'algorithm':algo,'environment':'tictactoe','rewardStrategy':'ONCEANDFORALL','explorationStrategy':'PREPROCESS','ordering':'BFS_ACTIONS','expectedStates':5478}))
        (self.folder/'runs.json').write_text(json.dumps([{'run':0,'seed':seed if algo=='INCREMENTAL_VALUE_ITERATION' else None,'logFile':'logs/run_000.log'}]))
        rows,summary=collect(self.folder)
        return rows[0],summary
    def test_all_six_successful(self):
        for algo,line in CASES.items():
            with self.subTest(algo=algo):
                row,summary=self.campaign(algo,line)
                self.assertEqual(row['validationState'],'VERIFIED_SUCCESS',row['issue'])
                self.assertEqual(summary['successfulRuns'],1)
                self.assertEqual(row['status'],'HORIZON_COMPLETED' if algo=='BACKWARD_INDUCTION' else 'CONVERGED')
    def test_false_convergence_rejected(self):
        row,_=self.campaign('VALUE_ITERATION',CASES['VALUE_ITERATION'].replace('bellmanResidual=0 ','bellmanResidual=0.25 '))
        self.assertEqual(row['validationState'],'INVALID')
        self.assertIn('residual',row['issue'])
    def test_limit_is_not_convergence(self):
        row,summary=self.campaign('PRIORITIZED_VALUE_ITERATION',CASES['PRIORITIZED_VALUE_ITERATION'].replace('CONVERGED','UPDATE_LIMIT_REACHED'))
        self.assertEqual(row['validationState'],'VERIFIED_NONCONVERGENCE')
        self.assertEqual(summary['successfulRuns'],0)
    def test_incomplete_horizon_rejected(self):
        row,_=self.campaign('BACKWARD_INDUCTION',CASES['BACKWARD_INDUCTION'].replace('completedHorizon=9','completedHorizon=8'))
        self.assertEqual(row['validationState'],'INVALID')
    def test_no_result_is_invalid(self):
        row,_=self.campaign('VALUE_ITERATION','ERROR',cache=False)
        self.assertEqual(row['validationState'],'INVALID')
    def test_nonzero_exit_rejected(self):
        row,_=self.campaign('VALUE_ITERATION',CASES['VALUE_ITERATION'],code=1)
        self.assertEqual(row['validationState'],'INVALID')
    def test_known_environment_cases(self):
        p=self.folder/'factory.java'
        p.write_text('''case "tictactoe" -> new TicTacToeEnvironment("/TicTacToe/tictac.mch", rewardStrategy);\ncase "tictacembedded" -> new TicTacToeEnvironment("/TicTacToe/tictacEmbedded.mch", rewardStrategy);\ncase "mountain_car", "mountaincar" -> new MountainCarEnvironment("/Mountain_Car/MountainCarSmall.mch", rewardStrategy);''')
        m=extract_environment_mapping(p)
        self.assertEqual(m['tictacembedded'],'/TicTacToe/tictacEmbedded.mch')
        self.assertEqual(m['mountaincar'],'/Mountain_Car/MountainCarSmall.mch')

if __name__=='__main__': unittest.main()
