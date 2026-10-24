#!/usr/bin/env python3
"""Generic audited ProB/offline-RL campaign orchestration (stdlib only).

Examples (from the fresh project root):
    python3 scripts/experiments/offline/manage_campaigns.py taxidriver --plan
    python3 scripts/experiments/offline/manage_campaigns.py taxidriver --validate
    python3 scripts/experiments/offline/manage_campaigns.py taxidriver --run
    python3 scripts/experiments/offline/manage_campaigns.py taxidriver --all

A JSON manifest in scripts/experiments/offline/campaigns/<id>.json defines all
benchmark-specific information. There are NO hard-coded environment names.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from zipfile import ZipFile, ZIP_DEFLATED

ALGOS = {
    'VI': 'VALUE_ITERATION', 'PI': 'POLICY_ITERATION',
    'MPI': 'MODIFIED_POLICY_ITERATION', 'IVI': 'INCREMENTAL_VALUE_ITERATION',
    'PVI': 'PRIORITIZED_VALUE_ITERATION', 'BI': 'BACKWARD_INDUCTION',
}
EXPLORATIONS = {'PREPROCESS', 'RECURSIVE'}
REWARDS = {'ONCEANDFORALL', 'EMBEDDED', 'ONTHEFLY'}
SCHEMA = 'offline-campaign-manager-v1'
ID_PATTERN = re.compile(r'^[a-z][a-z0-9_-]*$')
VALIDATE_FIELDS = ['variant', 'environment', 'algorithm', 'rewardStrategy',
                   'explorationStrategy', 'validation', 'issues', 'campaignId',
                   'expectedStates', 'exploredStates', 'status', 'validationState',
                   'processExitCode', 'gamma', 'theta', 'bellmanResidual',
                   'totalBackups', 'rewardLookups', 'cacheHits', 'cacheMisses',
                   'cachedStates', 'horizon', 'completedHorizon', 'seed', 'run']


def stamp():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def manifest_load(repo, campaign_id):
    if not ID_PATTERN.fullmatch(campaign_id):
        raise ValueError('Campaign ID must match [a-z][a-z0-9_-]*')
    p = repo / 'scripts/experiments/offline/campaigns' / f'{campaign_id}.json'
    if not p.is_file():
        raise ValueError(f'Unknown campaign {campaign_id!r}: expected {p}')
    m = json.loads(p.read_text(encoding='utf-8'))
    if m.get('id') != campaign_id:
        raise ValueError('Manifest id does not match its filename')
    algorithms = m.get('algorithms')
    explorations = m.get('explorations')
    variants = m.get('variants')
    count = m.get('repetitions')
    if not isinstance(algorithms, list) or not algorithms or len(set(algorithms)) != len(algorithms) or any(a not in ALGOS for a in algorithms):
        raise ValueError('Invalid/duplicate algorithms; allowed: ' + ','.join(ALGOS))
    if not isinstance(explorations, list) or not explorations or len(set(explorations)) != len(explorations) or any(x not in EXPLORATIONS for x in explorations):
        raise ValueError('Invalid/duplicate explorations')
    if type(count) is not int or not (2 <= count <= 100):
        raise ValueError('repetitions must be an integer 2..100')
    if not isinstance(variants, list) or not variants:
        raise ValueError('Manifest must define a nonempty list of variants')
    for k in ('gamma', 'theta'):
        if type(m.get(k)) not in (float, int) or not math.isfinite(m[k]) or m[k] <= 0:
            raise ValueError(f'Expected finite positive {k}')
    if not 0 < m['gamma'] <= 1:
        raise ValueError('gamma must be in (0, 1]')
    if 'BI' in algorithms and (type(m.get('biHorizon')) is not int or m['biHorizon'] < 1):
        raise ValueError('BI requires positive integer biHorizon')
    seen_ids, seen_envs = set(), set()
    for v in variants:
        if not isinstance(v, dict) or not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', str(v.get('id', ''))):
            raise ValueError('Variant requires a safe lowercase id')
        if v['id'] in seen_ids:
            raise ValueError(f'Duplicate variant {v["id"]}')
        seen_ids.add(v['id'])
        if type(v.get('expectedStates')) is not int or v['expectedStates'] < 1:
            raise ValueError(f'{v["id"]}: expectedStates must be a positive integer')
        envs = v.get('environments')
        if not isinstance(envs, dict) or not envs:
            raise ValueError(f'{v["id"]}: missing environments mapping')
        for reward, env in envs.items():
            if reward not in REWARDS or not isinstance(env, str) or not ID_PATTERN.fullmatch(env):
                raise ValueError(f'{v["id"]}: invalid reward/environment {reward}: {env}')
            if env in seen_envs:
                raise ValueError(f'Environment {env} is defined in multiple variants')
            seen_envs.add(env)
            if reward == 'EMBEDDED' and not env.endswith('embedded'):
                raise ValueError(f'EMBEDDED key must end with embedded: {env}')
            if reward != 'EMBEDDED' and env.endswith('embedded'):
                raise ValueError(f'Non-EMBEDDED key ends with embedded: {env}')
    return m, p


def specs(m):
    for v in m['variants']:
        for exploration, short, (reward, env) in itertools.product(
                m['explorations'], m['algorithms'], v['environments'].items()):
            yield {'variant': v['id'], 'expected': v['expectedStates'],
                   'exploration': exploration, 'flag': short,
                   'algorithm': ALGOS[short], 'reward': reward, 'env': env,
                   'key': (ALGOS[short], reward, exploration, env)}


def stable_files(repo):
    """Source/config fingerprint, excluding experiment output and generated files."""
    fixed = ['pom.xml', 'config/agent-parameters.properties',
             'scripts/experiments/offline/run_campaign.sh']
    files = []
    for name in fixed:
        p = repo / name
        if not p.is_file():
            raise RuntimeError(f'Missing required experiment input: {p}')
        files.append(p)
    folders = ('src/main/java', 'src/main/resources', 'scripts/experiments/offline')
    for rel in folders:
        d = repo / rel
        if not d.is_dir():
            raise RuntimeError(f'Missing project directory: {d}')
        for p in d.rglob('*'):
            if not p.is_file() or any(part.startswith('.') or part == '__pycache__'
                                      for part in p.relative_to(repo).parts):
                continue
            if p.suffix.lower() in {'.java', '.mch', '.def', '.properties', '.xml', '.json',
                                    '.py', '.sh', '.yml', '.yaml'}:
                files.append(p)
    return sorted(set(files))


def fingerprint(repo):
    h = hashlib.sha256()
    for p in stable_files(repo):
        rel = p.relative_to(repo).as_posix()
        h.update(rel.encode('utf-8') + b'\0' + bytes.fromhex(digest(p)))
    return h.hexdigest()


def read_folder(folder):
    with (folder / 'summary.json').open(encoding='utf-8') as f:
        s = json.load(f)
    with (folder / 'results.csv').open(newline='', encoding='utf-8-sig') as f:
        rows = list(csv.DictReader(f))
    key = tuple(s.get(x) for x in ('algorithm', 'rewardStrategy',
                                    'explorationStrategy', 'environment'))
    if s.get('campaignId') != folder.name:
        raise ValueError('summary.campaignId differs from directory')
    if s.get('totalRuns') != len(rows):
        raise ValueError('summary.totalRuns differs from CSV row count')
    if not rows:
        raise ValueError('Empty CSV')
    for i, row in enumerate(rows):
        rk = tuple(row.get(x) for x in ('algorithm', 'rewardStrategy',
                                       'explorationStrategy', 'environment'))
        if rk != key or row.get('campaignId') != folder.name:
            raise ValueError(f'CSV row {i} disagrees with summary identity')
    return s, rows, key


def number(row, field, errors, prefix, *, nonnegative=False):
    try:
        n = float(row[field])
        if not math.isfinite(n) or (nonnegative and n < 0):
            raise ValueError
        return n
    except (KeyError, ValueError, TypeError):
        errors.append(f'{prefix}: invalid {field}={row.get(field)}')
        return None


def check_rows(m, c, s, rows, expected_count):
    problems = []
    if (s.get('algorithm'), s.get('rewardStrategy'),
        s.get('explorationStrategy'), s.get('environment')) != c['key']:
        problems.append('Unexpected campaign identity')
    if s.get('totalRuns') != expected_count or len(rows) != expected_count:
        problems.append(f'Expected {expected_count} runs')
    if s.get('successfulRuns') != expected_count or s.get('validationStates') != {'VERIFIED_SUCCESS': expected_count}:
        problems.append('summary successfulRuns or validationStates is not all VERIFIED_SUCCESS')
    if [r.get('run') for r in rows] != [str(i) for i in range(expected_count)]:
        problems.append('Run indices must be 0..count-1')
    if c['flag'] == 'IVI' and [r.get('seed') for r in rows] != [str(i) for i in range(expected_count)]:
        problems.append('IVI must use seeds 0..count-1')
    for i, r in enumerate(rows):
        prefix = f'run {i}'
        if r.get('validationState') != 'VERIFIED_SUCCESS' or r.get('issue'):
            problems.append(f'{prefix}: validationState={r.get("validationState")}; issue={r.get("issue")}')
        if r.get('processExitCode') != '0':
            problems.append(f'{prefix}: processExitCode != 0')
        if r.get('exploredStates') != str(c['expected']):
            problems.append(f'{prefix}: exploredStates != {c["expected"]}')
        gamma = number(r, 'gamma', problems, prefix)
        if gamma is not None and abs(gamma - m['gamma']) > 1e-8:
            problems.append(f'{prefix}: gamma differs from manifest')
        if c['flag'] == 'BI':
            if r.get('status') != 'HORIZON_COMPLETED':
                problems.append(f'{prefix}: BI did not complete finite horizon')
            horizon = str(m['biHorizon'])
            if r.get('horizon') != horizon or r.get('completedHorizon') != horizon:
                problems.append(f'{prefix}: BI horizon != {horizon}')
            number(r, 'lastHorizonDelta', problems, prefix, nonnegative=True)
        else:
            if r.get('status') != 'CONVERGED':
                problems.append(f'{prefix}: status != CONVERGED')
            theta = number(r, 'theta', problems, prefix, nonnegative=True)
            res = number(r, 'bellmanResidual', problems, prefix, nonnegative=True)
            if theta is not None and abs(theta - m['theta']) > 1e-8:
                problems.append(f'{prefix}: theta differs from manifest')
            if res is not None and theta is not None and res > theta + 1e-9:
                problems.append(f'{prefix}: Bellman residual exceeds theta')
            if c['flag'] in ('PI', 'MPI') and r.get('policyStable', '').lower() != 'true':
                problems.append(f'{prefix}: policyStable not true')
        for k in ('explorationSeconds', 'learningSeconds', 'totalSeconds',
                  'totalBackups', 'rewardLookups'):
            number(r, k, problems, prefix, nonnegative=True)
        if c['reward'] == 'ONCEANDFORALL':
            try:
                hits, misses, cached, total = [int(r[x]) for x in
                     ('cacheHits', 'cacheMisses', 'cachedStates', 'rewardLookups')]
                if hits < 0 or misses < 1 or misses != cached or cached > c['expected'] or hits + misses != total:
                    problems.append(f'{prefix}: Once-and-for-all reward cache accounting inconsistent')
            except (KeyError, ValueError, TypeError):
                problems.append(f'{prefix}: missing/invalid reward cache counters')
    return problems


def matches_own_folder(folder, envs):
    return any(f'_{name}_' in folder.name for name in envs)


def scan(repo, m):
    out = repo / 'experiments/offline'
    out.mkdir(parents=True, exist_ok=True)
    configs = {c['key']: c for c in specs(m)}
    envs = {c['env'] for c in configs.values()}
    finals, pilots, ambiguous = {}, [], []
    for folder in sorted(out.iterdir()):
        if not folder.is_dir() or folder.is_symlink() or not matches_own_folder(folder, envs):
            continue
        try:
            summary, rows, key = read_folder(folder)
            if key not in configs:
                ambiguous.append(f'{folder.name}: unexpected algorithm/reward/exploration/environment {key}')
            elif len(rows) == 1:
                pilots.append((folder, summary))
            elif len(rows) == m['repetitions']:
                if key in finals:
                    ambiguous.append(f'{folder.name}: duplicate final for {key}')
                else:
                    finals[key] = (folder, check_rows(m, configs[key], summary, rows, m['repetitions']))
            else:
                ambiguous.append(f'{folder.name}: incomplete/unknown run count {len(rows)}')
        except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
            ambiguous.append(f'{folder.name}: {exc}')
    return finals, pilots, ambiguous


def print_scan(m, finals, pilots, ambiguous):
    n = len(list(specs(m)))
    good = sum(not problems for _, problems in finals.values())
    print(f'Final campaigns: {good}/{n} verified | pilots: {len(pilots)} | ambiguous: {len(ambiguous)}', flush=True)
    for key, (_, problems) in finals.items():
        if problems:
            print(f'  INVALID {key}: {"; ".join(problems[:5])}', flush=True)
    for x in ambiguous[:30]:
        print(f'  REVIEW {x}', flush=True)
    return good == n and len(finals) == n and not ambiguous


def stream_runner(repo, cmd, logfile):
    campaign = None
    with logfile.open('a', encoding='utf-8') as log:
        log.write('\nCOMMAND: ' + ' '.join(cmd) + '\n')
        with subprocess.Popen(cmd, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding='utf-8', errors='replace', bufsize=1) as proc:
            assert proc.stdout is not None
            for line in proc.stdout:
                log.write(line)
                if line.startswith('New campaign: '):
                    campaign = line[len('New campaign: '):].strip()
                if line.startswith('CHECK run ') or line.startswith('Completed:'):
                    print(line.rstrip(), flush=True)
            code = proc.wait()
    return code, campaign


def one_folder(repo, raw):
    if not raw:
        raise RuntimeError('Runner did not report New campaign: ...')
    folder = Path(raw).resolve()
    root = (repo / 'experiments/offline').resolve()
    if folder.parent != root or not folder.is_dir() or folder.is_symlink():
        raise RuntimeError(f'Unsafe or missing campaign path from runner: {raw}')
    return folder


def save_csv(path, rows, cols):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction='ignore')
        w.writeheader()
        w.writerows(rows)


def authorize_path(repo, mid):
    return repo / 'experiments/campaigns' / mid / 'validation-ok.json'


def validate(repo, m, manifest_path):
    marker = authorize_path(repo, m['id'])
    marker.unlink(missing_ok=True)  # fail closed when a new validation starts
    root = marker.parent / ('validation-' + stamp())
    if root.exists():
        raise RuntimeError(f'Validation report already exists: {root}')
    (root / 'logs').mkdir(parents=True)
    plan = list(specs(m))
    initial_hash = fingerprint(repo)
    runner = repo / 'scripts/experiments/offline/run_campaign.sh'
    rows = []
    report = root / 'validation.csv'
    errors = []
    for i, c in enumerate(plan, 1):
        print(f'VALIDATE {i}/{len(plan)}: {c["variant"]} {c["flag"]} {c["reward"]} {c["exploration"]}', flush=True)
        cmd = ['bash', str(runner), '--algorithm', c['flag'], '--reward', c['reward'],
               '--exploration', c['exploration'], '--environment', c['env'],
               '--count', '1', '--expected-states', str(c['expected'])]
        log = root / 'logs' / f'{i:03d}_{c["env"]}_{c["flag"]}_{c["exploration"]}.log'
        code, raw = stream_runner(repo, cmd, log)
        issues = []
        summary, trial = {}, []
        try:
            folder = one_folder(repo, raw)
            summary, trial, key = read_folder(folder)
            issues.extend(check_rows(m, c, summary, trial, 1))
            if key != c['key']:
                issues.append('Runner returned wrong configuration')
        except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
            issues.append(str(exc))
        if code != 0:
            issues.append(f'Runner exit code {code}')
        row0 = trial[0] if trial else {}
        row = {'variant': c['variant'], 'environment': c['env'],
               'algorithm': c['algorithm'], 'rewardStrategy': c['reward'],
               'explorationStrategy': c['exploration'],
               'validation': 'FAIL' if issues else 'PASS', 'issues': '; '.join(issues),
               'campaignId': summary.get('campaignId', ''),
               'expectedStates': c['expected'], **row0}
        rows.append(row)
        save_csv(report, rows, VALIDATE_FIELDS)
        if issues:
            errors.append(f'{i}: {row["issues"]}')
            print(f'  FAIL: {row["issues"][:350]}', flush=True)
        else:
            print('  PASS', flush=True)
    if fingerprint(repo) != initial_hash:
        errors.append('Source/configuration changed DURING validation')
    if errors:
        print(f'VALIDATION FAILED {sum(r["validation"] == "PASS" for r in rows)}/{len(plan)} passed (see {report})', flush=True)
        for x in errors[:20]:
            print(' BLOCKER:', x, flush=True)
        raise RuntimeError('At least one validation failed. Final runs NOT authorized.')
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(json.dumps({
        'schema': SCHEMA, 'campaign': m['id'],
        'sourceFingerprint': initial_hash, 'manifestSha256': digest(manifest_path),
        'report': str(report), 'reportSha256': digest(report),
        'configurations': len(plan), 'timeUtc': datetime.now(timezone.utc).isoformat(),
    }, indent=2) + '\n', encoding='utf-8')
    print(f'VALIDATION PASSED {len(plan)}/{len(plan)}. Report: {report}', flush=True)


def verify_authorization(repo, m, manifest_path):
    marker = authorize_path(repo, m['id'])
    if not marker.is_file():
        raise RuntimeError('No valid validation marker. Run --validate before --run.')
    a = json.loads(marker.read_text(encoding='utf-8'))
    if (a.get('schema') != SCHEMA or a.get('campaign') != m['id'] or
        a.get('manifestSha256') != digest(manifest_path) or
        a.get('configurations') != len(list(specs(m))) or
        a.get('sourceFingerprint') != fingerprint(repo)):
        raise RuntimeError('Code/specification/configuration changed, or validation is incomplete. Re-run --validate.')
    report = Path(a.get('report', ''))
    if (not report.is_file() or not report.resolve().is_relative_to((repo / 'experiments/campaigns' / m['id']).resolve())
            or digest(report) != a.get('reportSha256')):
        raise RuntimeError('Validation report missing, moved or modified. Re-run --validate.')
    with report.open(newline='', encoding='utf-8-sig') as f:
        rows = list(csv.DictReader(f))
    expected = {c['key'] for c in specs(m)}
    observed = [(r['algorithm'], r['rewardStrategy'], r['explorationStrategy'], r['environment']) for r in rows]
    if len(rows) != len(expected) or set(observed) != expected or len(set(observed)) != len(observed) or any(r['validation'] != 'PASS' for r in rows):
        raise RuntimeError('Validation report does not prove all unique configurations PASS')
    return report.parent


def expected_snapshot_lines(repo):
    """Mirror the input_sha256.txt list written by existing run_campaign.py."""
    targets = ['pom.xml', 'src/main/java', 'src/main/resources',
               'config/agent-parameters.properties',
               'src/main/resources/agent-parameters.properties',
               'scripts/experiments/offline']
    lines = []
    for rel in targets:
        path = repo / rel
        if not path.exists():
            continue
        if path.is_file():
            files = [path]
        else:
            files = [p for p in path.rglob('*') if p.is_file() and not any(
                segment.startswith('.') for segment in p.relative_to(repo).parts)]
        for file in sorted(files):
            if file.suffix in ('.class', '.pyc'):
                continue
            lines.append(f'{digest(file)}  {file.relative_to(repo).as_posix()}')
    return sorted(lines)


def snapshot_matches(repo, folder):
    """Strict check of the exact source snapshot (both paths and content hashes).

    Reject previously complete final campaigns that were generated with a
    different version of the B/Java/config sources or a different set of files.
    """
    p = folder / 'input_sha256.txt'
    if not p.is_file():
        return False, 'Missing input_sha256.txt source provenance'
    try:
        captured = sorted(line for line in p.read_text(encoding='utf-8').splitlines()
                          if line.strip())
        current = expected_snapshot_lines(repo)
        if captured != current:
            return False, 'Snapshot does not match the exact current source/config file set'
        return True, ''
    except (ValueError, OSError) as exc:
        return False, f'Unreadable source provenance: {exc}'


def export_archives(repo, m, finals):
    archives = []
    for variant in m['variants']:
        name = f'{m["id"]}_{variant["id"]}_final_results.zip'
        target = repo / name
        tmp = repo / (name + '.part')
        if tmp.exists():
            raise RuntimeError('A temporary ZIP already exists: ' + str(tmp))
        selected = [c for c in specs(m) if c['variant'] == variant['id']]
        with ZipFile(tmp, 'w', ZIP_DEFLATED) as z:
            for c in selected:
                folder, problems = finals[c['key']]
                if problems:
                    raise RuntimeError('Attempted to export invalid final')
                for filename in ('results.csv', 'summary.json'):
                    src = folder / filename
                    z.write(src, src.relative_to(repo))
        tmp.replace(target)
        print(f'EXPORTED {target}: {len(selected)} configurations x {m["repetitions"]} runs', flush=True)
        archives.append(target)
    return archives


def archive_pilots(repo, m, pilots, archive_root):
    if not pilots:
        return None
    destination = archive_root / f'{m["id"]}-pilots-{stamp()}'
    destination.mkdir(parents=True, exist_ok=False)
    manifest = []
    for folder, summary in pilots:
        if folder.resolve().parent != (repo / 'experiments/offline').resolve():
            raise RuntimeError(f'Unsafe pilot path: {folder}')
        target = destination / folder.name
        if target.exists():
            raise RuntimeError(f'Collision archiving pilot: {target}')
        shutil.move(str(folder), str(target))
        manifest.append({'folder': folder.name, 'algorithm': summary['algorithm'],
                         'environment': summary['environment'],
                         'reward': summary['rewardStrategy'],
                         'exploration': summary['explorationStrategy']})
    save_csv(destination / 'manifest.csv', manifest, list(manifest[0]))
    print(f'ARCHIVED {len(pilots)} one-run pilot campaigns to {destination}', flush=True)
    return destination


def run(repo, m, manifest_path, archive_root):
    report_dir = verify_authorization(repo, m, manifest_path)
    configs = list(specs(m))
    finals, pilots, ambiguous = scan(repo, m)
    print_scan(m, finals, pilots, ambiguous)
    if ambiguous or any(issues for _, issues in finals.values()):
        raise RuntimeError('Ambiguous/invalid final campaigns already present. No output moved.')
    for _, (folder, _) in finals.items():
        ok, msg = snapshot_matches(repo, folder)
        if not ok:
            raise RuntimeError(f'Existing final not from current sources: {folder.name}: {msg}')
    runner = repo / 'scripts/experiments/offline/run_campaign.sh'
    log = report_dir / 'final_launch.log'
    start_hash = fingerprint(repo)
    for i, c in enumerate(configs, 1):
        if c['key'] in finals:
            print(f'SKIP valid {i}/{len(configs)} {c["variant"]} {c["flag"]} {c["reward"]} {c["exploration"]}', flush=True)
            continue
        print(f'FINAL {i}/{len(configs)} {c["variant"]} {c["flag"]} {c["reward"]} {c["exploration"]}', flush=True)
        cmd = ['bash', str(runner), '--algorithm', c['flag'], '--reward', c['reward'],
               '--exploration', c['exploration'], '--environment', c['env'],
               '--count', str(m['repetitions']), '--expected-states', str(c['expected'])]
        code, raw = stream_runner(repo, cmd, log)
        if code:
            raise RuntimeError(f'Runner exit={code}. All evidence preserved. See {log}')
        created = one_folder(repo, raw)
        s, rows, key = read_folder(created)
        errs = check_rows(m, c, s, rows, m['repetitions'])
        matches, msg = snapshot_matches(repo, created)
        if not matches:
            errs.append(msg)
        if key != c['key'] or errs:
            raise RuntimeError(f'New final invalid: {created.name}: {errs}. No cleanup.')
        finals, pilots, ambiguous = scan(repo, m)
        if ambiguous:
            raise RuntimeError(f'Unexpected overlapping/ambiguous campaigns: {ambiguous}')
    if fingerprint(repo) != start_hash:
        raise RuntimeError('Source/configuration changed during FINAL runs. No cleanup.')
    finals, pilots, ambiguous = scan(repo, m)
    if not print_scan(m, finals, pilots, ambiguous):
        raise RuntimeError('Incomplete final set; no archives/cleanup.')
    for _, (folder, _) in finals.items():
        matches, msg = snapshot_matches(repo, folder)
        if not matches:
            raise RuntimeError(f'Final source provenance mismatch: {folder.name}: {msg}')
    # No moving any experiment until exports are secured.
    archives = export_archives(repo, m, finals)
    # Fail closed: do not archive pilots if any final audit fails.
    from audit_campaigns import generate_from_archives
    generate_from_archives(repo, m, archives)
    archive_pilots(repo, m, pilots, archive_root)
    print(f'SUCCESS: {len(configs)} final campaigns, {len(configs)*m["repetitions"]} verified runs; '
          f'{len(archives)} final ZIP(s).', flush=True)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('campaign', help='ID of scripts/experiments/offline/campaigns/<campaign>.json')
    modes = p.add_mutually_exclusive_group(required=True)
    modes.add_argument('--plan', action='store_true', help='Print matrix without running ProB')
    modes.add_argument('--validate', action='store_true', help='One validated run per configuration')
    modes.add_argument('--run', action='store_true', help='Final repeated experiments, requires complete validation')
    modes.add_argument('--all', action='store_true', help='Validate, then run only if all validations succeed')
    modes.add_argument('--audit', action='store_true', help='Rebuild audits from existing final ZIPs without ProB')
    modes.add_argument('--status', action='store_true', help='Read-only final campaign status')
    p.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[3],
                   help='fresh project root (inferred from script location)')
    p.add_argument('--archive', type=Path, default=Path.home() / 'rllib4prob-private-archive',
                   help='Private pilot archive, must be outside fresh project')
    args = p.parse_args(argv)
    try:
        repo = args.repo.expanduser().resolve()
        m, manifest_path = manifest_load(repo, args.campaign)
        plan = list(specs(m))
        if args.plan:
            print(f'Campaign: {m["id"]} ({len(plan)} configurations; '
                  f'{m["repetitions"]} repetitions each = {len(plan)*m["repetitions"]} final runs)')
            for c in plan:
                print(f'  {c["variant"]:>5} {c["flag"]:>3} {c["reward"]:<13} '
                      f'{c["exploration"]:<10} {c["env"]:<27} states={c["expected"]}')
            return 0
        if args.status:
            finals, pilots, ambiguous = scan(repo, m)
            return 0 if print_scan(m, finals, pilots, ambiguous) else 1
        if args.audit:
            # Historical audits do not require current source hashes or a new validation.
            from audit_campaigns import generate_from_archives
            generate_from_archives(repo, m)
            return 0
        archive_root = args.archive.expanduser().resolve()
        if os.path.commonpath([str(repo), str(archive_root)]) == str(repo):
            raise RuntimeError('Private archive MUST be outside the fresh repository')
        if not (repo / 'scripts/experiments/offline/run_campaign.sh').is_file():
            raise RuntimeError('Cannot find the existing runner in fresh')
        if args.validate or args.all:
            validate(repo, m, manifest_path)
        if args.run or args.all:
            run(repo, m, manifest_path, archive_root)
    except (OSError, ValueError, RuntimeError, KeyError, json.JSONDecodeError) as exc:
        print(f'STOP: {exc}', file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
