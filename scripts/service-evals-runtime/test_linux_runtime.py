"""Pure helper safety tests; these do not claim a Linux service run."""
import json
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
import linux_runtime as rt


class RuntimeTest(unittest.TestCase):
    def test_tools_probe_requires_nonempty_typed_tool_catalog(self):
        valid={'code':200,'message':'ok','timestamp':123,'data':[{'name':'tool','readOnly':True,'destructive':False}]}
        self.assertTrue(rt.tools_ready(valid))
        for invalid in [dict(valid,code=500),dict(valid,data=[]),dict(valid,data=[{}]),dict(valid,data=[{'name':'tool','readOnly':'true','destructive':False}])]:
            self.assertFalse(rt.tools_ready(invalid))

    def test_gateway_schema_rejects_generic_200_error_and_user_data(self):
        valid = {'code':606,'message':'missing','data':None,'timestamp':123}
        self.assertTrue(rt.gateway_response_ready(valid))
        for invalid in [dict(valid, code=500), dict(valid, code=200, data={'id':1}),
                        dict(valid, extra='private'), {}, [], dict(valid, code='606')]:
            self.assertFalse(rt.gateway_response_ready(invalid))

    def test_gateway_probe_accepts_verified_missing_user(self):
        response = io.BytesIO(json.dumps({'code':606,'message':'missing','data':None,'timestamp':123}).encode())
        response.status = 200
        opener = Mock(); opener.open.return_value = response
        with patch.object(rt.urllib.request,'build_opener',return_value=opener):
            rt.wait_http('http://127.0.0.1/probe',1,validator=rt.gateway_response_ready)

    def test_generic_200_fails_and_emits_safe_last_status(self):
        response = io.BytesIO(b'{"code":500,"private":"secret-sentinel"}'); response.status = 200
        opener = Mock(); opener.open.return_value = response
        rt.phase('READINESS','gateway-service')
        with patch.object(rt.urllib.request,'build_opener',return_value=opener), patch.object(rt.time,'monotonic',side_effect=[0,0,2]), patch.object(rt.time,'sleep'):
            with self.assertRaises(rt.RuntimeFailure) as error:
                rt.wait_http('http://127.0.0.1/probe',1,validator=rt.gateway_response_ready)
        payload = rt.failure_payload('start',error.exception)
        self.assertEqual(payload, {'runtime':'start','status':'FAIL','reason':'READINESS_TIMEOUT',
                                 'stage':'READINESS','service':'gateway-service','lastHttpStatus':200})
        self.assertNotIn('secret-sentinel',json.dumps(payload))

    def test_openapi_schema_and_failure_context_are_whitelisted(self):
        self.assertTrue(rt.openapi_ready({'openapi':'3.0.1','info':{},'paths':{}}))
        self.assertFalse(rt.openapi_ready({'code':200}))
        with patch.dict(rt.CONTEXT, {'stage':'private-stage','service':'private-service','lastHttpStatus':'private-value'}), patch.object(rt,'safe_failure_reason',return_value='RUNTIME_FAILED_UNCLASSIFIED'):
            payload=rt.failure_payload('start',RuntimeError('private-error'))
        self.assertNotIn('private',json.dumps(payload))

    def test_diagnostics_return_only_fixed_codes(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            with patch.object(rt, 'STATE', state):
                self.assertEqual(rt.safe_failure_reason(), 'RUNTIME_FAILED_UNCLASSIFIED')
                (state / 'compose.private.log').write_text('private-sentinel toomanyrequests credential=value')
                self.assertEqual(rt.safe_failure_reason(), 'REGISTRY_RATE_LIMIT')
                (state / 'compose.private.log').write_text('private-sentinel unknown reason')
                self.assertEqual(rt.safe_failure_reason(), 'RUNTIME_FAILED_UNCLASSIFIED')

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
