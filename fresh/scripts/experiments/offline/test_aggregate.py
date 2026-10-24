#!/usr/bin/env python3
"""Synthetic regression checks; no ProB or Maven invocation."""
import csv
import subprocess
import sys
import tempfile
from pathlib import Path

THIS = Path(__file__).resolve().parent

OLD = ['seed', 'status', 'processExitCode', 'gamma', 'theta', 'maxIterations',
       'updatesPerIteration', 'iterations', 'exploredStates', 'distinctUpdatedStates',
       'updateBackups', 'residualBackups', 'totalBackups', 'rewardLookups',
       'bellmanResidual', 'orderingMethod', 'orderingSeconds', 'explorationSeconds',
       'learningSeconds', 'totalSeconds', 'cacheHits', 'cacheMisses', 'cachedStates',
       'logFile', 'issue']


def write_csv(path, cols, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=cols)
        writer.writeheader()
        writer.writerows(rows)


def make_old(root, name, bad_protocol=False, invalid_residual=False):
    path = root / 'experiments' / name
    path.mkdir(parents=True, exist_ok=True)
    proto = ('algorithm=INCREMENTAL_VALUE_ITERATION\n'
             'reward_strategy=ONCEANDFORALL\n'
             'exploration=PREPROCESS\n'
             'environment=tictactoe\n'
             'ordering=BFS_ACTIONS\n')
    if bad_protocol:
        proto = proto.replace('exploration=PREPROCESS', 'exploration=UNKNOWN')
    (path / 'protocol.txt').write_text(proto)
    rows = []
    for i in range(10):
        r = {key: '' for key in OLD}
        r.update(seed=str(i), status='CONVERGED', processExitCode='0',
                 theta='.01', bellmanResidual='0', iterations='33',
                 gamma='.9', exploredStates='5478', totalBackups='38412',
                 rewardLookups='113365', orderingMethod='BFS_ACTIONS',
                 explorationSeconds='4.8', learningSeconds='42.0',
                 totalSeconds='46.8', logFile=f'logs/seed_{i}.log')
        if invalid_residual and i == 0:
            r['bellmanResidual'] = '0.2'
        (path / r['logFile']).parent.mkdir(parents=True, exist_ok=True)
        (path / r['logFile']).write_text('test log\n')
        rows.append(r)
    write_csv(path / 'results.csv', OLD, rows)


def make_legacy_v1(root, name):
    path = root / 'experiments' / name
    path.mkdir(parents=True, exist_ok=True)
    cols = OLD + ['rewardStrategy', 'explorationStrategy']
    rows=[]
    for i in range(10):
        r = {col: '' for col in cols}
        r.update(seed=str(i), status='CONVERGED', processExitCode='0',
                 theta='.01', bellmanResidual='0', iterations='44',
                 gamma='.9', exploredStates='5478', totalBackups='49390',
                 rewardLookups='140000', orderingMethod='BFS_ACTIONS',
                 rewardStrategy='EMBEDDED', explorationStrategy='RECURSIVE',
                 explorationSeconds='50', learningSeconds='12', totalSeconds='62',
                 logFile=f'logs/seed_{i}.log')
        (path / r['logFile']).parent.mkdir(parents=True, exist_ok=True)
        (path / r['logFile']).write_text('test log\n')
        rows.append(r)
    write_csv(path / 'results.csv', cols, rows)


def make_new(root, name):
    path = root / 'experiments' / name
    r = dict(schemaVersion='offline-common-v1', validationState='VERIFIED_SUCCESS',
             algorithm='VALUE_ITERATION', environment='tictactoe',
             rewardStrategy='ONCEANDFORALL', explorationStrategy='PREPROCESS',
             run='0', status='CONVERGED', exploredStates='5478',
             iterations='5', totalBackups='32868', bellmanResidual='0',
             explorationSeconds='5', learningSeconds='45', totalSeconds='50')
    write_csv(path / 'results.csv', list(r), [r])


def load_csv(path):
    with path.open(newline='') as stream:
        return list(csv.DictReader(stream))


def main():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        make_old(root, 'ivi_first')
        make_old(root, 'ivi_repeat')
        make_legacy_v1(root, 'ivi_embedded')
        make_new(root, 'new_vi')
        output = subprocess.run([sys.executable, str(THIS / 'aggregate.py'),
                                 '--project', str(root)], capture_output=True, text=True)
        assert output.returncode == 0, output.stdout + output.stderr
        rows = load_csv(root / 'analysis/results_all.csv')
        assert len(rows) == 31, len(rows)
        assert {r['sourceSchema'] for r in rows} == {'ivi-legacy-v0', 'ivi-legacy-v1', 'offline-common-v1'}
        assert all(r['validationState']=='VERIFIED_SUCCESS' for r in rows)
        agg = load_csv(root / 'analysis/summary_by_configuration.csv')
        once = [r for r in agg if r['algorithm']=='INCREMENTAL_VALUE_ITERATION' and r['rewardStrategy']=='ONCEANDFORALL'][0]
        assert once['totalRuns']=='20' and once['distinctSuccessfulSeeds']=='10' and once['campaignCount']=='2', once
        assert once['reportedStatuses']=='CONVERGED:20', once
        # Introduce an invalid residual in the original source: it must NOT pass.
        make_old(root, 'ivi_repeat', invalid_residual=True)
        output = subprocess.run([sys.executable, str(THIS / 'aggregate.py'), '--project',str(root)],capture_output=True,text=True)
        assert output.returncode == 0, output.stdout + output.stderr
        rows = load_csv(root / 'analysis/results_all.csv')
        bad = [r for r in rows if r['campaignId']=='ivi_repeat' and r['seed']=='0'][0]
        assert bad['validationState']=='INVALID' and 'residual > theta' in bad['issue'],bad
        # Historical protocol must be matched, never inferred from filenames.
        make_old(root, 'ivi_repeat', bad_protocol=True)
        output = subprocess.run([sys.executable, str(THIS / 'aggregate.py'), '--project',str(root)],capture_output=True,text=True)
        assert output.returncode == 0, output.stdout + output.stderr
        rows = load_csv(root / 'analysis/results_all.csv')
        bad = [r for r in rows if r['campaignId']=='ivi_repeat' and r['seed']=='1'][0]
        assert bad['validationState']=='INVALID' and 'protocol exploration' in bad['issue'],bad
        # An unsupported CSV must be detected and yield a nonzero exit code.
        write_csv(root/'experiments'/'unknown'/'results.csv',['other'],[{'other':'x'}])
        output = subprocess.run([sys.executable, str(THIS / 'aggregate.py'), '--project',str(root)],capture_output=True,text=True)
        assert output.returncode == 1 and 'WARNING' in output.stdout, output.stdout
    print('AGGREGATE_TEST PASS: old v0, old v1, common v1, duplicates, bad residual, protocol, unsupported file')


if __name__ == '__main__':
    main()
