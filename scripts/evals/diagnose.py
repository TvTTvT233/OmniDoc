"""Emit only test IDs and repository source line numbers, never raw failure text."""
import argparse
import json
import re
import xml.etree.ElementTree as ET
import evaluate as ev


def diagnose(run_id):
    ev.require(re.fullmatch(r'\d{8}T\d{6}Z-[a-f0-9]{8}', run_id), 'INVALID_RUN_ID')
    root = ev.ROOT / '.local/evals' / run_id
    report = ev.read(root / 'report.json')
    definitions, _ = ev.load_dataset()
    output = []
    for case in definitions:
        adapter = case['adapter']
        for trial in range(1, report['repeats'] + 1):
            xml = root / 'xml' / str(trial) / adapter['module'] / ('TEST-' + adapter['className'] + '.xml')
            result = ev.parse_results(xml, adapter['className'], adapter['method'])
            if result['status'] == 'PASSED':
                continue
            line = None
            if xml.is_file():
                try:
                    for node in ET.parse(xml).getroot().iter('testcase'):
                        if node.get('classname') != adapter['className'] or node.get('name') != adapter['method']:
                            continue
                        for failure in list(node):
                            if failure.tag not in {'failure', 'error'}:
                                continue
                            match = re.search(re.escape(adapter['className'] + '.' + adapter['method']) +
                                              r'\([^():]+\.java:(\d{1,7})\)', failure.text or '')
                            if match:
                                line = int(match.group(1))
                except ET.ParseError:
                    pass
            output.append({'caseId': case['id'], 'trial': trial, 'status': result['status'], 'sourceLine': line})
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps({'diagnostics': diagnose(args.run_id)}))
    except Exception:
        print('{"diagnostics":"UNAVAILABLE"}')
        raise SystemExit(2)
