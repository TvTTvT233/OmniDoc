import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import catalog
import evaluate as ev


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.definitions, self.dataset = ev.load_dataset()
        self.baseline = ev.read(ev.ROOT / 'evals/baselines/deterministic-v1-normalized.json')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.report = copy.deepcopy(self.baseline)
        self.report.pop('acceptance')
        self.evidence = Path(self.temp.name) / self.report['runId']
        groups = {}
        for case in self.report['cases']:
            a = case['adapter']
            path = self.evidence / 'xml/1' / a['module'] / ('TEST-' + a['className'] + '.xml')
            root = groups.setdefault(path, ET.Element('testsuite'))
            ET.SubElement(root, 'testcase', classname=a['className'], name=a['method'], time='0.001')
        for path, root in groups.items():
            # This private property must never escape into the catalogue.
            ET.SubElement(root, 'system-out').text = 'PRIVATE_SENTINEL_DO_NOT_EXPORT'
            path.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(root).write(path)
        for case in self.report['cases']:
            a = case['adapter']
            path = self.evidence / 'xml/1' / a['module'] / ('TEST-' + a['className'] + '.xml')
            case['trials'] = [dict(ev.parse_results(path, a['className'], a['method']), trial=1,
                                   evidenceSha256=hashlib.sha256(path.read_bytes()).hexdigest())]
        self.report['repeats'] = 1
        self.report['summary'] = ev.summarize(self.report['cases'], [])

    def project(self):
        return catalog.project(self.report, 'run:' + self.report['runId'], self.definitions, self.dataset, self.evidence)

    def test_projection_excludes_private_evidence_and_acceptance_text(self):
        output = self.project()
        text = json.dumps(output)
        self.assertEqual(output['summary']['passed'], 30)
        for forbidden in ('PRIVATE_SENTINEL', 'evidenceSha256', 'adapter', 'productionRunId', 'acceptance'):
            self.assertNotIn(forbidden, text)
        self.baseline['acceptance']['reason'] = 'PRIVATE_SENTINEL'
        accepted = catalog.project(self.baseline, 'baseline:approved', self.definitions, self.dataset)
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(accepted))

    def test_unknown_report_field_rejected(self):
        self.report['privateBody'] = 'PRIVATE_SENTINEL'
        with self.assertRaises(ValueError): self.project()

    def test_changed_case_description_rejected(self):
        self.report['cases'][0]['title'] = 'PRIVATE_SENTINEL'
        with self.assertRaises(ValueError): self.project()

    def test_unknown_trial_reason_rejected(self):
        self.report['cases'][0]['trials'][0]['reason'] = 'PRIVATE_SENTINEL'
        with self.assertRaises(ValueError): self.project()

    def test_invalid_metadata_rejected(self):
        self.report['code']['commit'] = 'PRIVATE_SENTINEL'
        with self.assertRaises(ValueError): self.project()

    def test_nonfinite_elapsed_rejected(self):
        self.report['elapsedSeconds'] = float('nan')
        with self.assertRaises(ValueError): self.project()

    def test_forged_green_and_xml_tampering_rejected(self):
        self.report['cases'][0]['trials'][0]['durationMs'] = 999
        with self.assertRaises(ValueError): self.project()

    def test_missing_xml_cannot_be_green(self):
        for path in self.evidence.rglob('*.xml'): path.unlink()
        with self.assertRaises(ValueError): self.project()

    def test_failed_missing_run_remains_visible(self):
        for path in self.evidence.rglob('*.xml'): path.unlink()
        for case in self.report['cases']:
            case['trials'] = [{'trial': 1, 'status': 'MISSING', 'reason': 'NO_FRESH_TEST_RESULT',
                               'durationMs': None, 'evidenceSha256': None}]
        self.report['infrastructureFailures'] = [{'module': 'ai-service', 'trial': 1, 'exitCode': 125}]
        self.report['summary'] = ev.summarize(self.report['cases'], self.report['infrastructureFailures'])
        result = self.project()
        self.assertEqual(result['summary']['gate'], 'FAIL')
        self.assertEqual(result['summary']['passed'], 0)
        self.assertEqual(result['infrastructureFailureCount'], 1)

    def test_modified_baseline_rejected(self):
        self.baseline['elapsedSeconds'] += 1
        with self.assertRaises(ValueError):
            catalog.project(self.baseline, 'baseline:modified', self.definitions, self.dataset)

    def test_requested_missing_run_blocks(self):
        with self.assertRaisesRegex(ValueError, 'REQUESTED_RUN_REJECTED'):
            catalog.build('19990101T000000Z-00000000')

    def test_actual_catalog_comparisons_and_safe_output(self):
        root = Path(self.temp.name) / 'repo'
        ev.write(root / 'evals/baselines/current.json', self.baseline)
        old = copy.deepcopy(self.baseline)
        old['grader']['digest'] = '0' * 64
        old['acceptance']['reportDigest'] = ev.digest({k: v for k, v in old.items() if k != 'acceptance'})
        ev.write(root / 'evals/baselines/old.json', old)
        import shutil
        destination = root / '.local/evals' / self.report['runId']
        shutil.copytree(self.evidence, destination)
        ev.write(destination / 'report.json', self.report)
        with patch.object(ev, 'ROOT', root), patch.object(ev, 'load_dataset', return_value=(self.definitions, self.dataset)):
            result = catalog.build(limit=2)
        self.assertEqual(result['omittedReports'], 0)
        self.assertTrue(any(r['kind'] == 'baseline' for r in result['runs']))
        self.assertTrue(any(c['gate'] == 'BLOCKED' for c in result['comparisons']))
        self.assertTrue(any(c['gate'] == 'PASS' for c in result['comparisons']))
        for run in result['runs']:
            self.assertNotIn('acceptance', run)
            self.assertNotIn('usage', run)


if __name__ == '__main__': unittest.main()
