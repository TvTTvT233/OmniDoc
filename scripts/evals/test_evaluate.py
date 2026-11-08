import copy
import argparse
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import evaluate as ev


def report(status='PASSED', critical=True):
    cases = [{'id': 'A', 'suiteId': 'agent', 'critical': critical, 'adapter': {'method': 'm'},
              'trials': [{'trial': 1, 'status': status, 'durationMs': 3}]}]
    result = {'schemaVersion': 1, 'runId': 'test', 'mode': 'deterministic-components',
            'dataset': {'digest': 'fixed'}, 'grader': {'version': 'v1'},
            'executionConfig': {'models': 'DISABLED_TEST_DOUBLES'}, 'repeats': 1,
            'cases': cases, 'caseSetDigest': ev.digest([{k: c[k] for k in ('id', 'suiteId', 'critical', 'adapter')} for c in cases]),
            'infrastructureFailures': [], 'summary': ev.summarize(cases, [])}
    result['acceptance'] = {'reviewedBy': 'unit-test', 'reason': 'synthetic gate self-test', 'reportDigest': ev.digest(result)}
    return result


class EvaluationGateTest(unittest.TestCase):
    def test_source_digest_ignores_checkout_line_endings_but_not_content(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.java'
            path.write_bytes(b'class X {}\n')
            expected = ev.source_digest(path)
            path.write_bytes(b'class X {}\r\n')
            self.assertEqual(expected, ev.source_digest(path))
            path.write_bytes(b'class Y {}\n')
            self.assertNotEqual(expected, ev.source_digest(path))

    def test_accept_uses_exclusive_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(report='mocked-evidence', name='baseline', reviewed_by='unit-test', reason='synthetic check')
            with patch.object(ev, 'ROOT', Path(directory)), patch.object(ev, 'verified_local_report', side_effect=lambda _: copy.deepcopy(report())), contextlib.redirect_stdout(io.StringIO()):
                ev.accept(args)
                path = Path(directory) / 'evals/baselines/baseline.json'
                before = path.read_bytes()
                with self.assertRaises(FileExistsError):
                    ev.accept(args)
                self.assertEqual(before, path.read_bytes())

    def test_cli_cannot_overwrite_inputs_or_baseline_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = root / 'baseline.json'
            candidate = root / 'candidate.json'
            ev.write(baseline, report())
            ev.write(candidate, report())
            for output in [baseline, candidate, root / 'evals/baselines/new.json']:
                before = output.read_bytes() if output.exists() else None
                with patch.object(ev, 'ROOT', root), patch('sys.argv', ['evaluate.py', 'compare', '--baseline', str(baseline), '--candidate', str(candidate), '--output', str(output)]), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(2, ev.main())
                self.assertEqual(before, output.read_bytes() if output.exists() else None)

    def test_unreviewed_or_changed_baseline_is_rejected(self):
        baseline = report()
        del baseline['acceptance']
        with self.assertRaises(ValueError):
            ev.compare(baseline, report())
        baseline = report()
        baseline['runId'] = 'changed-after-review'
        with self.assertRaises(ValueError):
            ev.compare(baseline, report())

    def test_arbitrary_report_cannot_be_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            ev.write(path, report())
            with self.assertRaisesRegex(ValueError, 'NOT_LOCAL_RUN_REPORT'):
                ev.verified_local_report(path)

    def test_same_results_pass(self):
        self.assertEqual('PASS', ev.compare(report(), report())['gate'])

    def test_critical_failure_blocks(self):
        candidate = report('FAILED')
        self.assertEqual(['A'], candidate['summary']['criticalFailures'])
        self.assertEqual('FAIL', ev.compare(report(), candidate)['gate'])

    def test_noncritical_regression_still_blocks(self):
        self.assertEqual('FAIL', ev.compare(report(critical=False), report('FAILED', False))['gate'])

    def test_skip_and_missing_never_count_as_success(self):
        for status in ['SKIPPED', 'MISSING', 'ERROR']:
            with self.subTest(status=status):
                self.assertEqual('FAIL', ev.compare(report(), report(status))['gate'])

    def test_dataset_grader_or_environment_change_is_incomparable(self):
        for field in ['dataset', 'grader', 'executionConfig']:
            candidate = report()
            candidate[field] = {'changed': True}
            with self.subTest(field=field), self.assertRaises(ValueError):
                ev.compare(report(), candidate)

    def test_forged_aggregate_is_rejected(self):
        candidate = report('FAILED')
        candidate['summary']['gate'] = 'PASS'
        with self.assertRaises(ValueError):
            ev.compare(report(), candidate)

    def test_missing_trial_and_duplicate_case_rejected(self):
        candidate = report()
        candidate['repeats'] = 2
        with self.assertRaises(ValueError):
            ev.validate_report(candidate)
        candidate = report()
        candidate['cases'].append(copy.deepcopy(candidate['cases'][0]))
        with self.assertRaises(ValueError):
            ev.validate_report(candidate)

    def test_maven_failure_not_hidden_by_green_selected_tests(self):
        candidate = report()
        candidate['infrastructureFailures'] = [{'exitCode': 1}]
        candidate['summary'] = ev.summarize(candidate['cases'], candidate['infrastructureFailures'])
        self.assertEqual('FAIL', ev.compare(report(), candidate)['gate'])

    def test_repeated_trials_all_count(self):
        candidate = report()
        candidate['repeats'] = 2
        candidate['cases'][0]['trials'].append({'trial': 2, 'status': 'FAILED', 'durationMs': 3})
        candidate['summary'] = ev.summarize(candidate['cases'], [])
        result = ev.compare(report(), candidate)
        self.assertEqual(.5, result['changes'][0]['candidatePassRate'])
        self.assertEqual('FAIL', result['gate'])

    def test_xml_failure_is_redacted_and_skipped_is_not_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.xml'
            for child, expected in [('<failure>PRIVATE_FIXTURE_SENTINEL</failure>', 'FAILED'),
                                    ('<skipped/>', 'SKIPPED'), ('', 'PASSED')]:
                path.write_text('<testsuite><testcase classname="C" name="m" time="0.2">' + child + '</testcase></testsuite>')
                result = ev.parse_results(path, 'C', 'm')
                self.assertEqual(expected, result['status'])
                self.assertNotIn('PRIVATE_FIXTURE_SENTINEL', str(result))
            path.write_text('<invalid')
            self.assertEqual('ERROR', ev.parse_results(path, 'C', 'm')['status'])
            path.unlink()
            self.assertEqual('MISSING', ev.parse_results(path, 'C', 'm')['status'])

    def test_duplicate_xml_result_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.xml'
            path.write_text('<testsuite><testcase classname="C" name="m"/><testcase classname="C" name="m"/></testsuite>')
            self.assertEqual('ERROR', ev.parse_results(path, 'C', 'm')['status'])


if __name__ == '__main__':
    unittest.main()
