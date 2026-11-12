"""Real loopback HTTP journeys; synthetic accounts/files, no external model calls."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import platform
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
DEFINITIONS = [('document', '文档更新、索引与历史引用'), ('review', '材料缺件、整改与复审'),
               ('isolation', '重复请求与用户隔离'), ('restart', '审批等待跨进程重启')]


def contract():
    paths = ['scripts/evals/service_flows.py', 'scripts/evals/service_report.py',
             'ai-service/src/test/java/com/javaee/aiservice/consistency/ServiceFlowEvaluationApplication.java']
    data = ''.join((ROOT / p).read_text(encoding='utf-8').replace('\r\n', '\n') for p in paths)
    return {'runnerOs': platform.system(), 'digest': hashlib.sha256(data.encode()).hexdigest()}


def require(ok):
    if not ok:
        raise ValueError('CONTRACT_FAILED')


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


class Client:
    def __init__(self, base):
        parsed = urllib.parse.urlsplit(base)
        require(parsed.scheme == 'http' and parsed.hostname in {'127.0.0.1', 'localhost', '::1'}
                and not parsed.username and not parsed.password and not parsed.query and parsed.path in {'', '/'})
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.token = None

    def call(self, method, path, body=None, token=None, key=None, raw=None, content_type=None):
        headers = {'Accept': 'application/json'}
        if token: headers['Authorization'] = 'Bearer ' + token
        if method != 'GET': headers['Idempotency-Key'] = key or uuid.uuid4().hex
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False).encode()
            content_type = 'application/json; charset=utf-8'
        if content_type: headers['Content-Type'] = content_type
        request = urllib.request.Request(self.base + path, data=raw, method=method, headers=headers)
        try: response = self.opener.open(request, timeout=45)
        except urllib.error.HTTPError as error: response = error
        with response:
            data = response.read(4_000_001)
            require(len(data) <= 4_000_000)
            try: value = json.loads(data)
            except (ValueError, UnicodeDecodeError): value = None
            return response.status, value

    def api(self, method, path, body=None, status=200, key=None):
        code, result = self.call(method, path, body, self.token, key)
        require(code == status and isinstance(result, dict))
        if 'code' in result:
            require(result['code'] == 200)
            return result['data']
        return result

    def register(self):
        name = 'flow_' + uuid.uuid4().hex[:16]
        password = uuid.uuid4().hex
        code, result = self.call('POST', '/api/users/register', {'username': name, 'password': password,
                                'email': name + '@example.invalid', 'phone': '139' + str(int(uuid.uuid4().hex[:8], 16)).zfill(10)[-8:]})
        require(code == 200 and result.get('code') == 200)
        code, result = self.call('POST', '/api/users/login', {'username': name, 'password': password})
        require(code == 200 and result.get('code') == 200)
        self.token = result['data']['accessToken']

    def upload(self, filename, content, owned=False):
        boundary = 'flow' + uuid.uuid4().hex
        mime = 'application/pdf' if filename.endswith('.pdf') else 'text/plain'
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
        code, result = self.call('POST', '/api/files/owned' if owned else '/api/files/upload', token=self.token,
                                 raw=raw, content_type='multipart/form-data; boundary=' + boundary)
        require(code == (201 if owned else 200))
        return result if owned else result['data']


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward bearer credentials to a redirected destination.


def pdf(text):
    stream = f'BT /F1 12 Tf 72 720 Td ({text}) Tj ET'
    objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', f'<< /Length {len(stream)} >>\nstream\n{stream}\nendstream']
    value, offsets = '%PDF-1.4\n', []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(value)); value += f'{i} 0 obj\n{obj}\nendobj\n'
    xref = len(value)
    value += 'xref\n0 6\n0000000000 65535 f \n' + ''.join(f'{x:010} 00000 n \n' for x in offsets)
    return (value + f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n').encode('ascii')


class Journeys:
    def __init__(self, client, other, args, out):
        self.c, self.other, self.args, self.out = client, other, args, out
        self.checks, self.ids = [], []

    def check(self, name, passed):
        self.checks.append({'name': name, 'passed': bool(passed)})
        require(passed)

    def poll(self, get, predicate):
        until = time.monotonic() + self.args.timeout
        while time.monotonic() < until:
            value = get()
            if predicate(value): return value
            time.sleep(.3)
        raise TimeoutError('WAIT_EXPIRED')

    def document(self):
        c = self.c
        first = 'Synthetic service flow original text ' + uuid.uuid4().hex
        file = c.upload('flow.txt', first.encode())
        doc = c.api('POST', '/api/documents', {'title': 'Synthetic service flow', 'fileId': file['fileId'], 'category': 'baseline'})
        base = '/api/documents/' + str(doc['id'])
        self.check('parse-exact-text', doc['content'] == first and doc['snapshotState'] == 'VERIFIED')
        c.api('POST', base + '/index', {'expectedVersion': doc['version'], 'strategy': 'FIXED_LENGTH'}, status=202)
        old = self.poll(lambda: c.api('GET', base + '/index-state'), lambda s: s['indexState'] in {'READY', 'FAILED'})
        self.check('initial-index-ready', old['indexState'] == 'READY' and old['queryable'])
        segments = c.api('GET', '/api/ai/rag/document/' + str(doc['id']) + '/segments')
        ref = segments['segments'][0]['reference']
        second = 'Synthetic updated service flow text ' + uuid.uuid4().hex
        saved = c.api('PUT', base, {'title': 'Synthetic service flow', 'content': second, 'contentFormat': 'PLAIN_TEXT', 'expectedVersion': doc['version'], 'changeLog': 'Synthetic evaluation'})
        state = c.api('GET', base + '/index-state')
        self.check('old-generation-never-queryable-after-save', not state['queryable'] or (state['indexedVersion'] == saved['version'] and state['activeGenerationId'] != old['activeGenerationId']))
        historical = c.api('GET', base + '/references/' + ref['referenceId'])
        self.check('historical-reference-exact', historical['status'] == 'HISTORICAL' and historical['content'] == first[ref['startOffset']:ref['endOffset']])
        new = self.poll(lambda: c.api('GET', base + '/index-state'), lambda s: s['indexState'] in {'READY', 'FAILED'})
        self.check('new-version-published', new['indexState'] == 'READY' and new['indexedVersion'] == saved['version'] and new['activeGenerationId'] != old['activeGenerationId'])
        fresh = c.api('GET', '/api/ai/rag/document/' + str(doc['id']) + '/segments')
        self.check('new-segments-version', fresh['documentVersion'] == saved['version'])
        status, _ = c.call('GET', base + '/references/' + ref['referenceId'], token=self.other.token)
        self.check('reference-owner-isolated', status == 404)

    def review(self):
        c = self.c
        case = c.api('POST', '/api/review-m0/cases', {'title': 'Synthetic service flow', 'processingMode': 'LOCAL_ONLY'}, status=201)
        base = '/api/review-m0/cases/' + case['caseId']
        requirements = []
        for name in ['application', 'proof']:
            draft = c.api('POST', base + '/checklist-draft/requirements', {'displayName': name, 'enabled': True, 'applicability': 'APPLIES', 'minimumCount': 1, 'fileNameRule': {'kind': 'EXACT', 'pattern': name + '.pdf', 'caseSensitive': True}}, status=201)
            requirements.append(next(r for r in draft['items'] if r['displayName'] == name))
        draft = c.api('GET', base + '/checklist-draft')
        snapshot = c.api('POST', base + '/checklist-snapshots', {'expectedChecklistRevision': draft['revision'], 'acknowledgedRequirementIds': [r['requirementId'] for r in requirements]}, status=201)
        snapshot_id = snapshot['checklistSnapshotId']
        assignments = []
        def attach(name, index):
            f = c.upload(name + '.pdf', pdf('SYNTHETIC material'), True)
            attached = c.api('POST', base + '/submission-sources', {'fileId': f['fileId'], 'submissionFileName': name + '.pdf'}, status=201)
            assignments.append({'materialId': attached['materials'][0]['materialId'], 'requirementId': requirements[index]['requirementId'], 'status': 'USER_CONFIRMED'})
            current = c.api('GET', base + '/assignments')
            c.api('PUT', base + '/assignments', {'expectedAssignmentRevision': current['assignmentRevision'], 'assignments': assignments})
        def review():
            state = c.api('GET', base)
            confirmed = c.api('POST', base + '/upload-confirmations', {'checklistSnapshotId': snapshot_id, 'materialSetRevision': state['materialSetRevision'], 'acknowledgedUploadComplete': True}, status=201)
            job = c.api('POST', base + '/reviews', {'mode': 'FULL', 'checklistSnapshotId': snapshot_id, 'uploadConfirmationId': confirmed['uploadConfirmationId'], 'baseReportId': None, 'changedMaterialIds': []}, status=202)
            self.ids.append('review:' + job['jobId'])
            done = self.poll(lambda: c.api('GET', base + '/jobs/' + job['jobId']), lambda j: j['status'] in {'SUCCEEDED', 'FAILED', 'CANCELLED'})
            self.check('review-worker-success', done['status'] == 'SUCCEEDED')
            return c.api('GET', base + '/reports/current')
        attach('application', 0)
        before = review()
        self.check('missing-material-detected', before['assessment'] == 'M0_NOT_READY' and before['summary']['missingCount'] == 1)
        self.check('dual-evidence-present', all(f['ruleEvidence'] and f['materialEvidence'] for f in before['findings']))
        attach('proof', 1)
        old = c.api('GET', base + '/reports/' + before['reportId'])
        self.check('old-report-outdated', old['freshness'] == 'OUTDATED')
        after = review()
        self.check('supplement-clears-findings', after['assessment'] == 'M0_READY' and not after['findings'] and after['reportId'] != before['reportId'])
        code, _ = c.call('GET', base + '/reports/' + after['reportId'], token=self.other.token)
        self.check('report-owner-isolated', code == 404)

    def isolation(self):
        c = self.c
        request = {'task': '统计文本', 'clientRequestId': uuid.uuid4().hex, 'reflectionEnabled': False, 'autoReplan': False, 'context': {'content': 'synthetic service flow text'}}
        first = c.api('POST', '/api/ai/agent/execute', request)
        self.check('deterministic-agent-completed', first['status'] == 'success')
        self.ids.append('agent:' + first['traceId'])
        repeated = c.api('POST', '/api/ai/agent/execute', request)
        self.check('duplicate-same-run', repeated['traceId'] == first['traceId'] and repeated['status'] == 'success')
        code, _ = c.call('POST', '/api/ai/agent/execute', dict(request, task='changed'), c.token)
        self.check('payload-conflict', code == 409)
        code, _ = c.call('GET', '/api/ai/task-runs/' + self.ids[0], token=self.other.token)
        self.check('task-owner-isolated', code == 404)
        run = c.api('GET', '/api/ai/task-runs/' + self.ids[0])
        self.check('durable-jdbc-result', run['status'] == 'SUCCEEDED' and run['capabilities']['persistence'] == 'JDBC')

    def restart(self):
        require(self.args.restart_script and re.fullmatch('[a-f0-9]{64}', self.args.artifact_sha256 or ''))
        c = self.c
        waiting = c.api('POST', '/api/ai/agent/execute', {'task': '删除文件', 'clientRequestId': uuid.uuid4().hex,
                    'reflectionEnabled': False, 'autoReplan': False, 'context': {'objectName': 'synthetic-never-dispatched.txt'}})
        self.check('approval-required', waiting['status'] == 'action_required')
        token = waiting['pendingApproval']['agentApprovalToken']
        run_id = 'agent:' + waiting['traceId']; self.ids.append(run_id)
        before = c.api('GET', '/api/ai/task-runs/' + run_id)
        self.check('approval-persisted-before-restart', before['status'] == 'WAITING_INPUT' and before['capabilities']['persistence'] == 'JDBC')
        proof = self.out / 'restart-proof.private.json'
        with (self.out / 'restart.private.log').open('wb') as log:
            result = subprocess.run(['pwsh', '-NoProfile', '-File', str(Path(self.args.restart_script).resolve()), '-ProofPath', str(proof)], stdout=log, stderr=log, timeout=self.args.timeout)
        require(result.returncode == 0)
        evidence = json.loads(proof.read_text(encoding='utf-8-sig'))
        self.check('different-process-same-artifact', type(evidence['beforePid']) is int and type(evidence['afterPid']) is int
                   and evidence['beforePid'] > 0 and evidence['afterPid'] > 0 and evidence['beforePid'] != evidence['afterPid']
                   and evidence['artifactSha256'] == self.args.artifact_sha256)
        deadline = time.monotonic() + self.args.timeout
        after = None
        while time.monotonic() < deadline:
            try:
                after = c.api('GET', '/api/ai/task-runs/' + run_id)
                break
            except (urllib.error.URLError, ValueError, TimeoutError):
                time.sleep(.3)
        require(after is not None)
        self.check('same-approval-restored', after['status'] == 'WAITING_INPUT' and after['waitingReason'] == 'APPROVAL_REQUIRED')
        cancelled = c.api('POST', '/api/ai/agent/approvals/' + token + '/cancel')
        self.check('approval-cancelled', cancelled is True)
        self.check('cancelled-state-persisted', c.api('GET', '/api/ai/task-runs/' + run_id)['status'] == 'CANCELLED')
        late = c.api('POST', '/api/ai/agent/approvals/confirm', {'approvalToken': token})
        self.check('late-confirm-no-write', late['status'] == 'cancelled')


def run(args):
    require(args.isolated_runtime and 10 <= args.timeout <= 600)
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    out = ROOT / '.local/service-evals' / run_id
    out.mkdir(parents=True)
    report = {'schemaVersion': 1, 'mode': 'service-flows-v1', 'runId': run_id,
              'createdAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
              'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
              'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip()),
              'contract': contract(),
              'modelBoundary': 'DISABLED_WITH_SYNTHETIC_EMBEDDING', 'usage': None, 'cases': []}
    ready = False
    c, other = Client(args.base_url), Client(args.base_url)
    try:
        c.register(); other.register()
        models = c.api('GET', '/api/ai/models')
        require(models and all(m.get('available') is False for m in models))
        ready = True
    except Exception: pass  # No exception/body/credential strings enter public reports.
    for case_id, title in DEFINITIONS:
        journey = Journeys(c, other, args, out)
        started = time.monotonic()
        status, reason = 'BLOCKED', 'RUNTIME_UNAVAILABLE'
        if ready:
            try:
                getattr(journey, case_id)()
                status, reason = 'PASS', None
            except Exception:
                status, reason = 'FAIL', 'JOURNEY_FAILED'
        report['cases'].append({'id': case_id, 'title': title, 'status': status, 'reason': reason,
                                'durationMs': round((time.monotonic() - started) * 1000, 3),
                                'checks': journey.checks, 'productionRunIds': journey.ids,
                                'runIdPolicy': 'NOT_APPLICABLE' if case_id == 'document' else 'REQUIRED'})
    report['gate'] = 'PASS' if all(c['status'] == 'PASS' for c in report['cases']) else 'FAIL'
    from service_report import validate, digest
    validate(report)
    write(out / 'report.json', report)
    write(out / 'receipt.private.json', {'digest': digest(report)})
    print(json.dumps({'runId': run_id, 'gate': report['gate']}))
    return 0 if report['gate'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--isolated-runtime', action='store_true', help='Confirm dedicated disposable runtime; never point at production.')
    parser.add_argument('--restart-script', required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args()
    try: return run(args)
    except Exception:
        print('{"gate":"BLOCKED","reason":"INVALID_CONFIGURATION"}')
        return 2


if __name__ == '__main__': raise SystemExit(main())
