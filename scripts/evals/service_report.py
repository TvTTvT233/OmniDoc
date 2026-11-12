"""Strict service-flow report validation and separately reviewed baselines."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import math
import service_flows as flows

CHECKS = {
 'document': ['parse-exact-text', 'initial-index-ready', 'old-generation-never-queryable-after-save', 'historical-reference-exact', 'new-version-published', 'new-segments-version', 'reference-owner-isolated'],
 'review': ['review-worker-success', 'missing-material-detected', 'dual-evidence-present', 'old-report-outdated', 'review-worker-success', 'supplement-clears-findings', 'report-owner-isolated'],
 'isolation': ['deterministic-agent-completed', 'duplicate-same-run', 'payload-conflict', 'task-owner-isolated', 'durable-jdbc-result'],
 'restart': ['approval-required', 'approval-persisted-before-restart', 'different-process-same-artifact', 'same-approval-restored', 'approval-cancelled', 'cancelled-state-persisted', 'late-confirm-no-write']}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def validate(report):
    flows.require(set(report) == {'schemaVersion', 'mode', 'runId', 'createdAtUtc', 'commit', 'dirty', 'contract', 'modelBoundary', 'usage', 'cases', 'gate'})
    flows.require(type(report['dirty']) is bool)
    flows.require(set(report['contract']) == {'runnerOs', 'digest'} and report['contract']['runnerOs'] in {'Windows', 'Linux', 'Darwin'} and re.fullmatch('[a-f0-9]{64}', report['contract']['digest']))
    flows.require(report['schemaVersion'] == 1 and report['mode'] == 'service-flows-v1')
    flows.require(re.fullmatch(r'\d{8}T\d{6}Z-[a-f0-9]{8}', report['runId']))
    flows.require(re.fullmatch('[a-f0-9]{40}', report['commit']))
    flows.require(len(report['createdAtUtc']) < 40 and dt.datetime.fromisoformat(report['createdAtUtc']).utcoffset() == dt.timedelta(0))
    flows.require(report['modelBoundary'] == 'DISABLED_WITH_SYNTHETIC_EMBEDDING' and report['usage'] is None)
    flows.require(len(report['cases']) == 4)
    for c, (case_id, title) in zip(report['cases'], flows.DEFINITIONS):
        flows.require(set(c) == {'id', 'title', 'status', 'reason', 'durationMs', 'checks', 'productionRunIds', 'runIdPolicy'})
        flows.require(c['id'] == case_id and c['title'] == title and c['status'] in {'PASS', 'FAIL', 'BLOCKED'})
        flows.require(c['reason'] == {'PASS': None, 'FAIL': 'JOURNEY_FAILED', 'BLOCKED': 'RUNTIME_UNAVAILABLE'}[c['status']])
        flows.require(type(c['durationMs']) in (float, int) and math.isfinite(c['durationMs']) and c['durationMs'] >= 0)
        flows.require(len(c['checks']) <= len(CHECKS[case_id]))
        for check, expected in zip(c['checks'], CHECKS[case_id]):
            flows.require(set(check) == {'name', 'passed'} and check['name'] == expected and type(check['passed']) is bool)
        flows.require(c['runIdPolicy'] == ('NOT_APPLICABLE' if case_id == 'document' else 'REQUIRED'))
        flows.require(isinstance(c['productionRunIds'], list) and len(c['productionRunIds']) <= 2)
        for value in c['productionRunIds']:
            flows.require(re.fullmatch(r'(agent|review):[a-zA-Z0-9_-]{1,100}', value))
        if c['status'] == 'PASS':
            flows.require(len(c['checks']) == len(CHECKS[case_id]) and all(x['passed'] for x in c['checks']))
            flows.require(len(c['productionRunIds']) == {'document': 0, 'review': 2, 'isolation': 1, 'restart': 1}[case_id])
    flows.require(report['gate'] == ('PASS' if all(c['status'] == 'PASS' for c in report['cases']) else 'FAIL'))
    return report


def compare(baseline, candidate):
    flows.require(set(baseline) == {'report', 'acceptance'})
    flows.require(set(baseline['acceptance']) == {'reportDigest', 'acceptedAtUtc'})
    old = validate(baseline['report']); validate(candidate)
    flows.require(old['contract'] == candidate['contract'])
    flows.require(old['gate'] == 'PASS' and baseline['acceptance']['reportDigest'] == digest(old))
    return {'mode': 'service-flows-v1', 'baselineRunId': old['runId'], 'candidateRunId': candidate['runId'],
            'gate': candidate['gate'], 'regressions': [c['id'] for c in candidate['cases'] if c['status'] != 'PASS']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['validate', 'accept', 'compare'])
    parser.add_argument('--report', required=True)
    parser.add_argument('--baseline')
    parser.add_argument('--name')
    args = parser.parse_args()
    try:
        path = Path(args.report).resolve()
        flows.require(path.stat().st_size < 100000)
        report = validate(json.loads(path.read_text(encoding='utf-8-sig')))
        if args.command == 'accept':
            flows.require(path.parent.parent == (flows.ROOT / '.local/service-evals').resolve() and path.name == 'report.json' and path.parent.name == report['runId'])
            flows.require(report['gate'] == 'PASS' and re.fullmatch('[a-zA-Z0-9_-]{1,80}', args.name or ''))
            flows.require(report['contract'] == flows.contract())
            receipt = json.loads((path.parent / 'receipt.private.json').read_text(encoding='utf-8'))
            flows.require(receipt == {'digest': digest(report)})
            destination = flows.ROOT / 'evals/service-baselines' / (args.name + '.json')
            destination.parent.mkdir(parents=True, exist_ok=True)
            with destination.open('x', encoding='utf-8') as handle:
                json.dump({'report': report, 'acceptance': {'reportDigest': digest(report), 'acceptedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat()}}, handle, ensure_ascii=False, indent=2)
        elif args.command == 'compare':
            result = compare(json.loads(Path(args.baseline).read_text(encoding='utf-8-sig')), report)
            print(json.dumps(result)); return 0 if result['gate'] == 'PASS' else 1
        print(json.dumps({'gate': report['gate']}))
        return 0 if report['gate'] == 'PASS' else 1
    except Exception:
        print('{"gate":"BLOCKED","reason":"INVALID_SERVICE_REPORT"}')
        return 2


if __name__ == '__main__': raise SystemExit(main())
