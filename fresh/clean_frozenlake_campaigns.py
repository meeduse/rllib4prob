#!/usr/bin/env python3
"""Move Frozen Lake single-run validation campaigns out of the public experiments tree.

Usage from fresh/:
    python3 ~/Downloads/clean_frozenlake_campaigns.py
    python3 ~/Downloads/clean_frozenlake_campaigns.py --apply

Safe defaults: preview only; do not delete results; final ten-run campaigns
(including failed runs, if any) are never moved.
"""
import argparse
import csv
import json
import os
import shutil
from datetime import datetime
from pathlib import Path

ALGORITHMS = {
    'VALUE_ITERATION', 'POLICY_ITERATION', 'MODIFIED_POLICY_ITERATION',
    'INCREMENTAL_VALUE_ITERATION', 'PRIORITIZED_VALUE_ITERATION',
    'BACKWARD_INDUCTION'
}
REWARDS = {'ONCEANDFORALL', 'EMBEDDED'}


def inside(path, parent):
    return os.path.commonpath((str(path.resolve()), str(parent.resolve()))) == str(parent.resolve())


def read_csv(path):
    with path.open(newline='', encoding='utf-8-sig') as f:
        return list(csv.DictReader(f))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', type=Path, default=Path.cwd(), help='Path to fresh/ (default: current directory)')
    p.add_argument('--archive', type=Path, default=None, help='Private directory OUTSIDE fresh/ (default: ~/rllib4prob-private-archive)')
    p.add_argument('--apply', action='store_true', help='Actually move one-run pilot campaigns')
    args = p.parse_args()
    repo = args.repo.resolve()
    src = repo / 'experiments' / 'offline'
    archive_root = (args.archive or (Path.home() / 'rllib4prob-private-archive')).expanduser().resolve()
    if not src.is_dir():
        raise SystemExit(f'Error: directory not found: {src}')
    if inside(archive_root, repo):
        raise SystemExit(f'Error: private archive MUST be outside the Git project: {archive_root}')

    pilots = []
    finals = []
    unclassified = []
    for folder in sorted(src.iterdir()):
        if not folder.is_dir() or folder.is_symlink() or 'frozenlake' not in folder.name.lower():
            continue
        sf = folder / 'summary.json'
        cf = folder / 'results.csv'
        if not sf.is_file() or not cf.is_file():
            unclassified.append((folder, 'missing results.csv or summary.json'))
            continue
        try:
            summary = json.loads(sf.read_text(encoding='utf-8'))
            rows = read_csv(cf)
            n = int(summary['totalRuns'])
        except (ValueError, KeyError, OSError, json.JSONDecodeError) as e:
            unclassified.append((folder, f'could not parse: {e}'))
            continue
        if (summary.get('environment') not in ('frozenlake', 'frozenlakeembedded')
                or summary.get('explorationStrategy') not in ('PREPROCESS', 'RECURSIVE')
                or summary.get('algorithm') not in ALGORITHMS
                or summary.get('rewardStrategy') not in REWARDS):
            unclassified.append((folder, 'unknown configuration; leave untouched'))
            continue
        if len(rows) != n:
            unclassified.append((folder, f'CSV rows={len(rows)}, summary totalRuns={n}; leave untouched'))
            continue
        if n == 1:
            pilots.append((folder, summary, rows))
        elif n == 10:
            finals.append((folder, summary, rows))
        else:
            unclassified.append((folder, f'{n} runs; leave untouched'))

    pre = [(f, s, r) for f, s, r in finals if s['explorationStrategy'] == 'PREPROCESS']
    seen = {(s['algorithm'], s['rewardStrategy']) for _, s, _ in pre}
    required = {(a, reward) for a in ALGORITHMS for reward in REWARDS}
    complete = (
        len(pre) == 12 and seen == required
        and all(s.get('successfulRuns') == 10
                and s.get('validationStates') == {'VERIFIED_SUCCESS': 10}
                and all(row.get('validationState') == 'VERIFIED_SUCCESS'
                        and row.get('exploredStates') == '43' for row in rows)
                for _, s, rows in pre)
    )
    print(f'Frozen Lake: {len(finals)} 10-run campaigns kept, {len(pilots)} 1-run pilots to archive, {len(unclassified)} left for manual review.')
    print(f'PREPROCESS: {len(pre)}/12 ten-run campaigns; all 120 runs verified: {complete}')
    for folder, s, rows in pilots:
        print(f'  ARCHIVE [{s["explorationStrategy"]:10}] {folder.name} ({rows[0].get("validationState", "?")})')
    for folder, reason in unclassified:
        print(f'  KEEP / CHECK: {folder.name} — {reason}')
    if not complete:
        raise SystemExit('ABORT: PREPROCESS final campaign is missing/incomplete. Nothing moved.')
    if not pilots:
        print('Nothing to archive.')
        return
    if not args.apply:
        print('\nPREVIEW ONLY. Run again with --apply to move these pilot folders.')
        return

    dest = archive_root / ('frozenlake-pilots-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    if dest.exists():
        raise SystemExit(f'ABORT: destination already exists: {dest}')
    dest.mkdir(parents=True)
    moved = []
    try:
        for folder, s, rows in pilots:
            target = dest / folder.name
            if target.exists():
                raise FileExistsError(target)
            shutil.move(str(folder), str(target))
            moved.append((folder.name, s['algorithm'], s['rewardStrategy'], s['explorationStrategy'], rows[0].get('validationState', '')))
        with (dest / 'pilot_manifest.csv').open('w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['campaign', 'algorithm', 'rewardStrategy', 'explorationStrategy', 'validationState'])
            w.writerows(moved)
    except Exception:
        print(f'Error during move. Already moved folders can be recovered from {dest}')
        raise
    print(f'\nMOVED {len(moved)} pilot campaigns into private archive: {dest}')
    print(f'KEPT {len(finals)} final Frozen Lake campaigns in {src}')
    print('Note: no failed results from ten-run campaigns were removed.')


if __name__ == '__main__':
    main()
