import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import diagnose
import evaluate as ev


class DiagnosticTests(unittest.TestCase):
    def test_only_known_test_source_line_is_public(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = '20261112T000000Z-abcdef01'
            location = root / '.local/evals' / run
            ev.write(location / 'report.json', {'repeats': 1})
            xml = location / 'xml/1/document-service/TEST-example.TaskTest.xml'
            xml.parent.mkdir(parents=True)
            xml.write_text('<testsuite><testcase classname="example.TaskTest" name="example" time="0.1">'
                           '<failure message="PRIVATE_MARKER">PRIVATE_MARKER\n'
                           'at example.TaskTest.example(TaskTest.java:117)</failure>'
                           '<system-out>PRIVATE_MARKER</system-out></testcase></testsuite>')
            cases = [{'id': 'DOC-004', 'adapter': {'module': 'document-service',
                     'className': 'example.TaskTest', 'method': 'example'}}]
            with patch.object(ev, 'ROOT', root), patch.object(ev, 'load_dataset', return_value=(cases, {})):
                result = diagnose.diagnose(run)
            self.assertEqual(result, [{'caseId': 'DOC-004', 'trial': 1, 'status': 'FAILED', 'sourceLine': 117}])
            self.assertNotIn('PRIVATE_MARKER', json.dumps(result))

    def test_path_traversal_rejected(self):
        with self.assertRaises(ValueError): diagnose.diagnose('../private')


if __name__ == '__main__': unittest.main()
