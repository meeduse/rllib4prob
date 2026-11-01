#!/usr/bin/env python3
"""Parse offline-algorithm logs into auditable per-run CSV/JSON; stdlib only.

Do not identify reaching a budget with convergence. Different algorithms
have different successful terminal statuses (BI is finite-horizon DP).
"""
import argparse
import csv
import json
import math
import re
import statistics
from pathlib import Path

PREFIX = {
    'VALUE_ITERATION': 'VI_RESULT ',
    'POLICY_ITERATION': 'PI_RESULT ',
    'MODIFIED_POLICY_ITERATION': 'MPI_RESULT ',
    'INCREMENTAL_VALUE_ITERATION': 'IVI_RESULT ',
    'PRIORITIZED_VALUE_ITERATION': 'PVI_RESULT ',
    'BACKWARD_INDUCTION': 'BI_RESULT ',
}
COLUMNS = [
    'schemaVersion','campaignId','run','seed','algorithm','environment',
    'rewardStrategy','explorationStrategy','status','validationState','issue',
    'processExitCode','exploredStates','explorationSeconds','learningSeconds',
    'totalSeconds','gamma','theta','iterations','maxIterations','maxUpdates',
    'updates','horizon','completedHorizon','evalIterations','evaluationSweeps',
    'maxEvaluationSweeps','updatesPerIteration','policyStable',
    'bellmanResidual','policyResidual','evaluationDelta','lastHorizonDelta',
    'updateBackups','evaluationBackups','improvementBackups','priorityBackups',
    'residualBackups','finalQBackups','totalBackups','rewardLookups',
    'orderingMethod','orderingSeconds','cacheHits','cacheMisses','cachedStates',
    'logFile'
]
COPY_FIELDS = [c for c in COLUMNS if c not in {
    'schemaVersion','campaignId','run','seed','algorithm','environment',
    'rewardStrategy','explorationStrategy','validationState','issue',
    'processExitCode','explorationSeconds','totalSeconds','orderingMethod',
    'orderingSeconds','cacheHits','cacheMisses','cachedStates','logFile'
}]
RE_KV = re.compile(r'([A-Za-z][A-Za-z0-9]*)=([^\s]+)')
RE_EXPLORATION = re.compile(r'End of exploration\s+(\d+)\s*\|\s*Exploration time:\s*([0-9.eE+-]+)\s*seconds')


def kv(text):
    return dict(RE_KV.findall(text))


def singular(lines, prefix):
    matches = [line for line in lines if line.startswith(prefix)]
    return matches[0] if len(matches) == 1 else None


def finite_float(value, name, issues, lower=0):
    try:
        number = float(value)
        if not math.isfinite(number) or (lower is not None and number < lower):
            raise ValueError()
        return number
    except (ValueError, TypeError, OverflowError):
        issues.append(f'{name} missing/invalid')
        return None


def nonnegative_int(value, name, issues):
    try:
        n = int(value)
        if n < 0:
            raise ValueError()
        return n
    except (ValueError, TypeError, OverflowError):
        issues.append(f'{name} missing/invalid')
        return None


def parse_log(folder, item, protocol):
    algo = protocol['algorithm']
    row = {field: '' for field in COLUMNS}
    row.update(schemaVersion='offline-common-v1', campaignId=folder.name,
               run=item['run'], seed='' if item.get('seed') is None else item['seed'],
               algorithm=algo, environment=protocol['environment'],
               rewardStrategy=protocol['rewardStrategy'],
               explorationStrategy=protocol['explorationStrategy'],
               logFile=item['logFile'])
    p = folder / item['logFile']
    if not p.exists():
        row['validationState'] = 'INCOMPLETE'
        row['issue'] = 'log missing'
        return row
    lines = p.read_text(encoding='utf-8', errors='replace').splitlines()
    issues = []
    record = singular(lines, PREFIX[algo])
    if record is None:
        issues.append('result line absent or duplicated')
    result = kv(record or '')
    for field in COPY_FIELDS:
        row[field] = result.get(field, '')
    if not record:
        row['status'] = 'NO_RESULT'
    exits = [x.partition('=')[2] for x in lines if x.startswith('RUN_EXIT_CODE=')]
    if len(exits) != 1:
        issues.append('exit code absent/duplicated')
    else:
        row['processExitCode'] = exits[0]
        if exits[0] != '0':
            issues.append(f'process exited {exits[0]}')
    explores = [RE_EXPLORATION.search(x) for x in lines]
    explores = [m for m in explores if m]
    if len(explores) != 1:
        issues.append('exploration marker absent/duplicated')
    else:
        row['explorationSeconds'] = explores[0].group(2)
    ordering = singular(lines, 'IVI_ORDERING ')
    if ordering:
        values = kv(ordering)
        row['orderingMethod'] = values.get('method', '')
        row['orderingSeconds'] = values.get('orderingSeconds', '')
    cache = singular(lines, 'REWARD_CACHE ')
    if cache:
        values = kv(cache)
        for outfield, field in (('cacheHits','hits'),('cacheMisses','misses'),('cachedStates','cachedStates')):
            row[outfield] = values.get(field, '')
    n_states = nonnegative_int(result.get('exploredStates'), 'exploredStates', issues)
    actual_explored = int(explores[0].group(1)) if len(explores) == 1 else None
    if n_states is not None and actual_explored is not None and n_states != actual_explored:
        issues.append('state count inconsistent with exploration')
    expected_states = protocol.get('expectedStates')
    if expected_states is not None and n_states is not None and n_states != int(expected_states):
        issues.append('state count differs from expectedStates')
    if n_states is not None and n_states == 0:
        issues.append('empty reachable state set')
    gamma = finite_float(row['gamma'], 'gamma', issues)
    if gamma is not None and gamma >= 1:
        issues.append('gamma outside discounted setting')
    exploration = finite_float(row['explorationSeconds'], 'explorationSeconds', issues)
    learning = finite_float(row['learningSeconds'], 'learningSeconds', issues)
    if exploration is not None and learning is not None:
        row['totalSeconds'] = f'{exploration+learning:.6f}'
    n_backups = nonnegative_int(row['totalBackups'], 'totalBackups', issues)
    nonnegative_int(row['rewardLookups'], 'rewardLookups', issues)
    components = {
        'VALUE_ITERATION':['updateBackups','residualBackups'],
        'INCREMENTAL_VALUE_ITERATION':['updateBackups','residualBackups'],
        'POLICY_ITERATION':['evaluationBackups','improvementBackups','residualBackups'],
        'MODIFIED_POLICY_ITERATION':['evaluationBackups','improvementBackups','residualBackups'],
        'PRIORITIZED_VALUE_ITERATION':['priorityBackups','residualBackups','finalQBackups'],
        'BACKWARD_INDUCTION':['updateBackups'],
    }[algo]
    parts = [nonnegative_int(row[key], key, issues) for key in components]
    if n_backups is not None and all(part is not None for part in parts):
        if n_backups != sum(parts):
            issues.append('backup counters inconsistent')
    if protocol['rewardStrategy'] == 'ONCEANDFORALL':
        if cache is None:
            issues.append('once-and-for-all cache marker missing')
        else:
            hits = nonnegative_int(row['cacheHits'], 'cacheHits', issues)
            misses = nonnegative_int(row['cacheMisses'], 'cacheMisses', issues)
            lookups = nonnegative_int(row['rewardLookups'], 'rewardLookups', issues)
            if hits is not None and misses is not None and lookups is not None and hits+misses!=lookups:
                issues.append('cache hits + misses != reward lookups')
    if algo == 'INCREMENTAL_VALUE_ITERATION':
        if row['orderingMethod'] != protocol.get('ordering', 'BFS_ACTIONS'):
            issues.append('IVI ordering mismatch')
        finite_float(row['orderingSeconds'], 'orderingSeconds', issues)
        if str(result.get('seed', '')) != str(item['seed']):
            issues.append('IVI seed mismatch')
        if (nonnegative_int(row['updatesPerIteration'], 'updatesPerIteration', issues) or 0) <= 0:
            issues.append('updatesPerIteration must be positive')
    status = row['status']
    if algo == 'BACKWARD_INDUCTION':
        h = nonnegative_int(row['horizon'], 'horizon', issues)
        c = nonnegative_int(row['completedHorizon'], 'completedHorizon', issues)
        finite_float(row['lastHorizonDelta'], 'lastHorizonDelta', issues)
        if h is not None and h == 0:
            issues.append('zero horizon')
        if status == 'HORIZON_COMPLETED' and (h is None or c != h):
            issues.append('reported complete but horizon mismatch')
        if status != 'HORIZON_COMPLETED':
            # A genuine budget/other status is preserved, not relabeled as convergence.
            pass
    else:
        theta = finite_float(row['theta'], 'theta', issues)
        resid = finite_float(row['bellmanResidual'], 'bellmanResidual', issues)
        if algo == 'PRIORITIZED_VALUE_ITERATION':
            n = nonnegative_int(row['updates'], 'updates', issues)
            maximum = nonnegative_int(row['maxUpdates'], 'maxUpdates', issues)
            if n is not None and maximum is not None and n > maximum:
                issues.append('updates exceed maxUpdates')
        else:
            n = nonnegative_int(row['iterations'], 'iterations', issues)
            maximum = nonnegative_int(row['maxIterations'], 'maxIterations', issues)
            if n is not None and maximum is not None and n > maximum:
                issues.append('iterations exceed maxIterations')
        if algo in {'POLICY_ITERATION', 'MODIFIED_POLICY_ITERATION'}:
            finite_float(row['policyResidual'], 'policyResidual', issues)
            if status == 'CONVERGED' and row['policyStable'] != 'true':
                issues.append('converged PI/MPI without stable policy')
            if status == 'CONVERGED' and theta is not None:
                policy = finite_float(row['policyResidual'], 'policyResidual', issues)
                if policy is not None and policy > theta + 1e-10:
                    issues.append('policy residual > theta')
        if status == 'CONVERGED':
            if theta is None or resid is None or resid > theta + 1e-10:
                issues.append('CONVERGED without certified Bellman residual <= theta')
    if issues:
        row['validationState'] = 'INVALID'
        row['issue'] = '; '.join(dict.fromkeys(issues))
    elif (status == 'HORIZON_COMPLETED' and algo == 'BACKWARD_INDUCTION') or (status == 'CONVERGED' and algo != 'BACKWARD_INDUCTION'):
        row['validationState'] = 'VERIFIED_SUCCESS'
    else:
        # A budget-limited run is data, not a converged run.
        row['validationState'] = 'VERIFIED_NONCONVERGENCE' if 'LIMIT_REACHED' in status else 'OTHER_TERMINATION'
    return row


def collect(folder):
    protocol=json.loads((folder/'protocol.json').read_text(encoding='utf-8'))
    runs=json.loads((folder/'runs.json').read_text(encoding='utf-8'))
    rows=[parse_log(folder,item,protocol) for item in runs]
    with (folder/'results.csv').open('w',encoding='utf-8',newline='') as file:
        writer=csv.DictWriter(file,fieldnames=COLUMNS)
        writer.writeheader(); writer.writerows(rows)
    by_state={status:sum(r['validationState']==status for r in rows) for status in sorted({r['validationState'] for r in rows})}
    verified=[row for row in rows if row['validationState']=='VERIFIED_SUCCESS']
    metrics={}
    for key in ('explorationSeconds','orderingSeconds','learningSeconds','totalSeconds','totalBackups','iterations','updates'):
        vals=[float(r[key]) for r in verified if r[key]!='']
        if vals:
            metrics[key]={'n':len(vals),'mean':statistics.mean(vals),'sampleSD':statistics.stdev(vals) if len(vals)>1 else None,
                          'median':statistics.median(vals),'min':min(vals),'max':max(vals)}
    summary={'campaignId':folder.name,'algorithm':protocol['algorithm'],
             'environment':protocol['environment'],'rewardStrategy':protocol['rewardStrategy'],
             'explorationStrategy':protocol['explorationStrategy'],'totalRuns':len(rows),
             'validationStates':by_state,'successfulRuns':len(verified),'statisticsOnVerifiedSuccessOnly':metrics}
    (folder/'summary.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return rows, summary


def main():
    parser=argparse.ArgumentParser(description='Summarize one offline campaign without rerunning ProB')
    parser.add_argument('folder',type=Path)
    parser.add_argument('--quiet',action='store_true')
    args=parser.parse_args()
    rows, summary=collect(args.folder.resolve())
    if args.quiet:
        return
    print('\nRUN  SEED  STATUS                      CHECK                   ITERS / UPDATES  TOTAL(s)   ISSUE')
    for r in rows:
        count=r['iterations'] or r['updates'] or r['completedHorizon']
        print(f"{str(r['run']):>3}  {str(r['seed']):>4}  {r['status']:<26}  {r['validationState']:<22} {str(count):>10}  {str(r['totalSeconds']):>9}  {r['issue'] or '-'}")
    print(f"\nVerified success: {summary['successfulRuns']}/{summary['totalRuns']}; breakdown: {summary['validationStates']}")
    for name,stat in summary['statisticsOnVerifiedSuccessOnly'].items():
        std='n/a' if stat['sampleSD'] is None else f"{stat['sampleSD']:.6f}"
        print(f"{name}: mean={stat['mean']:.6f} sample_sd={std} median={stat['median']:.6f} min={stat['min']:.6f} max={stat['max']:.6f}")
    print('CSV:',args.folder/'results.csv')
    print('Summary:',args.folder/'summary.json')


if __name__=='__main__':
    main()
