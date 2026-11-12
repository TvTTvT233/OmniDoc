import copy
import datetime as dt
import unittest
from unittest.mock import patch
import service_flows as flow
import service_report as report


def passing():
    cases = []
    for case_id, title in flow.DEFINITIONS:
        ids = [] if case_id == 'document' else [('review:' if case_id == 'review' else 'agent:') + 'a' * 32]
        if case_id == 'review': ids.append('review:' + 'b' * 32)
        cases.append(dict(id=case_id, title=title, status='PASS', reason=None, durationMs=1,
                          checks=[dict(name=name, passed=True) for name in report.CHECKS[case_id]],
                          productionRunIds=ids, runIdPolicy='NOT_APPLICABLE' if case_id == 'document' else 'REQUIRED'))
    return dict(schemaVersion=1, mode='service-flows-v1', runId='20261112T000000Z-abcdefab',
                createdAtUtc='2026-11-12T00:00:00+00:00', commit='a' * 40,
                dirty=False, contract=flow.contract(), modelBoundary='DISABLED_WITH_SYNTHETIC_EMBEDDING', usage=None, cases=cases, gate='PASS')


class ServiceFlowTests(unittest.TestCase):
    def test_complete_report(self):
        self.assertEqual(report.validate(passing())['gate'], 'PASS')

    def test_no_partial_green(self):
        value = passing(); value['cases'][0]['checks'].pop()
        with self.assertRaises(ValueError): report.validate(value)

    def test_no_forged_summary(self):
        value = passing(); value['cases'][0]['checks'][0]['passed'] = False
        with self.assertRaises(ValueError): report.validate(value)

    def test_no_unexpected_public_fields(self):
        value = passing(); value['token'] = 'private'
        with self.assertRaises(ValueError): report.validate(value)

    def test_real_run_ids_required(self):
        value = passing(); value['cases'][1]['productionRunIds'] = []
        with self.assertRaises(ValueError): report.validate(value)

    def test_failure_comparison(self):
        value = passing()
        baseline = dict(report=copy.deepcopy(value), acceptance=dict(reportDigest=report.digest(value), acceptedAtUtc=value['createdAtUtc']))
        value['gate'] = 'FAIL'; case = value['cases'][0]; case['checks'] = []; case['status'] = 'FAIL'; case['reason'] = 'JOURNEY_FAILED'
        compared = report.compare(baseline, value)
        self.assertEqual(compared['regressions'], ['document'])

    def test_modified_baseline_rejected(self):
        value = passing(); baseline = dict(report=value, acceptance=dict(reportDigest='a' * 64, acceptedAtUtc=value['createdAtUtc']))
        with self.assertRaises(ValueError): report.compare(baseline, value)

    def test_no_remote_or_credential_urls(self):
        for url in ['https://example.com', 'http://127.0.0.1@example.com', 'http://secret@localhost', 'http://localhost/api']:
            with self.assertRaises(ValueError): flow.Client(url)
        flow.Client('http://127.0.0.1:18080')

    def test_no_redirect_credentials(self):
        self.assertIsNone(flow.NoRedirect().redirect_request(None, None, 302, None, None, 'https://example.com'))

    def test_no_nonfinite_duration(self):
        value = passing(); value['cases'][0]['durationMs'] = float('nan')
        with self.assertRaises(ValueError): report.validate(value)

    def test_pdf_is_synthetic_valid_structure(self):
        data = flow.pdf('SYNTHETIC')
        self.assertTrue(data.startswith(b'%PDF-1.4'))
        self.assertIn(b'startxref', data)


if __name__ == '__main__': unittest.main()
