"""Deterministic task evaluation. Standard library only; no model/network SDK.

JUnit adapters execute real production components with explicitly declared fixtures.
This is not a live-model, HTTP end-to-end, or billing evaluation.
"""
import argparse
import collections
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
GRADER_VERSION = 'deterministic-junit-v1'
REPORT_VERSION = 1
MODULES = {'ai-service', 'document-service', 'file-service'}
CASE_FIELDS = {'id', 'title', 'task', 'initialState', 'expectedOutcome', 'externalDependencies',
               'evidenceLevel', 'critical', 'forbiddenBehaviors', 'adapter', 'tags', 'suiteId'}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':')).encode()).hexdigest()


def source_digest(path):
    data = Path(path).read_bytes()
    try:
        # Git checkout may translate LF/CRLF; that is not a new fixture or grader.
        text = data.decode('utf-8')
        if '\x00' not in text:
            data = text.replace('\r\n', '\n').encode('utf-8')
    except UnicodeDecodeError:
        pass
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT).decode('utf-8').strip()


def load_dataset():
    cases, suite_versions, fixtures = [], {}, {}
    for path in sorted((ROOT / 'evals/suites').glob('*.json')):
        suite = read(path)
        require(suite['suiteId'] not in suite_versions, 'DUPLICATE_SUITE')
        suite_versions[suite['suiteId']] = suite['version']
        for case in suite['cases']:
            for field in ('id', 'title', 'task', 'initialState', 'expectedOutcome',
                          'externalDependencies', 'evidenceLevel'):
                require(bool(case.get(field)), 'CASE_FIELD_MISSING')
            require(type(case.get('critical')) is bool, 'CRITICAL_FLAG_REQUIRED')
            require(isinstance(case.get('forbiddenBehaviors'), list), 'FORBIDDEN_BEHAVIORS_REQUIRED')
            a = case['adapter']
            require(a['module'] in MODULES, 'INVALID_MODULE')
            require(re.fullmatch(r'[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+', a['className']), 'INVALID_CLASS')
            require(re.fullmatch(r'[A-Za-z_][\w]*', a['method']), 'INVALID_METHOD')
            source = ROOT / a['module'] / 'src/test/java' / (a['className'].replace('.', '/') + '.java')
            require(source.is_file(), 'ADAPTER_SOURCE_MISSING')
            require(re.search(r'\b' + re.escape(a['method']) + r'\s*\(', source.read_text(encoding='utf-8')), 'ADAPTER_METHOD_MISSING')
            fixtures[source.relative_to(ROOT).as_posix()] = source_digest(source)
            cases.append(dict(case, suiteId=suite['suiteId']))
    require(bool(cases), 'EMPTY_DATASET')
    require(collections.Counter(c['suiteId'] for c in cases) == {'agent': 10, 'document': 10, 'review': 10}, 'INCOMPLETE_FROZEN_DATASET')
    require(len({c['id'] for c in cases}) == len(cases), 'DUPLICATE_CASE')
    require(len({tuple(c['adapter'][k] for k in ('module', 'className', 'method')) for c in cases}) == len(cases), 'DUPLICATE_ADAPTER')
    # Include indirect test helpers and synthetic resources, not just adapter entry classes.
    for module in sorted({c['adapter']['module'] for c in cases}):
        for source in sorted((ROOT / module / 'src/test').rglob('*')):
            if source.is_file():
                fixtures[source.relative_to(ROOT).as_posix()] = source_digest(source)
    return cases, {'versions': suite_versions, 'digest': digest(cases), 'fixtureDigest': digest(fixtures)}


def parse_results(xml_path, class_name, method):
    if not xml_path.is_file():
        return {'status': 'MISSING', 'reason': 'NO_FRESH_TEST_RESULT', 'durationMs': None}
    try:
        root = ET.parse(xml_path).getroot()
        found = [t for t in root.iter('testcase') if t.get('classname') == class_name and t.get('name') == method]
        if len(found) != 1:
            return {'status': 'ERROR', 'reason': 'MISSING_OR_DUPLICATE_TESTCASE', 'durationMs': None}
        case = found[0]
        seconds = float(case.get('time', '0'))
        require(math.isfinite(seconds) and seconds >= 0, 'INVALID_DURATION')
        status = 'SKIPPED' if case.find('skipped') is not None else (
            'ERROR' if case.find('error') is not None else ('FAILED' if case.find('failure') is not None else 'PASSED'))
        # Never copy exception messages, system-out, request bodies or stack traces into public reports.
        return {'status': status, 'reason': {'PASSED': None, 'FAILED': 'ASSERTION_FAILED',
                'ERROR': 'TEST_EXECUTION_ERROR', 'SKIPPED': 'TEST_SKIPPED'}[status],
                'durationMs': round(seconds * 1000, 3)}
    except (ET.ParseError, ValueError):
        return {'status': 'ERROR', 'reason': 'INVALID_TEST_XML', 'durationMs': None}


def summarize(cases, infrastructure):
    passed = sum(all(t['status'] == 'PASSED' for t in c['trials']) for c in cases)
    blockers = [c['id'] for c in cases if c['critical'] and any(t['status'] != 'PASSED' for t in c['trials'])]
    return {'total': len(cases), 'passed': passed, 'passRate': passed / len(cases) if cases else 0,
            'criticalFailures': blockers, 'gate': 'PASS' if passed == len(cases) and not infrastructure else 'FAIL'}


def validate_report(report):
    require(report.get('schemaVersion') == REPORT_VERSION, 'INVALID_REPORT_VERSION')
    require(report.get('mode') == 'deterministic-components', 'INCOMPATIBLE_MODE')
    cases = report.get('cases', [])
    repeats = report.get('repeats')
    require(type(repeats) is int and repeats > 0, 'INVALID_REPEATS')
    require(cases and len({c['id'] for c in cases}) == len(cases), 'INVALID_CASE_SET')
    for case in cases:
        require(type(case['critical']) is bool and len(case['trials']) == repeats, 'INCOMPLETE_TRIALS')
        require([t['trial'] for t in case['trials']] == list(range(1, repeats + 1)), 'INVALID_TRIAL_SEQUENCE')
        for trial in case['trials']:
            require(trial['status'] in {'PASSED', 'FAILED', 'ERROR', 'SKIPPED', 'MISSING'}, 'INVALID_STATUS')
            value = trial['durationMs']
            require(value is None or (type(value) in (int, float) and math.isfinite(value) and value >= 0), 'INVALID_DURATION')
    require(report['summary'] == summarize(cases, report['infrastructureFailures']), 'INVALID_SUMMARY')
    require(report['caseSetDigest'] == digest([{k: c[k] for k in ('id', 'suiteId', 'critical', 'adapter')} for c in cases]), 'INVALID_CASE_DIGEST')


def compare(baseline, candidate):
    validate_report(baseline)
    validate_report(candidate)
    acceptance = baseline.get('acceptance', {})
    require(bool(acceptance.get('reviewedBy')) and bool(acceptance.get('reason')), 'BASELINE_NOT_REVIEWED')
    require(acceptance.get('reportDigest') == digest({k: v for k, v in baseline.items() if k != 'acceptance'}), 'BASELINE_CHANGED_AFTER_REVIEW')
    require(baseline['summary']['gate'] == 'PASS', 'BASELINE_NOT_PASSING')
    for field in ('mode', 'dataset', 'grader', 'caseSetDigest', 'executionConfig'):
        require(baseline[field] == candidate[field], 'INCOMPATIBLE_' + field.upper())
    before = {c['id']: c for c in baseline['cases']}
    changes = []
    for case in candidate['cases']:
        old = before[case['id']]
        old_rate = sum(t['status'] == 'PASSED' for t in old['trials']) / len(old['trials'])
        new_rate = sum(t['status'] == 'PASSED' for t in case['trials']) / len(case['trials'])
        durations = [t['durationMs'] for t in case['trials'] if t['durationMs'] is not None]
        old_durations = [t['durationMs'] for t in old['trials'] if t['durationMs'] is not None]
        changes.append({'id': case['id'], 'baselinePassRate': old_rate, 'candidatePassRate': new_rate,
                        'regressed': new_rate < old_rate,
                        'meanDurationDeltaMs': (sum(durations)/len(durations) - sum(old_durations)/len(old_durations))
                        if durations and old_durations else None})
    return {'schemaVersion': 1, 'baselineRunId': baseline['runId'], 'candidateRunId': candidate['runId'],
            'gate': 'FAIL' if candidate['summary']['gate'] != 'PASS' or any(c['regressed'] for c in changes) else 'PASS',
            'changes': changes, 'timingPolicy': 'INFORMATIONAL_ONLY_COMPONENT_TEST_TIMES_NOT_SERVICE_LATENCY',
            'infrastructureFailures': candidate['infrastructureFailures']}


def tree_digest():
    files = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT).decode().split('\0')
    return digest({p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in sorted(set(files)) if p and (ROOT / p).is_file()})


def execute(args):
    cases, dataset = load_dataset()
    require(1 <= args.repeats <= 10, 'REPEATS_OUT_OF_RANGE')
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    out = ROOT / '.local/evals' / run_id
    out.mkdir(parents=True)
    lock = ROOT / '.local/evals/runner.lock'
    with lock.open('x') as handle:
        handle.write(str(os.getpid()))
    try:
        report = {'schemaVersion': REPORT_VERSION, 'runId': run_id, 'mode': 'deterministic-components',
                  'createdAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
                  'code': {'commit': git('rev-parse', 'HEAD'), 'dirty': bool(git('status', '--porcelain')), 'treeDigest': tree_digest()},
                  'dataset': dataset, 'grader': {'version': GRADER_VERSION, 'digest': source_digest(__file__)},
                  'executionConfig': {'models': 'DISABLED_TEST_DOUBLES', 'promptVersion': None, 'retrievalConfig': 'CASE_FIXTURES',
                                      'runnerOs': platform.system()},
                  'repeats': args.repeats, 'infrastructureFailures': [],
                  'usage': {'inputTokens': None, 'outputTokens': None, 'cost': None, 'reason': 'NOT_MEASURED_NO_REAL_MODEL'},
                  'cases': [dict(c, trials=[], productionRunId=None) for c in cases]}
        report['caseSetDigest'] = digest([{k: c[k] for k in ('id', 'suiteId', 'critical', 'adapter')} for c in report['cases']])
        env = os.environ.copy()
        for name in list(env):
            if name.startswith(('DASHSCOPE', 'OPENAI', 'TAVILY', 'SPRING_AI', 'AI_MODELS')):
                del env[name]
        start = time.monotonic()
        for trial in range(1, args.repeats + 1):
            for module in sorted({c['adapter']['module'] for c in cases}):
                selected = [c for c in report['cases'] if c['adapter']['module'] == module]
                selectors = collections.defaultdict(list)
                for case in selected:
                    selectors[case['adapter']['className']].append(case['adapter']['method'])
                evidence_dir = out / 'xml' / str(trial)
                paths = {clazz: evidence_dir / module / ('TEST-' + clazz + '.xml') for clazz in selectors}
                selector = ','.join(c + '#' + '+'.join(sorted(methods)) for c, methods in sorted(selectors.items()))
                command = [str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')), '-s', '.mvn/settings-local.xml',
                           '-pl', module, '-am', '-Ptask-evaluation', '-Domnidoc.eval.reportsDirectory=' + str(evidence_dir),
                           '-Dtest=' + selector, '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
                log = out / f'{module}-trial-{trial}.private.log'
                print(f'Running {module}, trial {trial}/{args.repeats} ({len(selected)} cases)', flush=True)
                with log.open('wb') as handle:
                    try:
                        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT,
                                                   start_new_session=os.name != 'nt')
                        try:
                            code = process.wait(timeout=args.timeout)
                        except subprocess.TimeoutExpired:
                            if os.name == 'nt':
                                subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                            else:
                                os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
                            code = 124
                    except OSError:
                        code = 125
                if code:
                    report['infrastructureFailures'].append({'module': module, 'trial': trial, 'exitCode': code})
                for case in selected:
                    a = case['adapter']
                    result = parse_results(paths[a['className']], a['className'], a['method'])
                    result['evidenceSha256'] = hashlib.sha256(paths[a['className']].read_bytes()).hexdigest() if paths[a['className']].exists() else None
                    case['trials'].append(dict(result, trial=trial))
        # Detect concurrent edits rather than claiming results belong to a different source snapshot.
        if tree_digest() != report['code']['treeDigest'] or git('rev-parse', 'HEAD') != report['code']['commit']:
            report['infrastructureFailures'].append({'reason': 'CODE_CHANGED_DURING_RUN'})
        report['elapsedSeconds'] = round(time.monotonic() - start, 3)
        report['summary'] = summarize(report['cases'], report['infrastructureFailures'])
        validate_report(report)
        write(out / 'report.json', report)
        lines = ['# Deterministic evaluation', '', f"Gate: {report['summary']['gate']}", '',
                 '| Case | Result | Trials passed |', '|---|---|---|']
        for case in report['cases']:
            passed = sum(t['status'] == 'PASSED' for t in case['trials'])
            lines.append(f"| {case['id']} {case['title']} | {'PASS' if passed == args.repeats else 'FAIL'} | {passed}/{args.repeats} |")
        (out / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
        if args.baseline:
            comparison = compare(read(args.baseline), report)
            write(out / 'comparison.json', comparison)
        print(json.dumps({'report': str(out / 'report.json'), **report['summary']}, ensure_ascii=False))
        return 0 if report['summary']['gate'] == 'PASS' else 1
    finally:
        lock.unlink(missing_ok=True)


def verified_local_report(path):
    path = Path(path).resolve()
    require(path.name == 'report.json' and path.parent.parent == (ROOT / '.local/evals').resolve(), 'NOT_LOCAL_RUN_REPORT')
    report = read(path)
    validate_report(report)
    require(report.get('runId') == path.parent.name, 'RUN_ID_MISMATCH')
    require(re.fullmatch(r'\d{8}T\d{6}Z-[a-f0-9]{8}', report['runId']), 'INVALID_RUN_ID')
    require(report['summary']['gate'] == 'PASS', 'CANNOT_ACCEPT_FAILED_RUN')
    cases, dataset = load_dataset()
    require(report['dataset'] == dataset, 'BASELINE_DATASET_MISMATCH')
    require(report['grader'] == {'version': GRADER_VERSION, 'digest': source_digest(__file__)}, 'BASELINE_GRADER_MISMATCH')
    require(set(report) == {'schemaVersion', 'runId', 'mode', 'createdAtUtc', 'code', 'dataset', 'grader',
                           'executionConfig', 'repeats', 'infrastructureFailures', 'usage', 'cases', 'caseSetDigest',
                           'elapsedSeconds', 'summary'}, 'UNEXPECTED_REPORT_FIELDS')
    require(report['executionConfig'] == {'models': 'DISABLED_TEST_DOUBLES', 'promptVersion': None,
                                          'retrievalConfig': 'CASE_FIXTURES', 'runnerOs': platform.system()}, 'INVALID_EXECUTION_CONFIG')
    require(report['usage'] == {'inputTokens': None, 'outputTokens': None, 'cost': None, 'reason': 'NOT_MEASURED_NO_REAL_MODEL'}, 'INVALID_USAGE')
    require(set(report['code']) == {'commit', 'dirty', 'treeDigest'}, 'INVALID_CODE_METADATA')
    require(re.fullmatch('[a-f0-9]{40}', report['code']['commit']) and
            re.fullmatch('[a-f0-9]{64}', report['code']['treeDigest']) and type(report['code']['dirty']) is bool, 'INVALID_CODE_METADATA')
    dt.datetime.fromisoformat(report['createdAtUtc'])
    require(type(report['elapsedSeconds']) in (int, float) and math.isfinite(report['elapsedSeconds']) and report['elapsedSeconds'] >= 0, 'INVALID_DURATION')
    require(len(report['cases']) == len(cases), 'INCOMPLETE_FROZEN_DATASET')
    for expected, actual in zip(cases, report['cases']):
        require(set(actual) == CASE_FIELDS | {'trials', 'productionRunId'}, 'UNEXPECTED_CASE_FIELDS')
        require({k: actual[k] for k in CASE_FIELDS} == expected and actual['productionRunId'] is None, 'CASE_DEFINITION_CHANGED')
        for trial in actual['trials']:
            require(set(trial) == {'trial', 'status', 'reason', 'durationMs', 'evidenceSha256'}, 'UNEXPECTED_TRIAL_FIELDS')
            a = actual['adapter']
            xml = path.parent / 'xml' / str(trial['trial']) / a['module'] / ('TEST-' + a['className'] + '.xml')
            require(xml.is_file() and trial['evidenceSha256'] == hashlib.sha256(xml.read_bytes()).hexdigest(), 'EVIDENCE_DIGEST_MISMATCH')
            require(parse_results(xml, a['className'], a['method']) == {k: trial[k] for k in ('status', 'reason', 'durationMs')}, 'EVIDENCE_RESULT_MISMATCH')
    return report


def accept(args):
    report = verified_local_report(args.report)
    require(re.fullmatch(r'[a-zA-Z0-9_-]+', args.name), 'INVALID_BASELINE_NAME')
    require(0 < len(args.reviewed_by.strip()) <= 80 and 0 < len(args.reason.strip()) <= 300, 'REVIEW_REQUIRED')
    require(not re.search(r'(?:sk-|tvly-|ghp_|github_pat_|eyJ)[A-Za-z0-9_-]{12,}', args.reviewed_by + args.reason), 'SENSITIVE_REVIEW_TEXT')
    destination = ROOT / 'evals/baselines' / (args.name + '.json')
    report['acceptance'] = {'reviewedBy': args.reviewed_by, 'reason': args.reason,
                            'acceptedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'reportDigest': digest(report)}
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive create, not exists-then-write: concurrent acceptance cannot replace a baseline.
    with destination.open('x', encoding='utf-8') as handle:
        handle.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Accepted immutable baseline: ' + str(destination))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('validate')
    run = commands.add_parser('run')
    run.add_argument('--repeats', type=int, default=1)
    run.add_argument('--timeout', type=int, default=900)
    run.add_argument('--baseline')
    comp = commands.add_parser('compare')
    comp.add_argument('--baseline', required=True)
    comp.add_argument('--candidate', required=True)
    comp.add_argument('--output', required=True)
    approval = commands.add_parser('accept')
    approval.add_argument('--report', required=True)
    approval.add_argument('--name', required=True)
    approval.add_argument('--reviewed-by', required=True)
    approval.add_argument('--reason', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'validate':
            cases, meta = load_dataset()
            print(json.dumps({'cases': len(cases), 'dataset': meta}))
            return 0
        if args.command == 'run':
            return execute(args)
        if args.command == 'accept':
            return accept(args)
        result = compare(read(args.baseline), read(args.candidate))
        output = Path(args.output).resolve()
        require((ROOT / 'evals/baselines').resolve() not in output.parents, 'BASELINE_OUTPUT_FORBIDDEN')
        require(output not in {Path(args.baseline).resolve(), Path(args.candidate).resolve()}, 'INPUT_OUTPUT_COLLISION')
        write(output, result)
        print(json.dumps({'gate': result['gate'], 'regressions': [c['id'] for c in result['changes'] if c['regressed']]}))
        return 0 if result['gate'] == 'PASS' else 1
    except (ValueError, KeyError, TypeError, AttributeError, IndexError, OSError) as error:
        # Deliberately don't echo arbitrary JSON or filesystem exception messages.
        code = str(error) if isinstance(error, ValueError) and re.fullmatch('[A-Z_]+', str(error)) else type(error).__name__
        print(json.dumps({'gate': 'BLOCKED', 'reason': code}), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
