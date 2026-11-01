#!/usr/bin/env python3
"""End-to-end isolated tests with a fake ProB launcher. No Maven/ProB required."""
import csv
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SRC = Path(__file__).resolve().parents[1]
MANAGER = SRC / 'manage_campaigns.py'

FAKE_RUNNER = r'''#!/usr/bin/env python3
import argparse, csv, hashlib, json
from pathlib import Path
import os
import tempfile
p=argparse.ArgumentParser()
for arg in ('algorithm','reward','exploration','environment','count','expected-states'):
    p.add_argument('--'+arg,required=True)
a=p.parse_args()
root=Path(__file__).resolve().parents[3]
algs={'VI':'VALUE_ITERATION','BI':'BACKWARD_INDUCTION'}
alg=algs[a.algorithm]
folder=Path(tempfile.mkdtemp(prefix=f'{alg.lower()}_{a.environment}_{a.reward.lower()}_{a.exploration.lower()}_',dir=root/'experiments/offline'))
print('New campaign: '+str(folder),flush=True)
rows=[]
for i in range(int(a.count)):
    row={'schemaVersion':'offline-common-v1','campaignId':folder.name, 'run':str(i),
         'seed':'','algorithm':alg,'environment':a.environment,'rewardStrategy':a.reward,
         'explorationStrategy':a.exploration,'status':'HORIZON_COMPLETED' if a.algorithm=='BI' else 'CONVERGED',
         'validationState':'VERIFIED_SUCCESS','issue':'','processExitCode':'0',
         'exploredStates':a.expected_states,'gamma':'0.9','theta':'0.01',
         'bellmanResidual':'0.00000','horizon':'150','completedHorizon':'150',
         'lastHorizonDelta':'0.00001', 'explorationSeconds':'0.1','learningSeconds':'0.2',
         'totalSeconds':'0.3','totalBackups':'20','rewardLookups':'20',
         'cacheHits':'18','cacheMisses':'2','cachedStates':'2','policyStable':'true'}
    rows.append(row)
with (folder/'results.csv').open('w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
summary={'campaignId':folder.name,'algorithm':alg,'environment':a.environment,
         'rewardStrategy':a.reward,'explorationStrategy':a.exploration,
         'totalRuns':len(rows),'successfulRuns':len(rows),'validationStates':{'VERIFIED_SUCCESS':len(rows)}}
(folder/'summary.json').write_text(json.dumps(summary))
inputs=[]
for rel in ['pom.xml','config/agent-parameters.properties','src/main/java/Mock.java',
            'src/main/resources/Mock.mch','scripts/experiments/offline/run_campaign.sh',
            'scripts/experiments/offline/fake_runner.py',
            'scripts/experiments/offline/campaigns/demo.json']:
    f=root/rel
    inputs.append(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+rel)
(folder/'input_sha256.txt').write_text('\n'.join(inputs)+'\n')
print('CHECK run 0: status=CONVERGED verification=VERIFIED_SUCCESS')
print(f'Completed: {len(rows)}/{len(rows)} verified successful runs')
'''


class CampaignFlow(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name) / 'fresh'
        (self.repo/'config').mkdir(parents=True)
        (self.repo/'src/main/java').mkdir(parents=True)
        (self.repo/'src/main/resources').mkdir(parents=True)
        scripts=self.repo/'scripts/experiments/offline'
        (scripts/'campaigns').mkdir(parents=True)
        (self.repo/'experiments/offline').mkdir(parents=True)
        (self.repo/'pom.xml').write_text('<project/>')
        (self.repo/'config/agent-parameters.properties').write_text('gamma=0.9\n')
        (self.repo/'src/main/java/Mock.java').write_text('public class Mock {}')
        (self.repo/'src/main/resources/Mock.mch').write_text('MACHINE Mock END')
        (scripts/'run_campaign.sh').write_text('#!/bin/bash\nexec python3 "$(dirname "$0")/fake_runner.py" "$@"\n')
        (scripts/'fake_runner.py').write_text(FAKE_RUNNER)
        (scripts/'campaigns/demo.json').write_text(json.dumps({
            'id':'demo','algorithms':['VI','BI'],'explorations':['PREPROCESS'],
            'repetitions':2,'gamma':0.9,'theta':0.01,'biHorizon':150,
            'variants':[{'id':'small','expectedStates':2,'environments':{'ONCEANDFORALL':'mock'}}],
        }))
        self.archive=Path(self.temp.name)/'private'

    def tearDown(self):
        self.temp.cleanup()

    def call(self, flag):
        return subprocess.run([sys.executable,str(MANAGER),'demo',flag,
                               '--repo',str(self.repo),'--archive',str(self.archive)],
                              text=True,capture_output=True,timeout=20)

    def test_all_then_reopen_status_and_archive(self):
        a=self.call('--run')
        self.assertNotEqual(a.returncode,0)
        self.assertIn('No valid validation marker',a.stderr)
        b=self.call('--all')
        self.assertEqual(b.returncode,0,b.stdout+'\n'+b.stderr)
        report=list((self.repo/'experiments/campaigns/demo').rglob('validation.csv'))
        self.assertEqual(len(report),1)
        with report[0].open(newline='') as fh:
            self.assertEqual(len(list(csv.DictReader(fh))),2)
        self.assertEqual(self.call('--status').returncode,0)
        archive=self.repo/'demo_small_final_results.zip'
        self.assertTrue(archive.is_file())
        with __import__('zipfile').ZipFile(archive) as z:
            self.assertEqual(len(z.namelist()),4)
        self.assertEqual(len(list(self.archive.rglob('manifest.csv'))),1)
        self.assertEqual(len(list((self.repo/'experiments/offline').iterdir())),2)
        second=self.call('--run')
        self.assertEqual(second.returncode,0,second.stdout+'\n'+second.stderr)
        self.assertEqual(len(list((self.repo/'experiments/offline').iterdir())),2)

    def test_config_change_blocks_run(self):
        b=self.call('--validate')
        self.assertEqual(b.returncode,0,b.stdout+'\n'+b.stderr)
        (self.repo/'src/main/resources/Mock.mch').write_text('MACHINE Changed END')
        blocked=self.call('--run')
        self.assertNotEqual(blocked.returncode,0)
        self.assertIn('changed',blocked.stderr)

    def test_invalid_final_blocks_cleanup(self):
        self.assertEqual(self.call('--all').returncode,0)
        folders=list((self.repo/'experiments/offline').iterdir())
        f=folders[0]/'results.csv'
        with f.open(newline='') as fh:
            rows=list(csv.DictReader(fh))
        rows[0]['validationState']='INVALID'
        with f.open('w',newline='') as out:
            w=csv.DictWriter(out,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
        result=self.call('--run')
        self.assertNotEqual(result.returncode,0)
        self.assertIn('invalid final campaigns',result.stderr.lower())


if __name__ == '__main__':
    unittest.main()
