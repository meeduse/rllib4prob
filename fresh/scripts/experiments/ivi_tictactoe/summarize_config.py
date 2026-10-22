#!/usr/bin/env python3
"""Summarize an IVI run_config.sh output folder. No external Python packages."""
import argparse
import csv
import math
import re
import statistics
from pathlib import Path

FIELDS=['seed','rewardStrategy','explorationStrategy','status','processExitCode','gamma',
 'theta','maxIterations','updatesPerIteration','iterations','exploredStates',
 'distinctUpdatedStates','updateBackups','residualBackups','totalBackups',
 'rewardLookups','bellmanResidual','orderingMethod','orderingSeconds',
 'explorationSeconds','learningSeconds','totalSeconds','cacheHits','cacheMisses',
 'cachedStates','logFile','issue']

def read_protocol(folder):
    result={}
    for line in (folder/'protocol.txt').read_text().splitlines():
        if '=' in line:
            k,v=line.split('=',1)
            result[k]=v
    return result

def kv(s):
    return dict(re.findall(r'([A-Za-z][A-Za-z0-9]*)=([^\s]+)',s))

def singleton(lines,prefix):
    found=[s for s in lines if s.startswith(prefix)]
    return found[0] if len(found)==1 else None

def parse(folder, seed, proto):
    r={k:'' for k in FIELDS}; r['seed']=seed
    r['rewardStrategy']=proto['reward_strategy']; r['explorationStrategy']=proto['exploration']
    p=folder/'logs'/f'seed_{seed}.log'; r['logFile']=f'logs/{p.name}'
    if not p.exists():
        r['status']='MISSING_RUN'; r['issue']='log absent'; return r
    lines=p.read_text(errors='replace').splitlines()
    result=singleton(lines,'IVI_RESULT ')
    order=singleton(lines,'IVI_ORDERING ')
    cache=singleton(lines,'REWARD_CACHE ')
    exits=[s for s in lines if s.startswith('RUN_EXIT_CODE=')]
    r['processExitCode']=exits[0].split('=',1)[1] if len(exits)==1 else ''
    k=kv(result) if result else {}
    for field in ['status','gamma','theta','maxIterations','updatesPerIteration','iterations',
         'exploredStates','distinctUpdatedStates','updateBackups','residualBackups',
         'totalBackups','rewardLookups','bellmanResidual','learningSeconds']:
        r[field]=k.get(field,'')
    if not result: r['status']='NO_IVI_RESULT'
    if order:
        ok=kv(order); r['orderingMethod']=ok.get('method','')
        r['orderingSeconds']=ok.get('orderingSeconds','')
    if cache:
        ck=kv(cache)
        for col,key in [('cacheHits','hits'),('cacheMisses','misses'),('cachedStates','cachedStates')]:
            r[col]=ck.get(key,'')
    ex=[s for s in lines if 'End of exploration ' in s and 'Exploration time:' in s]
    if len(ex)==1:
        m=re.search(r'Exploration time:\s*([0-9.]+)\s*seconds',ex[0])
        if m: r['explorationSeconds']=m.group(1)
    issues=[]
    if not result: issues.append('IVI_RESULT absent ou multiple')
    if len(ex)!=1 or not r['explorationSeconds']: issues.append('exploration absente/multiple')
    if not order: issues.append('IVI_ORDERING absent ou multiple')
    if r['orderingMethod']!='BFS_ACTIONS': issues.append('methode ordonnancement incorrecte')
    if r['processExitCode']!='0': issues.append('code sortie non nul/absent')
    if k.get('seed')!=str(seed): issues.append('graine du resultat differente')
    try:
        if not math.isclose(float(r['gamma']),float(proto['expected_gamma']),rel_tol=0,abs_tol=1e-10):
            issues.append('gamma incorrect')
        if not math.isclose(float(r['theta']),float(proto['expected_theta']),rel_tol=0,abs_tol=1e-10):
            issues.append('theta incorrect')
        if int(r['maxIterations'])!=int(proto['expected_max_iterations']): issues.append('maxIterations incorrect')
        if int(r['updatesPerIteration'])!=int(proto['expected_updates_per_iteration']): issues.append('updatesPerIteration incorrect')
        if int(r['exploredStates'])!=int(proto['expected_states']): issues.append('etats incorrects')
        if int(r['updateBackups'])+int(r['residualBackups'])!=int(r['totalBackups']): issues.append('backups incoherents')
        if int(r['iterations']) > int(r['maxIterations']): issues.append('iterations > limite')
        if not (math.isfinite(float(r['learningSeconds'])) and float(r['learningSeconds'])>=0):
            issues.append('temps apprentissage incorrect')
        if not (math.isfinite(float(r['orderingSeconds'])) and float(r['orderingSeconds'])>=0):
            issues.append('temps ordre incorrect')
        r['totalSeconds']=f"{float(r['explorationSeconds'])+float(r['learningSeconds']):.6f}"
        if r['rewardStrategy']=='ONCEANDFORALL':
            if cache is None:
                issues.append('cache manquant')
            elif int(r['cacheHits'])+int(r['cacheMisses'])!=int(r['rewardLookups']):
                issues.append('cache/lookups incoherents')
        if r['status']=='CONVERGED':
            res=float(r['bellmanResidual']); theta=float(r['theta'])
            if not (math.isfinite(res) and 0<=res<=theta): issues.append('residu invalide ou > theta')
        else: issues.append('NON_CONVERGED')
    except (ValueError,KeyError,TypeError,OverflowError):
        issues.append('champs parametres/metriques manquants')
    r['issue']='; '.join(issues)
    return r

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('output',type=Path)
    ap.add_argument('--quiet',action='store_true'); args=ap.parse_args()
    d=args.output; proto=read_protocol(d)
    seeds=range(int(proto['first_seed']), int(proto['first_seed'])+int(proto['run_count']))
    rows=[parse(d,seed,proto) for seed in seeds]
    with (d/'results.csv').open('w',newline='') as fh:
        writer=csv.DictWriter(fh,fieldnames=FIELDS); writer.writeheader(); writer.writerows(rows)
    if args.quiet: return
    print('\nSEED  STATUS                   ITERS  BACKUPS   RESIDUAL     TOTAL(s)   ISSUE')
    for r in rows:
        print(f"{r['seed']:>4}  {r['status']:<23} {str(r['iterations']):>5}  {str(r['totalBackups']):>7}  {str(r['bellmanResidual']):>10}  {str(r['totalSeconds']):>9}  {r['issue'] or '-'}")
    ok=[r for r in rows if r['status']=='CONVERGED' and not r['issue']]
    print(f"\nRESULTATS: {len(ok)}/{len(rows)} RUNS CONVERGED AVEC RESIDU VERIFIE; {len(rows)-len(ok)} autres")
    if ok:
        for field in ['explorationSeconds','orderingSeconds','learningSeconds','totalSeconds','totalBackups','iterations']:
            values=[float(r[field]) for r in ok]
            print(f"{field}: mean={statistics.mean(values):.6f} sd={statistics.stdev(values) if len(values)>1 else float('nan'):.6f} median={statistics.median(values):.6f} min={min(values):.6f} max={max(values):.6f}")
    print('CSV:',d/'results.csv')

if __name__=='__main__': main()
