#!/usr/bin/env python3
"""Pair converged IVI seeds across two CSVs, report backups and total time."""
import csv,sys,statistics
from pathlib import Path

def load(path):
    with Path(path).open(newline='') as f: rows=list(csv.DictReader(f))
    return {int(r['seed']):r for r in rows}

def valid(row):
    return row and row.get('status')=='CONVERGED' and not row.get('issue') and row.get('processExitCode')=='0'

if __name__=='__main__':
    if len(sys.argv)!=3:
        sys.exit('Usage: python3 compare_campaigns.py path/to/reference/results.csv path/to/new/results.csv')
    a=load(sys.argv[1]); b=load(sys.argv[2]); shared=sorted(set(a)&set(b))
    pairs=[(seed,a[seed],b[seed]) for seed in shared if valid(a[seed]) and valid(b[seed])]
    print('Compared:',sys.argv[1],'vs',sys.argv[2])
    print(f'Paired valid seeds: {len(pairs)} / {len(shared)} shared')
    print('seed  reference_total_s new_total_s  ref_backups new_backups')
    for seed,x,y in pairs:
        print(f"{seed:4d} {float(x['totalSeconds']):18.3f} {float(y['totalSeconds']):11.3f} {int(x['totalBackups']):12d} {int(y['totalBackups']):11d}")
    if len(pairs)<2:
        sys.exit('Not enough valid paired runs for a dispersion estimate')
    ds=[float(y['totalSeconds'])-float(x['totalSeconds']) for _,x,y in pairs]
    ratios=[float(y['totalSeconds'])/float(x['totalSeconds']) for _,x,y in pairs]
    eq=sum(int(x['totalBackups'])==int(y['totalBackups']) for _,x,y in pairs)
    print(f'Mean paired delta (new - reference) totalSeconds: {statistics.mean(ds):.6f}; sample SD: {statistics.stdev(ds):.6f}')
    print(f'Mean individual runtime ratio new/reference: {statistics.mean(ratios):.6f}')
    print(f'Equal backup counts on matched seeds: {eq}/{len(pairs)}')
    print('Note: timings are observational; not a significance test; count nonconverged runs separately.')
