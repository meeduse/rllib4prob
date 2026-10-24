#!/usr/bin/env python3
"""Versioned, dependency-free campaign launcher for six offline RL algorithms."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

from summarize import collect

ALGORITHMS = {
    'VI': 'VALUE_ITERATION', 'VALUE_ITERATION': 'VALUE_ITERATION',
    'PI': 'POLICY_ITERATION', 'POLICY_ITERATION': 'POLICY_ITERATION',
    'MPI': 'MODIFIED_POLICY_ITERATION', 'MODIFIED_POLICY_ITERATION': 'MODIFIED_POLICY_ITERATION',
    'IVI': 'INCREMENTAL_VALUE_ITERATION', 'INCREMENTAL_VALUE_ITERATION': 'INCREMENTAL_VALUE_ITERATION',
    'PVI': 'PRIORITIZED_VALUE_ITERATION', 'PRIORITIZED_VALUE_ITERATION': 'PRIORITIZED_VALUE_ITERATION',
    'BI': 'BACKWARD_INDUCTION', 'BACKWARD_INDUCTION': 'BACKWARD_INDUCTION',
}


def project_root():
    return Path(__file__).resolve().parents[3]


def extract_environment_mapping(factory):
    text=factory.read_text(encoding='utf-8')
    case=re.compile(r'case\s+((?:"[^"\n]+"\s*,?\s*)+)\s*->\s*new\s+\w+\s*\(\s*"(/[^"\n]+\.mch)"',re.M)
    mapping={}
    for labels,path in case.findall(text):
        for name in re.findall(r'"([^"\n]+)"',labels):
            mapping[name.lower()]=path
    # Some older factory versions intentionally route TicTacToe via default.
    if 'tictactoe' not in mapping:
        fallback=re.search(r'default\s*->\s*new\s+TicTacToeEnvironment\s*\(\s*"(/[^"\n]+\.mch)"',text,re.M)
        if fallback:
            mapping['tictactoe']=fallback.group(1)
    return mapping


def run_version(command, root):
    try:
        process=subprocess.run(command,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                               text=True,timeout=25,check=False)
        return process.stdout.strip()
    except Exception as exc:
        return f'unavailable: {exc}'


def code_snapshot(root, out):
    """Frozen source artifact; do not duplicate result logs or local archives."""
    targets=[Path('pom.xml'),Path('src/main/java'),Path('src/main/resources'),Path('config/agent-parameters.properties'),
             Path('src/main/resources/agent-parameters.properties'),Path('scripts/experiments/offline')]
    sha=[]
    archive=out/'inputs'/'source_snapshot.tar.gz'
    archive.parent.mkdir(parents=True,exist_ok=True)
    with tarfile.open(archive,'w:gz') as tar:
        for rel in targets:
            path=root/rel
            if not path.exists():
                continue
            if path.is_file():
                files=[path]
            else:
                files=[p for p in path.rglob('*') if p.is_file() and not any(part.startswith('.') for part in p.relative_to(root).parts)]
            for file in sorted(files):
                name=file.relative_to(root).as_posix()
                # Avoid snapshotting generated class files, hidden data or unrelated material.
                if file.suffix in ('.class','.pyc'):
                    continue
                sha.append(f'{hashlib.sha256(file.read_bytes()).hexdigest()}  {name}')
                tar.add(file,arcname=name,recursive=False)
    (out/'input_sha256.txt').write_text('\n'.join(sha)+'\n',encoding='utf-8')
    return len(sha)


def build_classpath(root):
    print('Compiling once and resolving the runtime classpath...',flush=True)
    subprocess.run(['mvn','-q','test-compile'],cwd=root,check=True)
    relative='target/offline-benchmark-classpath.txt'
    subprocess.run(['mvn','-q','dependency:build-classpath','-Dmdep.includeScope=test',
                    f'-Dmdep.outputFile={relative}'],cwd=root,check=True)
    file=root/relative
    if not file.is_file() or not file.read_text().strip():
        raise RuntimeError('Maven classpath is empty')
    return os.pathsep.join([str(root/'target/classes'),str(root/'target/test-classes'),file.read_text().strip()])


def main():
    parser=argparse.ArgumentParser(description='Run one auditable campaign for one of six offline RL algorithms')
    parser.add_argument('--algorithm',required=True,choices=sorted(ALGORITHMS))
    parser.add_argument('--reward',choices=['ONCEANDFORALL','ONTHEFLY','EMBEDDED'],required=True)
    parser.add_argument('--exploration',choices=['PREPROCESS','RECURSIVE'],required=True)
    parser.add_argument('--environment',default='tictactoe',help='Exact EnvironmentFactory key, e.g. puzzle or mountaincar')
    parser.add_argument('--count',type=int,default=1,help='Number of runs; IVI uses different seeds, others repeat for timing (default 1)')
    parser.add_argument('--first-seed',type=int,default=0,help='First IVI seed (only applicable to IVI)')
    parser.add_argument('--expected-states',type=int,default=None,help='Optional expected accessible state count')
    parser.add_argument('--dry-run',action='store_true',help='Validate routing and print plan without compilation')
    args=parser.parse_args()
    if not (1 <= args.count <= 100) or not (0 <= args.first_seed <= 2**31-1-args.count):
        parser.error('count must be in 1..100; first-seed must be a nonnegative bounded integer')
    if args.expected_states is not None and args.expected_states <= 0:
        parser.error('--expected-states must be positive')
    algorithm=ALGORITHMS[args.algorithm]
    env=args.environment.lower()
    # For TicTacToe the reviewed B model is intentionally distinct in EMBEDDED mode.
    if env in {'tictactoe','tictacembedded'}:
        env='tictacembedded' if args.reward=='EMBEDDED' else 'tictactoe'
    elif args.reward=='EMBEDDED' and not env.endswith('embedded'):
        parser.error('Use the explicit *embedded EnvironmentFactory key for EMBEDDED reward mode')
    elif args.reward!='EMBEDDED' and env.endswith('embedded'):
        parser.error('Non-EMBEDDED reward must not run the embedded B machine')
    root=project_root()
    if not (root/'pom.xml').is_file():
        parser.error(f'Project root not found at {root}')
    factory=root/'src/main/java/fr/polytech/mnia/Environment/EnvironmentFactory.java'
    if not factory.exists():
        parser.error('EnvironmentFactory.java is missing')
    env_mapping=extract_environment_mapping(factory)
    if env not in env_mapping:
        parser.error(f'Unknown/unverified EnvironmentFactory key {env!r}; known: {", ".join(sorted(env_mapping))}')
    b_resource=env_mapping[env]
    b_path=root/'src/main/resources'/b_resource.lstrip('/')
    if not b_path.is_file():
        parser.error(f'EnvironmentFactory points to a missing B machine: {b_resource}')
    if algorithm=='INCREMENTAL_VALUE_ITERATION':
        algorithm_file=root/'src/main/java/fr/polytech/mnia/mbrl/offline/IncrementalValueIteration.java'
        if 'BFS_ACTIONS' not in algorithm_file.read_text(encoding='utf-8'):
            parser.error('IVI source does not contain canonical BFS_ACTIONS ordering')
    if args.dry_run:
        print(f'CHECK OK: algorithm={algorithm} reward={args.reward} exploration={args.exploration}')
        print(f'environment={env} B machine={b_resource} count={args.count}')
        print('IVI uses seeds; other algorithms produce independent repetitions, not seeded runs.')
        print('No experiment executed; no output created.')
        return 0
    if not all(shutil.which(t) for t in ['java','mvn']):
        parser.error('java and mvn must be on PATH')
    try:
        classpath=build_classpath(root)
    except (subprocess.CalledProcessError,RuntimeError) as exc:
        print('Cannot start campaign: compilation/classpath failure:',exc,file=sys.stderr)
        return 2
    folder=root/'experiments'/'offline'
    folder.mkdir(parents=True,exist_ok=True)
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    slug=f'{algorithm.lower()}_{env}_{args.reward.lower()}_{args.exploration.lower()}_{stamp}_'
    out=Path(tempfile.mkdtemp(prefix=slug,dir=folder))
    (out/'logs').mkdir(exist_ok=True)
    num_sources=code_snapshot(root,out)
    git_root=run_version(['git','rev-parse','--show-toplevel'],root)
    protocol={
        'schemaVersion':'offline-common-v1','startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
        'algorithm':algorithm,'environment':env,'rewardStrategy':args.reward,
        'explorationStrategy':args.exploration,'bMachine':b_resource,'count':args.count,
        'firstSeed':args.first_seed if algorithm=='INCREMENTAL_VALUE_ITERATION' else None,
        'seedPolicy':'explicit seeded Fisher-Yates/BFS_ACTIONS (IVI only)' if algorithm=='INCREMENTAL_VALUE_ITERATION' else 'repeated independent JVM runs; no PRNG seed asserted',
        'ordering':'BFS_ACTIONS' if algorithm=='INCREMENTAL_VALUE_ITERATION' else None,
        'expectedStates':args.expected_states,'gitRoot':git_root,
        'gitPrefix':run_version(['git','rev-parse','--show-prefix'],root),
        'gitCommit':run_version(['git','rev-parse','HEAD'],root),
        'gitStatus':run_version(['git','status','--porcelain','--untracked-files=normal','--','.'],root),
        'javaVersion':run_version(['java','-version'],root),
        'mavenVersion':run_version(['mvn','-version'],root),
        'platform':platform.platform(),'architecture':platform.machine(),
        'pythonVersion':sys.version,'sourceFilesInSnapshot':num_sources,
        'resultCriteria':{'BI':'completedHorizon == horizon > 0, status HORIZON_COMPLETED',
                          'others':'status CONVERGED and finite global Bellman residual <= theta; policy stable/residual for PI/MPI'},
        'notes':'All code, B machines, and config captured before runs; values parsed from algorithm result lines. '
                'Times are wall-clock algorithm times reported by Java and exclude Maven compilation.'
    }
    (out/'protocol.json').write_text(json.dumps(protocol,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(f'\nNew campaign: {out}\nSnapshot: {num_sources} source/config files',flush=True)
    runs=[]
    for i in range(args.count):
        seed=args.first_seed+i if algorithm=='INCREMENTAL_VALUE_ITERATION' else None
        item={'run':i,'seed':seed,'logFile':f'logs/run_{i:03d}.log'}
        runs.append(item)
        (out/'runs.json').write_text(json.dumps(runs,indent=2)+'\n',encoding='utf-8')
        args_java=['java']
        if seed is not None:
            args_java.extend([f'-Drllib4prob.ivi.seed={seed}','-Drllib4prob.ivi.ordering=BFS_ACTIONS'])
        args_java.extend(['-cp',classpath,'fr.polytech.mnia.App',algorithm,args.reward,args.exploration,env])
        label=f'seed {seed}' if seed is not None else f'repetition {i+1}'
        print(f'\n========== {algorithm} {label} ({i+1}/{args.count}) ==========',flush=True)
        output_file=out/item['logFile']
        exit_code=1
        try:
            with output_file.open('w',encoding='utf-8') as log:
                with subprocess.Popen(args_java,cwd=root,stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT,text=True,errors='replace',bufsize=1) as proc:
                    try:
                        for line in proc.stdout:
                            print(line,end='',flush=True)
                            log.write(line)
                            log.flush()
                        exit_code=proc.wait()
                    except KeyboardInterrupt:
                        proc.terminate()
                        proc.wait()
                        exit_code=130
                log.write(f'RUN_EXIT_CODE={exit_code}\n')
        except OSError as exc:
            with output_file.open('a',encoding='utf-8') as log:
                log.write(f'LAUNCH_ERROR={exc}\nRUN_EXIT_CODE=127\n')
        rows,summary=collect(out)
        last=rows[-1]
        print(f'CHECK run {i}: status={last["status"]} verification={last["validationState"]}'
              + (f' issue={last["issue"]}' if last['issue'] else ''),flush=True)
        if exit_code==130:
            print('Interrupted; partial results preserved in:',out)
            return 130
    # Display concise results; full statistics remain in summary.json.
    print(f'\nCompleted: {summary["successfulRuns"]}/{summary["totalRuns"]} verified successful runs')
    print('Breakdown:',summary['validationStates'])
    for k in ('totalSeconds','learningSeconds','explorationSeconds','totalBackups'):
        if k in summary['statisticsOnVerifiedSuccessOnly']:
            v=summary['statisticsOnVerifiedSuccessOnly'][k]
            print(f'{k}: mean={v["mean"]:.6f} sample_sd={v["sampleSD"] if v["sampleSD"] is not None else "n/a"}')
    print('CSV:',out/'results.csv')
    print('Summary:',out/'summary.json')
    print('All raw logs preserved in:',out/'logs')
    return 0 if all(r['validationState'] not in ('INVALID','INCOMPLETE') for r in rows) else 1


if __name__=='__main__':
    sys.exit(main())
