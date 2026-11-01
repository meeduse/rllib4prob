#!/usr/bin/env python3
"""Aggregate all six offline algorithms and both historical IVI CSV schemas.

Read-only with respect to experiments/. All summaries are regenerated in analysis/.
No skipped IVI results, no implicit convergence, no invented experimental data.
"""
import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
import statistics

FIELDS = [
    'campaignId', 'sourceSchema', 'algorithm', 'environment', 'rewardStrategy',
    'explorationStrategy', 'run', 'seed', 'status', 'validationState', 'issue',
    'exploredStates', 'iterations', 'updates', 'horizon', 'completedHorizon',
    'bellmanResidual', 'policyResidual', 'explorationSeconds', 'orderingSeconds',
    'learningSeconds', 'totalSeconds', 'totalBackups', 'rewardLookups',
    'csvSource', 'logFile'
]
METRICS = ('totalSeconds', 'explorationSeconds', 'learningSeconds',
           'totalBackups', 'iterations', 'updates')
LEGACY_REQUIRED = {'seed', 'status', 'processExitCode', 'theta', 'bellmanResidual',
                   'totalBackups', 'explorationSeconds', 'learningSeconds', 'issue'}
REQUIRED_PROTOCOL_OLD = {
    'algorithm': 'INCREMENTAL_VALUE_ITERATION',
    'reward_strategy': 'ONCEANDFORALL',
    'exploration': 'PREPROCESS',
    'environment': 'tictactoe',
    'ordering': 'BFS_ACTIONS',
}


def protocol_properties(path):
    """Legacy experiments record protocol as key=value text, not JSON."""
    if not path.is_file():
        return {}
    props = {}
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            props[key.strip()] = value.strip()
    return props


def finite_nonnegative(s):
    try:
        number = float(s)
        return number if math.isfinite(number) and number >= 0 else None
    except (ValueError, TypeError, OverflowError):
        return None


def legacy_validation(src, out, metadata_issues, folder):
    """Recheck historical IVI results, without trusting status alone."""
    issues = list(metadata_issues)
    if src.get('processExitCode') != '0':
        issues.append('process exit code missing/nonzero')
    if not src.get('logFile') or not (folder / src['logFile']).is_file():
        issues.append('original log missing')
    if src.get('orderingMethod') != 'BFS_ACTIONS':
        issues.append('BFS_ACTIONS ordering not confirmed')
    residual = finite_nonnegative(src.get('bellmanResidual'))
    theta = finite_nonnegative(src.get('theta'))
    if residual is None or theta is None:
        issues.append('missing/invalid Bellman residual or theta')
    backs = finite_nonnegative(src.get('totalBackups'))
    if backs is None:
        issues.append('missing/invalid backups')
    for fld in ('explorationSeconds', 'learningSeconds', 'totalSeconds'):
        if finite_nonnegative(src.get(fld)) is None:
            issues.append(f'missing/invalid {fld}')
    if not src.get('seed', '').strip().isdigit():
        issues.append('seed missing/invalid')
    if src.get('issue'):
        # The historical summarizers label a correctly stopped budget run
        # with NON_CONVERGED/non convergence. Other issues are real errors.
        if (src.get('status', '').endswith('LIMIT_REACHED') and
            src['issue'].strip().casefold() in {'non_converged', 'non convergence'}):
            pass
        else:
            issues.append(f"source validation issue: {src['issue']}")
    if src.get('status') == 'CONVERGED' and residual is not None and theta is not None:
        if residual > theta:
            issues.append('claimed convergence despite residual > theta')
    if issues:
        out['validationState'] = 'INVALID'
        out['issue'] = '; '.join(dict.fromkeys(issues))
    elif src.get('status') == 'CONVERGED':
        out['validationState'] = 'VERIFIED_SUCCESS'
    elif src.get('status', '').endswith('LIMIT_REACHED'):
        out['validationState'] = 'VERIFIED_NONCONVERGENCE'
    else:
        out['validationState'] = 'OTHER_TERMINATION'
    return out


def read_campaign(path, root):
    with path.open(encoding='utf-8', newline='') as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames:
            return [], f'empty CSV: {path}'
        cols = set(reader.fieldnames)
        if {'schemaVersion', 'validationState', 'algorithm'}.issubset(cols):
            schema = 'offline-common-v1'
        elif LEGACY_REQUIRED.issubset(cols):
            schema = ('ivi-legacy-v1' if
                      {'rewardStrategy', 'explorationStrategy'}.issubset(cols)
                      else 'ivi-legacy-v0')
        else:
            return [], f'unsupported CSV header: {path}'
        rows = list(reader)

    proto = protocol_properties(path.parent / 'protocol.txt')
    normalized = []
    for idx, src in enumerate(rows):
        out = {key: src.get(key, '') or '' for key in FIELDS}
        out['campaignId'] = path.parent.name
        out['sourceSchema'] = schema
        out['csvSource'] = path.relative_to(root).as_posix()
        if schema.startswith('ivi-legacy'):
            out['algorithm'] = 'INCREMENTAL_VALUE_ITERATION'
            out['run'] = str(idx)
            issues = []
            if schema == 'ivi-legacy-v0':
                for key, expected in REQUIRED_PROTOCOL_OLD.items():
                    if proto.get(key) != expected:
                        issues.append(f'protocol {key} absent/mismatched')
                # Only use known, validated fields, never infer from directory name.
                out['rewardStrategy'] = proto.get('reward_strategy', '')
                out['explorationStrategy'] = proto.get('exploration', '')
                out['environment'] = proto.get('environment', '')
            else:
                out['rewardStrategy'] = src['rewardStrategy']
                out['explorationStrategy'] = src['explorationStrategy']
                out['environment'] = ('tictacembedded' if out['rewardStrategy'] == 'EMBEDDED'
                                      else 'tictactoe')
                # Do not silently ignore contradictory campaign metadata.
                if proto:
                    for pkey, field in (('reward_strategy', 'rewardStrategy'),
                                        ('exploration', 'explorationStrategy')):
                        if pkey in proto and proto[pkey] != out[field]:
                            issues.append(f'protocol {pkey} mismatch')
            legacy_validation(src, out, issues, path.parent)
        normalized.append(out)
    return normalized, None


def main():
    parser = argparse.ArgumentParser(description='Aggregate all offline experiments, including early IVI')
    parser.add_argument('--project', type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = args.project.resolve()
    destination = args.output.resolve() if args.output else root / 'analysis'
    destination.mkdir(parents=True, exist_ok=True)

    rows = []
    unrecognized = []
    for path in sorted((root / 'experiments').rglob('results.csv')):
        batch, warning = read_campaign(path, root)
        if warning:
            unrecognized.append(warning)
            print('WARNING:', warning)
        rows.extend(batch)

    out_file = destination / 'results_all.csv'
    with out_file.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    groups = defaultdict(list)
    for row in rows:
        key = (row['algorithm'], row['environment'], row['rewardStrategy'],
               row['explorationStrategy'])
        groups[key].append(row)

    summary = []
    for key, candidates in sorted(groups.items()):
        good = [r for r in candidates if r['validationState'] == 'VERIFIED_SUCCESS']
        nonconv = [r for r in candidates if r['validationState'] == 'VERIFIED_NONCONVERGENCE']
        invalid = [r for r in candidates if r['validationState'] in {'INVALID', 'INCOMPLETE'}]
        seeds = {str(r['seed']) for r in good if str(r['seed']) != ''}
        rec = dict(zip(('algorithm', 'environment', 'rewardStrategy', 'explorationStrategy'), key))
        status_counts = Counter(r['status'] or 'UNKNOWN' for r in candidates)
        rec.update(totalRuns=len(candidates), verifiedSuccess=len(good),
                   verifiedNonconvergence=len(nonconv), invalidOrIncomplete=len(invalid),
                   campaignCount=len({r['campaignId'] for r in candidates}),
                   distinctSuccessfulSeeds=len(seeds),
                   reportedStatuses='; '.join(f'{name}:{n}' for name, n in sorted(status_counts.items())))
        for field in METRICS:
            values = [v for v in (finite_nonnegative(r[field]) for r in good) if v is not None]
            rec[field + 'Mean'] = f'{statistics.mean(values):.6f}' if values else ''
            rec[field + 'SampleSD'] = f'{statistics.stdev(values):.6f}' if len(values) > 1 else ''
        summary.append(rec)

    header = ['algorithm', 'environment', 'rewardStrategy', 'explorationStrategy',
              'totalRuns', 'verifiedSuccess', 'verifiedNonconvergence',
              'invalidOrIncomplete', 'campaignCount', 'distinctSuccessfulSeeds',
              'reportedStatuses']
    for field in METRICS:
        header += [field + 'Mean', field + 'SampleSD']
    comparison = destination / 'summary_by_configuration.csv'
    with comparison.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=header)
        writer.writeheader()
        writer.writerows(summary)

    print(f'Aggregated {len(rows)} runs from {len({r["csvSource"] for r in rows})} campaign folders.')
    print('Detailed:', out_file)
    print('Comparison:', comparison)
    print('Distinct seed counts and campaign counts are reported separately; repeating a seed does not create a new seed.')
    print('No original experiment, CSV or log was modified.')
    if unrecognized:
        print(f'WARNING: {len(unrecognized)} CSV(s) remain unsupported; please inspect warnings above.')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
