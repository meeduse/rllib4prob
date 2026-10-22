#!/usr/bin/env python3
"""Reparse independent log files into complete per-seed CSV; Python stdlib only."""
from __future__ import annotations
import argparse
import csv
import math
import re
import statistics
from pathlib import Path

FIELDS = [
    'seed', 'status', 'processExitCode', 'gamma', 'theta', 'maxIterations',
    'updatesPerIteration', 'iterations', 'exploredStates', 'distinctUpdatedStates',
    'updateBackups', 'residualBackups', 'totalBackups', 'rewardLookups',
    'bellmanResidual', 'orderingMethod', 'orderingSeconds', 'explorationSeconds',
    'learningSeconds', 'totalSeconds', 'cacheHits', 'cacheMisses', 'cachedStates',
    'logFile', 'issue'
]

def kv(line):
    return dict(re.findall(r'([A-Za-z][A-Za-z0-9]*)=([^\s]+)', line))

def get_single(lines, prefix):
    matches = [s for s in lines if s.startswith(prefix)]
    return matches[-1] if len(matches) == 1 else None

def parse(path, seed):
    row = {c: '' for c in FIELDS}
    row['seed'] = seed
    row['logFile'] = f'logs/{path.name}'
    if not path.exists():
        row['status'] = 'MISSING_RUN'
        row['issue'] = 'log absent'
        return row
    lines = path.read_text(errors='replace').splitlines()
    result = get_single(lines,'IVI_RESULT ')
    ordering = get_single(lines,'IVI_ORDERING ')
    cache = get_single(lines,'REWARD_CACHE ')
    exits = [s for s in lines if s.startswith('RUN_EXIT_CODE=')]
    row['processExitCode'] = exits[-1].split('=',1)[1] if len(exits) == 1 else ''
    if ordering:
        k = kv(ordering)
        row['orderingMethod'] = k.get('method','')
        row['orderingSeconds'] = k.get('orderingSeconds','')
    if cache:
        k = kv(cache)
        for original, col in [('hits','cacheHits'),('misses','cacheMisses'),('cachedStates','cachedStates')]:
            row[col] = k.get(original, '')
    ex = [s for s in lines if 'End of exploration ' in s and 'Exploration time:' in s]
    if len(ex) == 1:
        m = re.search(r'Exploration time:\s*([0-9.]+)\s*seconds', ex[0])
        if m:
            row['explorationSeconds'] = m.group(1)
    if result:
        k=kv(result)
        for field in ['status','gamma','theta','maxIterations','updatesPerIteration','iterations',
                      'exploredStates','distinctUpdatedStates','updateBackups','residualBackups',
                      'totalBackups','rewardLookups','bellmanResidual','learningSeconds']:
            row[field] = k.get(field,'')
        result_seed = k.get('seed')
    else:
        result_seed = None
        row['status'] = 'NO_IVI_RESULT'
    if row['explorationSeconds'] and row['learningSeconds']:
        row['totalSeconds'] = f"{float(row['explorationSeconds']) + float(row['learningSeconds']):.6f}"
    issues=[]
    if result_seed != str(seed):
        issues.append(f"seed incorrecte (recue: {result_seed})")
    if row['processExitCode'] != '0':
        issues.append('exit code non nul/absent')
    if row['orderingMethod'] != 'BFS_ACTIONS':
        issues.append('mode ordonnancement incorrect/absent')
    if not result:
        issues.append('IVI_RESULT absent ou multiple')
    if not row['explorationSeconds']:
        issues.append('exploration absente')
    try:
        if row['gamma'] and abs(float(row['gamma']) - 0.9) > 1e-12:
            issues.append('gamma different de 0.9')
        if row['theta'] and abs(float(row['theta']) - 0.01) > 1e-12:
            issues.append('theta different de 0.01')
        if row['maxIterations'] != '100' or row['updatesPerIteration'] != '500':
            issues.append('limites IVI differentes du protocole')
        if row['exploredStates'] != '5478':
            issues.append('nombre etats different du protocole')
        if int(row['updateBackups']) + int(row['residualBackups']) != int(row['totalBackups']):
            issues.append('compteurs backups incoherents')
        if not cache:
            issues.append('REWARD_CACHE absent')
        elif int(row['cacheHits']) + int(row['cacheMisses']) != int(row['rewardLookups']):
            issues.append('compteurs cache/recompenses incoherents')
    except (ValueError,TypeError):
        issues.append('parametres ou compteurs manquants/invalides')
    if row['status'] == 'CONVERGED':
        try:
            residual=float(row['bellmanResidual'])
            theta=float(row['theta'])
            if not (math.isfinite(residual) and math.isfinite(theta) and 0 <= residual <= theta):
                issues.append('residu non conforme a theta')
        except (ValueError,TypeError):
            issues.append('residu/theta absent ou non numerique')
    elif row['status'] in ('ITERATION_LIMIT_REACHED','NO_STATES'):
        issues.append('non convergence')
    else:
        issues.append('convergence non etablie')
    row['issue']='; '.join(issues)
    return row

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('directory', type=Path)
    ap.add_argument('--quiet',action='store_true')
    args=ap.parse_args()
    d=args.directory
    rows=[parse(d/'logs'/f'seed_{i}.log',i) for i in range(10)]
    with (d/'results.csv').open('w',newline='') as f:
        writer=csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    if args.quiet:
        return
    print('\nSEED  STATUS                      ITERS  BACKUPS   RESIDU     TOTAL(s)  ISSUE')
    for r in rows:
        print(f"{r['seed']:>4}  {r['status'][:27]:<27} {str(r['iterations']):>5}  "
              f"{str(r['totalBackups']):>7}  {str(r['bellmanResidual']):>9}  "
              f"{str(r['totalSeconds']):>8}  {r['issue'] or '-'}")
    valid=[r for r in rows if r['status']=='CONVERGED' and not r['issue']]
    failed=[r for r in rows if r not in valid]
    print(f"\nRESULTATS : {len(valid)}/10 executions CONVERGED avec residu confirme et logs coherents ; {len(failed)} autres")
    if valid:
        print('MESURES DES RUNS CONVERGES UNIQUEMENT :')
        for field in ['explorationSeconds','orderingSeconds','learningSeconds','totalSeconds','totalBackups','iterations']:
            values=[float(r[field]) for r in valid if r[field]]
            if len(values) == len(valid):
                mean=statistics.mean(values)
                sd=statistics.stdev(values) if len(values)>1 else float('nan')
                median=statistics.median(values)
                print(f"  {field}: moyenne={mean:.6f} ecart_type_echantillon={sd:.6f} "
                      f"mediane={median:.6f} min={min(values):.6f} max={max(values):.6f}")
    print('CSV :',d/'results.csv')
    print('Journaux :',d/'logs')

if __name__=='__main__':
    main()
