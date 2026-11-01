#!/usr/bin/env python3
"""Rebuild the standard 80-column offline-RL audit CSV from final campaign ZIPs.

Only Python's standard library is required. Designed to run automatically after
--run, or post-hoc on historical ZIPs with --audit. No Java/ProB execution.

AuditPassed means measurement integrity. VERIFIED_NONCONVERGENCE is a valid
observation, not successful convergence. Missing provenance is left blank;
never invent measured transitions or model paths.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
from decimal import Decimal, InvalidOperation
import io
import json
import math
from pathlib import Path, PurePosixPath
import statistics
import sys
import tempfile
from zipfile import ZipFile

ALGO = {
    'VI': 'VALUE_ITERATION', 'PI': 'POLICY_ITERATION',
    'MPI': 'MODIFIED_POLICY_ITERATION', 'IVI': 'INCREMENTAL_VALUE_ITERATION',
    'PVI': 'PRIORITIZED_VALUE_ITERATION', 'BI': 'BACKWARD_INDUCTION',
}
ABBREV = {v:k for k,v in ALGO.items()}
SOURCE_COLUMNS = [
    'schemaVersion','campaignId','run','seed','algorithm','environment',
    'rewardStrategy','explorationStrategy','status','validationState','issue',
    'processExitCode','exploredStates','explorationSeconds','learningSeconds',
    'totalSeconds','gamma','theta','iterations','maxIterations','maxUpdates',
    'updates','horizon','completedHorizon','evalIterations','evaluationSweeps',
    'maxEvaluationSweeps','updatesPerIteration','policyStable','bellmanResidual',
    'policyResidual','evaluationDelta','lastHorizonDelta','updateBackups',
    'evaluationBackups','improvementBackups','priorityBackups','residualBackups',
    'finalQBackups','totalBackups','rewardLookups','orderingMethod',
    'orderingSeconds','cacheHits','cacheMisses','cachedStates','logFile'
]
AUDIT_PREFIX = [
    'benchmark','modelFile','referenceReachableStates',
    'referenceReachableTransitions','referenceTransitionsSource',
    'algorithmAbbreviation','planningHorizonType','auditRunId',
    'campaignRunCount','summarySuccessfulRuns','auditPassed','auditIssues',
    'auditTimeDeltaSeconds','auditComputedBackups','auditCacheCheck',
    'auditConvergenceCheck','auditSummaryCheck'
]
STAT_FIELDS = {
    'explorationSeconds': ('summaryMeanExplorationSeconds','summarySDExplorationSeconds'),
    'learningSeconds': ('summaryMeanLearningSeconds','summarySDLearningSeconds'),
    'totalSeconds': ('summaryMeanTotalSeconds','summarySDTotalSeconds'),
    'totalBackups': ('summaryMeanBackups','summarySDBackups'),
    'iterations': ('summaryMeanIterations','summarySDIterations'),
    'orderingSeconds': ('summaryMeanOrderingSeconds','summarySDOrderingSeconds'),
}
STAT_COLUMNS = [f for pair in STAT_FIELDS.values() for f in pair]
PATH_COLUMNS = ['sourceResultsCsv','sourceSummaryJson','sourceLogPath','sourceLogIncludedInZip']
COLUMNS = AUDIT_PREFIX + SOURCE_COLUMNS + STAT_COLUMNS + PATH_COLUMNS
assert len(COLUMNS) == 80 and len(set(COLUMNS)) == 80
BACKUPS = ('updateBackups','evaluationBackups','improvementBackups',
           'priorityBackups','residualBackups','finalQBackups')


def decimal(value):
    if value in ('', None):
        raise ValueError('missing number')
    try:
        d = Decimal(str(value))
    except InvalidOperation as ex:
        raise ValueError(f'not a number: {value!r}') from ex
    if not d.is_finite():
        raise ValueError('nonfinite number')
    return d


def integer(value):
    d = decimal(value)
    if d != d.to_integral_value():
        raise ValueError(f'not an integer: {value}')
    return int(d)


def close(a,b,abs_tol=1e-5,rel_tol=1e-7):
    return math.isclose(float(a),float(b),abs_tol=abs_tol,rel_tol=rel_tol)


def metadata(variant, key, default=''):
    val = variant.get(key, default)
    if val is None:
        return ''
    return str(val)


def expected_for_variant(m, variant):
    return {(ALGO[a],reward,exp,env) for a in m['algorithms']
            for exp in m['explorations']
            for reward,env in variant['environments'].items()}


def check_summary_statistics(summary, runs):
    errors=[]
    success=[r for r in runs if r['validationState']=='VERIFIED_SUCCESS']
    summary_stats=summary.get('statisticsOnVerifiedSuccessOnly',{})
    if not isinstance(summary_stats,dict):
        return ['statisticsOnVerifiedSuccessOnly not a dictionary']
    for field in STAT_FIELDS:
        vals=[float(decimal(r[field])) for r in success if r[field]!='']
        stat=summary_stats.get(field)
        if not vals:
            if stat is not None and stat.get('n',0)!=0:
                errors.append(f'{field}: unexpected summary without values')
            continue
        if not isinstance(stat,dict) or stat.get('n')!=len(vals):
            errors.append(f'{field}: summary count missing/mismatch')
            continue
        if not close(stat.get('mean',float('nan')),statistics.mean(vals)):
            errors.append(f'{field}: summary mean differs')
        if len(vals)>1:
            if stat.get('sampleSD') is None or not close(stat['sampleSD'],statistics.stdev(vals)):
                errors.append(f'{field}: summary sampleSD differs')
    return errors


def audited_filename(m, variant):
    # Human-friendly names consistent with existing audit convention.
    if len(m['variants']) == 1:
        return f'{m["id"]}_audit.csv'
    return f'{m["id"]}{variant["id"]}_audit.csv'


def audit_zip(m, variant, archive):
    configs=expected_for_variant(m,variant)
    expected_count=m['repetitions']
    expected_states=integer(variant['expectedStates'])
    if not archive.is_file():
        raise RuntimeError(f'Missing final archive: {archive}')
    outputs=[]
    observed=set()
    with ZipFile(archive) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):
            raise RuntimeError(f'{archive.name}: duplicated ZIP members')
        entries=set(names)
        csv_names=sorted(n for n in names if n.endswith('/results.csv'))
        json_names={n for n in names if n.endswith('/summary.json')}
        if len(csv_names)!=len(configs) or len(json_names)!=len(configs):
            raise RuntimeError(f'{archive.name}: expected {len(configs)} CSV/JSON pairs, '
                               f'found {len(csv_names)} results CSVs and {len(json_names)} summaries')
        for path in csv_names:
            pp=PurePosixPath(path)
            if pp.is_absolute() or '..' in pp.parts or len(pp.parts)<3:
                raise RuntimeError(f'Unsafe ZIP path: {path}')
            summary_path=path[:-len('results.csv')]+'summary.json'
            if summary_path not in entries:
                raise RuntimeError(f'Missing summary for {path}')
            srcdir=path.rsplit('/',1)[0]
            summary=json.loads(z.read(summary_path))
            reader=csv.DictReader(io.StringIO(z.read(path).decode('utf-8-sig')))
            if reader.fieldnames != SOURCE_COLUMNS:
                raise RuntimeError(f'{path}: source schema differs from offline-common-v1')
            runs=list(reader)
            if len(runs)!=expected_count:
                raise RuntimeError(f'{path}: expected {expected_count} rows, got {len(runs)}')
            first=runs[0]
            key=tuple(first[k] for k in ('algorithm','rewardStrategy',
                                          'explorationStrategy','environment'))
            if key not in configs or key in observed:
                raise RuntimeError(f'{path}: unexpected or duplicate configuration: {key}')
            observed.add(key)
            if summary.get('campaignId') != first['campaignId'] or pp.parent.name != first['campaignId']:
                raise RuntimeError(f'{path}: inconsistent campaign ID')
            if [r['run'] for r in runs] != [str(i) for i in range(expected_count)]:
                raise RuntimeError(f'{path}: missing/duplicate run IDs')
            if first['algorithm']=='INCREMENTAL_VALUE_ITERATION':
                if [r['seed'] for r in runs] != [str(i) for i in range(expected_count)]:
                    raise RuntimeError(f'{path}: IVI seeds not 0..{expected_count-1}')
            if summary.get('totalRuns')!=expected_count:
                raise RuntimeError(f'{path}: summary totalRuns inconsistent')
            states=Counter(r['validationState'] for r in runs)
            if summary.get('validationStates')!=dict(states):
                raise RuntimeError(f'{path}: summary validationStates differs from raw CSV')
            n_success=states['VERIFIED_SUCCESS']
            if summary.get('successfulRuns')!=n_success:
                raise RuntimeError(f'{path}: summary successfulRuns differs from raw CSV')
            stat_errors=check_summary_statistics(summary,runs)
            sstats=summary.get('statisticsOnVerifiedSuccessOnly',{})
            for r in runs:
                issues=[]
                out=dict.fromkeys(COLUMNS,'')
                out['benchmark']=metadata(variant,'auditBenchmark',f'{m["id"]}:{variant["id"]}')
                out['modelFile']=str(variant.get('modelFiles',{}).get(r['rewardStrategy'],'') or '')
                out['referenceReachableStates']=str(expected_states)
                out['referenceReachableTransitions']=metadata(variant,'referenceReachableTransitions')
                out['referenceTransitionsSource']=metadata(variant,'referenceTransitionsSource')
                out['algorithmAbbreviation']=ABBREV[r['algorithm']]
                out['planningHorizonType']='FINITE' if r['algorithm']=='BACKWARD_INDUCTION' else 'INFINITE'
                out['auditRunId']=f'{r["campaignId"]}/{r["run"]}'
                out['campaignRunCount']=str(expected_count)
                out['summarySuccessfulRuns']=str(n_success)
                out.update(r)
                out['sourceResultsCsv']=path
                out['sourceSummaryJson']=summary_path
                logpath=r['logFile']
                out['sourceLogPath']=f'{srcdir}/{logpath}' if logpath else ''
                out['sourceLogIncludedInZip']=str(out['sourceLogPath'] in entries).lower()
                for k in ('campaignId','algorithm','environment','rewardStrategy','explorationStrategy'):
                    if summary.get(k)!=r[k] or r[k]!=first[k]:
                        issues.append(f'inconsistent {k}')
                if r['schemaVersion']!='offline-common-v1':
                    issues.append('unexpected source schemaVersion')
                if r['exploredStates']!=str(expected_states):
                    issues.append(f'exploredStates != {expected_states}')
                if r['processExitCode']!='0':
                    issues.append('processExitCode != 0')
                if r['issue']:
                    issues.append('runner issue: '+r['issue'])
                try:
                    if not close(decimal(r['gamma']),decimal(m['gamma']),abs_tol=1e-8):
                        issues.append('gamma differs from manifest')
                except ValueError:
                    issues.append('invalid gamma')
                try:
                    tdelta=decimal(r['explorationSeconds'])+decimal(r['learningSeconds'])-decimal(r['totalSeconds'])
                    out['auditTimeDeltaSeconds']=f'{tdelta:.9f}'
                    if abs(tdelta)>Decimal('0.00001'):
                        issues.append('totalSeconds not exploration+learning')
                    if any(decimal(r[k])<0 for k in ('explorationSeconds','learningSeconds','totalSeconds')):
                        issues.append('negative execution time')
                except ValueError:
                    issues.append('time measurement missing/invalid')
                try:
                    bsum=sum(integer(r[k]) for k in BACKUPS if r[k]!='')
                    out['auditComputedBackups']=str(bsum)
                    if bsum!=integer(r['totalBackups']) or bsum<0:
                        issues.append('backup component mismatch')
                except ValueError:
                    issues.append('invalid backup counts')
                try:
                    if integer(r['rewardLookups'])<0:
                        issues.append('negative rewardLookups')
                except ValueError:
                    issues.append('rewardLookups missing/invalid')
                if r['rewardStrategy']=='ONCEANDFORALL':
                    try:
                        hits,misses,cached,lookups=[integer(r[k]) for k in
                            ('cacheHits','cacheMisses','cachedStates','rewardLookups')]
                        if not (hits>=0 and misses>=0 and cached>0 and cached<=expected_states
                                and misses==cached and hits+misses==lookups):
                            raise ValueError('invalid cache counters')
                        out['auditCacheCheck']='PASS'
                    except ValueError:
                        out['auditCacheCheck']='FAIL'
                        issues.append('Once-and-for-all cache counters inconsistent')
                else:
                    out['auditCacheCheck']='NOT_APPLICABLE'
                    if any(r[k] for k in ('cacheHits','cacheMisses','cachedStates')):
                        issues.append('unexpected cache stats for non OaF strategy')
                conv='FAIL'
                if r['algorithm']=='BACKWARD_INDUCTION':
                    try:
                        valid=(r['status']=='HORIZON_COMPLETED' and
                               r['validationState']=='VERIFIED_SUCCESS' and
                               integer(r['horizon'])==integer(r['completedHorizon'])==integer(m['biHorizon']) and
                               decimal(r['lastHorizonDelta'])>=0)
                        if valid:conv='PASS_HORIZON'
                    except ValueError:
                        pass
                else:
                    try:
                        theta=decimal(r['theta']); residual=decimal(r['bellmanResidual'])
                        if theta<=0 or residual<0 or not close(theta,decimal(m['theta']),abs_tol=1e-8):
                            raise ValueError('theta/residual invalid')
                        if (r['status']=='CONVERGED' and r['validationState']=='VERIFIED_SUCCESS'
                            and residual<=theta+Decimal('0.000000001')):
                            conv='PASS_CONVERGED'
                        elif (r['validationState']=='VERIFIED_NONCONVERGENCE'
                              and r['status'] in ('ITERATION_LIMIT_REACHED','UPDATE_LIMIT_REACHED')
                              and residual>theta):
                            conv='PASS_NONCONVERGENCE'
                    except ValueError:
                        pass
                out['auditConvergenceCheck']=conv
                if conv=='FAIL':issues.append('convergence/horizon verification inconsistent')
                if r['validationState'] not in ('VERIFIED_SUCCESS','VERIFIED_NONCONVERGENCE'):
                    issues.append('unverified run status')
                if r['algorithm'] in ('POLICY_ITERATION','MODIFIED_POLICY_ITERATION') and conv=='PASS_CONVERGED':
                    if r['policyStable'].lower()!='true':
                        issues.append('policyStable not true')
                out['auditSummaryCheck']='PASS' if not stat_errors else 'FAIL'
                if stat_errors:issues.extend(stat_errors)
                for k, (meankey, sdkey) in STAT_FIELDS.items():
                    v=sstats.get(k)
                    if v:
                        out[meankey]=str(v['mean']) if v.get('mean') is not None else ''
                        out[sdkey]=str(v.get('sampleSD')) if v.get('sampleSD') is not None else ''
                out['auditPassed']=str(not issues).lower()
                out['auditIssues']='; '.join(dict.fromkeys(issues))
                outputs.append(out)
    if observed!=configs:
        raise RuntimeError(f'{archive.name}: missing configurations {configs-observed}')
    if len(outputs)!=len(configs)*expected_count:
        raise RuntimeError(f'{archive.name}: wrong number of audit lines')
    issues=[(r['auditRunId'],r['auditIssues']) for r in outputs if r['auditPassed']!='true']
    if issues:
        msg='; '.join(f'{rid}: {issue}' for rid,issue in issues[:7])
        raise RuntimeError(f'{archive.name}: {len(issues)} audit integrity errors: {msg}')
    return outputs


def generate_from_archives(repo, m, archives=None):
    byvariant = {}
    if archives is not None and len(archives)!=len(m['variants']):
        raise RuntimeError('wrong number of final ZIPs provided')
    for i, v in enumerate(m['variants']):
        source=(Path(archives[i]) if archives is not None else repo /
                f'{m["id"]}_{v["id"]}_final_results.zip')
        byvariant[v['id']]=(v,audit_zip(m,v,source))
    # Commit only when ALL variants are valid; never overwrite a completed audit with invalid data.
    files=[]
    for variant_id, (v,rows) in byvariant.items():
        path=repo / audited_filename(m,v)
        with tempfile.NamedTemporaryFile('w', encoding='utf-8', newline='',
                                         dir=repo, prefix='.audit-',suffix='.part',delete=False) as f:
            tmp=Path(f.name)
            w=csv.DictWriter(f,fieldnames=COLUMNS)
            w.writeheader();w.writerows(rows)
        tmp.replace(path)
        states=Counter(r['validationState'] for r in rows)
        print(f'AUDIT {path}: {len(rows)} runs, 80 columns, '
              f'outcomes={dict(states)}, integrity errors=0',flush=True)
        files.append(path)
    return files


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('campaign', help='Campaign ID, e.g., mountaincar or taxidriver')
    p.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[3])
    args=p.parse_args()
    try:
        repo=args.repo.expanduser().resolve()
        mp=repo/'scripts/experiments/offline/campaigns'/f'{args.campaign}.json'
        m=json.loads(mp.read_text(encoding='utf-8'))
        if m['id']!=args.campaign:
            raise ValueError('manifest id mismatch')
        generate_from_archives(repo,m)
    except (OSError,KeyError,ValueError,RuntimeError,json.JSONDecodeError) as e:
        print('AUDIT FAILED:',e,file=sys.stderr)
        return 1
    return 0


if __name__=='__main__':
    sys.exit(main())
