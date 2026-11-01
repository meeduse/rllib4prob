#!/usr/bin/env python3
"""Reproducible TicTacToe campaign orchestrator; reuses existing run_campaign.sh.

Does not change Java, B models, or original results. Does not count a budget stop
as success. Default intentionally skips IVI (already 60 validated runs).
"""
import argparse
import json
import subprocess
from pathlib import Path

ALGOS = {'VI': 'VALUE_ITERATION', 'PI': 'POLICY_ITERATION',
         'MPI': 'MODIFIED_POLICY_ITERATION', 'PVI': 'PRIORITIZED_VALUE_ITERATION',
         'BI': 'BACKWARD_INDUCTION'}
REWARDS = ('ONCEANDFORALL', 'EMBEDDED', 'ONTHEFLY')
EXPLORATIONS = ('PREPROCESS', 'RECURSIVE')

def root_dir():
    return Path(__file__).resolve().parents[3]

def matches(camp, algo, reward, exploration):
    protocol_file = camp / 'protocol.json'
    summary_file = camp / 'summary.json'
    if not protocol_file.is_file() or not summary_file.is_file():
        return None
    try:
        p = json.loads(protocol_file.read_text(encoding='utf-8'))
        s = json.loads(summary_file.read_text(encoding='utf-8'))
        if (p.get('algorithm'), p.get('rewardStrategy'), p.get('explorationStrategy')) != (algo, reward, exploration):
            return None
        expected_environment = 'tictacembedded' if reward == 'EMBEDDED' else 'tictactoe'
        if p.get('environment', '').lower() != expected_environment or p.get('expectedStates') != 5478:
            return None
        return s
    except (OSError, ValueError, TypeError):
        return None

def full_success(camp, algo, reward, exploration, minimum):
    s = matches(camp, algo, reward, exploration)
    return (s is not None and s.get('totalRuns', -1) >= minimum and
            s.get('successfulRuns', -1) == s.get('totalRuns') and
            s.get('validationStates') == {'VERIFIED_SUCCESS': s['totalRuns']})

def found_successful(root, algo, reward, exploration, minimum):
    folder = root / 'experiments' / 'offline'
    return any(full_success(c, algo, reward, exploration, minimum)
               for c in folder.iterdir() if c.is_dir()) if folder.exists() else False

def main():
    ap = argparse.ArgumentParser(description='TicTacToe matrix for five non-IVI offline algorithms')
    ap.add_argument('--stage', choices=['pilot', 'full'], required=True,
                    help='pilot: one run per cell; full: ten independent JVM runs per cell')
    ap.add_argument('--algorithms', nargs='+', choices=ALGOS, default=list(ALGOS))
    ap.add_argument('--rewards', nargs='+', choices=REWARDS, default=list(REWARDS))
    ap.add_argument('--explorations', nargs='+', choices=EXPLORATIONS, default=list(EXPLORATIONS))
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--allow-without-pilot', action='store_true',
                    help='full only: bypass the pilot success prerequisite')
    args = ap.parse_args()
    root = root_dir()
    runner = root / 'scripts/experiments/offline/run_campaign.sh'
    if not runner.is_file():
        ap.error(f'Missing existing launcher: {runner}')
    requested = 1 if args.stage == 'pilot' else 10
    commands = []
    for reward in dict.fromkeys(args.rewards):
        for exploration in dict.fromkeys(args.explorations):
            for name in dict.fromkeys(args.algorithms):
                algo = ALGOS[name]
                if found_successful(root, algo, reward, exploration, requested):
                    print(f'SKIP validated {requested}-run campaign: {name} {reward} {exploration}', flush=True)
                    continue
                if args.stage == 'full' and not args.allow_without_pilot and not found_successful(root, algo, reward, exploration, 1):
                    print(f'BLOCK no VERIFIED_SUCCESS pilot: {name} {reward} {exploration}', flush=True)
                    return 2
                cmd = ['bash', str(runner), '--algorithm', name, '--reward', reward,
                       '--exploration', exploration, '--environment', 'tictactoe',
                       '--count', str(requested), '--expected-states', '5478']
                commands.append((name, reward, exploration, cmd))
    print(f'Stage {args.stage}: {len(commands)} campaign(s) planned', flush=True)
    if args.dry_run:
        for name, reward, explore, _ in commands:
            print(f'PLAN {name} {reward} {explore} count={requested}', flush=True)
        return 0
    for i, (name, reward, exploration, cmd) in enumerate(commands, 1):
        print(f'\n[{i}/{len(commands)}] {name} {reward} {exploration}, count={requested}', flush=True)
        done = subprocess.run(cmd, cwd=root, check=False)
        if done.returncode != 0 or not found_successful(root, ALGOS[name], reward, exploration, requested):
            print(f'STOP {name} {reward} {exploration}: failed or did not verify success; inspect campaign logs.', flush=True)
            return 1
    print(f'COMPLETED {args.stage}: {len(commands)} new campaign(s). Raw data kept under experiments/offline/.', flush=True)
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
