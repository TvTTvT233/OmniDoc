"""Public, allowlisted evaluation catalogue. Never copy raw logs or XML properties."""
import argparse
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import re
import sys

import evaluate as ev

RUN_ID = r'\d{8}T\d{6}Z-[a-f0-9]{8}'
REASONS = {None, 'NO_FRESH_TEST_RESULT', 'MISSING_OR_DUPLICATE_TESTCASE',
           'ASSERTION_FAILED', 'TEST_EXECUTION_ERROR', 'TEST_SKIPPED', 'INVALID_TEST_XML'}
REPORT_FIELDS = {'schemaVersion', 'runId', 'mode', 'createdAtUtc', 'code', 'dataset',
                 'grader', 'executionConfig', 'repeats', 'infrastructureFailures',
                 'usage', 'cases', 'caseSetDigest', 'elapsedSeconds', 'summary'}


def hex_value(value, length):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{' + str(length) + '}', value)


def number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def timestamp(value):
    ev.require(isinstance(value, str) and len(value) <= 40, 'INVALID_TIMESTAMP')
    parsed = dt.datetime.fromisoformat(value)
    ev.require(parsed.utcoffset() == dt.timedelta(0), 'INVALID_TIMESTAMP')
    return parsed.isoformat()


def project(report, key, definitions, dataset, evidence=None):
    """Validate before projecting; acceptance text and unknown fields never escape."""
    baseline = key.startswith('baseline:')
    ev.require(set(report) == REPORT_FIELDS | ({'acceptance'} if baseline else set()), 'UNEXPECTED_FIELDS')
    ev.validate_report(report)
    ev.require(re.fullmatch(RUN_ID, report['runId']), 'INVALID_RUN_ID')
    ev.require(1 <= report['repeats'] <= 10, 'INVALID_REPEATS')
    recorded_dataset = report['dataset']
    ev.require(set(recorded_dataset) == {'versions', 'digest', 'fixtureDigest'}
               and recorded_dataset['versions'] == dataset['versions']
               and recorded_dataset['digest'] == dataset['digest']
               and hex_value(recorded_dataset['fixtureDigest'], 64), 'UNSUPPORTED_DATASET')
    ev.require(number(report['elapsedSeconds']), 'INVALID_DURATION')
    code = report['code']
    ev.require(set(code) == {'commit', 'dirty', 'treeDigest'} and hex_value(code['commit'], 40)
               and hex_value(code['treeDigest'], 64) and type(code['dirty']) is bool, 'INVALID_CODE')
    grader = report['grader']
    ev.require(set(grader) == {'version', 'digest'} and grader['version'] == ev.GRADER_VERSION
               and hex_value(grader['digest'], 64), 'INVALID_GRADER')
    config = report['executionConfig']
    ev.require(config == {'models': 'DISABLED_TEST_DOUBLES', 'promptVersion': None,
                          'retrievalConfig': 'CASE_FIXTURES', 'runnerOs': config.get('runnerOs')}
               and config['runnerOs'] in {'Windows', 'Linux', 'Darwin'}, 'INVALID_CONFIG')
    ev.require(report['usage'] == {'inputTokens': None, 'outputTokens': None, 'cost': None,
                                  'reason': 'NOT_MEASURED_NO_REAL_MODEL'}, 'INVALID_USAGE')
    failures = report['infrastructureFailures']
    ev.require(isinstance(failures, list) and len(failures) <= 31, 'INVALID_INFRASTRUCTURE')
    for failure in failures:
        ev.require(failure == {'reason': 'CODE_CHANGED_DURING_RUN'} or (
            set(failure) == {'module', 'trial', 'exitCode'} and failure['module'] in ev.MODULES
            and type(failure['trial']) is int and 1 <= failure['trial'] <= report['repeats']
            and type(failure['exitCode']) is int and failure['exitCode'] != 0), 'INVALID_INFRASTRUCTURE')
    if baseline:
        acceptance = report['acceptance']
        ev.require(set(acceptance) == {'reviewedBy', 'reason', 'acceptedAtUtc', 'reportDigest'}, 'INVALID_ACCEPTANCE')
        timestamp(acceptance['acceptedAtUtc'])
        ev.compare(report, report)  # Integrity check, not a digital signature.
    else:
        ev.require(evidence is not None and evidence.name == report['runId'], 'EVIDENCE_REQUIRED')
    ev.require(len(report['cases']) == len(definitions), 'INCOMPLETE_CASES')
    projected_cases = []
    for expected, actual in zip(definitions, report['cases']):
        ev.require(set(actual) == ev.CASE_FIELDS | {'trials', 'productionRunId'}, 'UNEXPECTED_CASE_FIELDS')
        ev.require({k: actual[k] for k in ev.CASE_FIELDS} == expected
                   and actual['productionRunId'] is None, 'CASE_DEFINITION_CHANGED')
        trials = []
        for trial in actual['trials']:
            ev.require(set(trial) == {'trial', 'status', 'reason', 'durationMs', 'evidenceSha256'}, 'UNEXPECTED_TRIAL_FIELDS')
            ev.require(trial['reason'] in REASONS and (trial['evidenceSha256'] is None
                       or hex_value(trial['evidenceSha256'], 64)), 'INVALID_TRIAL')
            if not baseline:
                a = expected['adapter']
                xml = evidence / 'xml' / str(trial['trial']) / a['module'] / ('TEST-' + a['className'] + '.xml')
                actual_sha = hashlib.sha256(xml.read_bytes()).hexdigest() if xml.is_file() else None
                ev.require(actual_sha == trial['evidenceSha256'], 'EVIDENCE_DIGEST_MISMATCH')
                ev.require(ev.parse_results(xml, a['className'], a['method']) ==
                           {k: trial[k] for k in ('status', 'reason', 'durationMs')}, 'EVIDENCE_RESULT_MISMATCH')
            trials.append({k: trial[k] for k in ('trial', 'status', 'reason', 'durationMs')})
        projected_cases.append({'id': expected['id'], 'trials': trials})
    return {'key': key, 'kind': 'baseline' if baseline else 'run', 'runId': report['runId'],
            'createdAtUtc': timestamp(report['createdAtUtc']), 'code': dict(code),
            'dataset': {k: recorded_dataset[k] for k in ('digest', 'fixtureDigest')}, 'grader': dict(grader),
            'runnerOs': config['runnerOs'], 'repeats': report['repeats'],
            'elapsedSeconds': report['elapsedSeconds'], 'summary': report['summary'],
            'infrastructureFailureCount': len(failures), 'cases': projected_cases}


def build(run_id=None, limit=50):
    definitions, dataset = ev.load_dataset()
    ev.require(type(limit) is int and 1 <= limit <= 100, 'INVALID_LIMIT')
    ev.require(run_id is None or re.fullmatch(RUN_ID, run_id), 'INVALID_RUN_ID')
    runs, raw, omitted = [], {}, 0
    baselines = sorted((ev.ROOT / 'evals/baselines').glob('*.json'))
    local = ev.ROOT / '.local/evals'
    paths = ([local / run_id / 'report.json'] if run_id else
             sorted((p / 'report.json' for p in local.iterdir()
                     if p.is_dir() and re.fullmatch(RUN_ID, p.name) and (p / 'report.json').is_file()),
                    reverse=True)[:limit]) if local.exists() else []
    for path in baselines + paths:
        is_baseline = path in baselines
        try:
            ev.require(path.stat().st_size <= 2_000_000, 'REPORT_TOO_LARGE')
            ev.require(not path.is_symlink(), 'LINK_NOT_ALLOWED')
            if is_baseline:
                ev.require(re.fullmatch('[A-Za-z0-9_-]{1,100}', path.stem), 'INVALID_BASELINE_NAME')
            key = ('baseline:' + path.stem) if is_baseline else ('run:' + path.parent.name)
            report = ev.read(path)
            runs.append(project(report, key, definitions, dataset, None if is_baseline else path.parent))
            raw[key] = report
        except (ValueError, KeyError, TypeError, OSError, OverflowError):
            omitted += 1  # Do not disclose paths, exception messages or offending content.
    ev.require(run_id is None or 'run:' + run_id in raw, 'REQUESTED_RUN_REJECTED')
    comparisons = []
    for baseline in runs:
        if baseline['kind'] != 'baseline':
            continue
        for candidate in runs:
            if candidate['kind'] != 'run':
                continue
            entry = {'baselineKey': baseline['key'], 'candidateKey': candidate['key']}
            try:
                compared = ev.compare(raw[baseline['key']], raw[candidate['key']])
                entry.update(gate=compared['gate'], reason=None, changes=compared['changes'])
            except ValueError:
                entry.update(gate='BLOCKED', reason='INCOMPATIBLE_CONFIGURATION', changes=[])
            comparisons.append(entry)
    return {'schemaVersion': 1, 'mode': 'deterministic-components',
            'generatedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'omittedReports': omitted,
            'cases': [{k: c[k] for k in ('id', 'suiteId', 'title', 'task', 'expectedOutcome', 'critical', 'evidenceLevel')}
                      for c in definitions], 'runs': runs, 'comparisons': comparisons}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['export'])
    parser.add_argument('--output', required=True)
    parser.add_argument('--run-id')
    parser.add_argument('--limit', type=int, default=50)
    args = parser.parse_args()
    try:
        output = Path(args.output).resolve()
        # Prevent accidental overwrites of source reports, baselines or arbitrary files.
        ev.require(output in {(ev.ROOT / '.local/evaluation-center/catalog.json').resolve(),
                              (ev.ROOT / 'web/public/evals/catalog.json').resolve()}, 'INVALID_OUTPUT')
        result = build(args.run_id, args.limit)
        ev.write(output, result)
        print(json.dumps({'runs': len(result['runs']), 'omittedReports': result['omittedReports']}))
        return 0
    except (ValueError, KeyError, TypeError, OSError, OverflowError) as error:
        print(json.dumps({'gate': 'BLOCKED', 'reason': type(error).__name__}), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
