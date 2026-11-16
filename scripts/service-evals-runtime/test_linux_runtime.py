"""Pure helper safety tests; these do not claim a Linux service run."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import linux_runtime as rt


class RuntimeTest(unittest.TestCase):
    def test_child_environment_does_not_inherit_provider_or_java_injection(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory); (state / 'runtime.env').write_text('MYSQL_DATABASE=omnidoc\n')
            with patch.object(rt, 'STATE', state), patch.dict(os.environ, {
                'PATH': '/usr/bin', 'HOME': '/home/test', 'OPENAI_API_KEY': 'private',
                'CUSTOM_PROVIDER_KEY': 'private', 'JAVA_TOOL_OPTIONS': '-javaagent:bad'}, clear=True):
                env = rt.environment()
                self.assertEqual(env, {'PATH': '/usr/bin', 'HOME': '/home/test', 'MYSQL_DATABASE': 'omnidoc'})

    def test_identity_mismatch_never_sends_signal(self):
        with patch.object(rt, 'identity', return_value={'startTicks':'2','exe':'/java'}), patch.object(rt.os, 'kill') as kill:
            with self.assertRaises(RuntimeError): rt.stop({'pid':42,'identity':{'startTicks':'1','exe':'/java'}})
            kill.assert_not_called()

    def test_changed_artifact_rejects_restart_before_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / '.local').mkdir()
            manifest = root / 'manifest.json'; artifact = root / 'app.jar'; artifact.write_bytes(b'new')
            manifest.write_text(json.dumps({'artifacts':{'app':'old'},'processes':{}}))
            with patch.object(rt,'ROOT',root), patch.object(rt,'MANIFEST',manifest), patch.object(rt,'artifacts',return_value={'app':artifact}), patch.object(rt,'stop') as stop:
                with self.assertRaises(RuntimeError): rt.restart(root / '.local/proof.json')
                stop.assert_not_called()

    def test_proof_outside_checkout_rejected_before_manifest_read(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(rt,'stop') as stop:
            with self.assertRaises(RuntimeError): rt.restart(Path(directory) / 'proof.json')
            stop.assert_not_called()

    def test_exited_process_rejects_readiness(self):
        with patch.object(rt,'identity',return_value=None):
            with self.assertRaises(RuntimeError): rt.wait_http('http://127.0.0.1:9',1,{'pid':42,'identity':{'exe':'java'}})


if __name__ == '__main__': unittest.main()
