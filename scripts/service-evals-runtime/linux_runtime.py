"""Isolated Linux CI runtime. Private state stays below .local; no model credentials inherited."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.local/service-runtime-linux'
MANIFEST = STATE / 'processes.json'
PORTS = {'user-service': 18081, 'file-service': 18082, 'document-service': 18084,
         'ai-service': 18083, 'gateway-service': 18080}


def require(ok):
    if not ok: raise RuntimeError('RUNTIME_CONTRACT_FAILED')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')


def environment():
    # Allowlist, rather than trying to enumerate all possible provider key names.
    env = {k: os.environ[k] for k in ('PATH', 'HOME', 'JAVA_HOME', 'LANG', 'TZ') if k in os.environ}
    for line in (STATE / 'runtime.env').read_text().splitlines():
        key, value = line.split('=', 1); env[key] = value
    return env


def compose(*args):
    with (STATE / 'compose.private.log').open('ab') as log:
        subprocess.run(['docker', 'compose', '--env-file', str(STATE / 'runtime.env'), '-f',
                        str(ROOT / 'scripts/service-evals-runtime/compose.yml'), *args],
                       cwd=ROOT, check=True, stdout=log, stderr=log, timeout=300)


def initialize():
    STATE.mkdir(parents=True, exist_ok=True)
    os.chmod(STATE, 0o700)
    path = STATE / 'runtime.env'
    if not path.exists():
        mysql, redis, mq, minio = [secrets.token_hex(32) for _ in range(4)]
        env = dict(MYSQL_ROOT_PASSWORD=mysql, MYSQL_DATABASE='omnidoc', MYSQL_LOCAL_HOST='127.0.0.1',
                   MYSQL_LOCAL_PORT='23306', MYSQL_LOCAL_USERNAME='root', MYSQL_LOCAL_PASSWORD=mysql,
                   REDIS_LOCAL_HOST='127.0.0.1', REDIS_PASSWORD=redis, REDIS_LOCAL_PASSWORD=redis,
                   REDIS_PORT='26379', RABBITMQ_HOST='127.0.0.1', RABBITMQ_USERNAME='evaluator',
                   RABBITMQ_PASSWORD=mq, MINIO_LOCAL_ENDPOINT='http://127.0.0.1:29000',
                   MINIO_ACCESS_KEY='evaluator', MINIO_SECRET_KEY=minio,
                   AI_MEMORY_SCHEMA_AUTO_MIGRATION_ENABLED='true')
        for key in ('OMNIDOC_JWT_SECRET', 'OMNIDOC_INDEX_INTERNAL_TOKEN', 'OMNIDOC_REVIEW_INTERNAL_TOKEN'):
            env[key] = secrets.token_hex(32)
        path.write_text(''.join(f'{k}={v}\n' for k, v in env.items()))
        os.chmod(path, 0o600)
    compose('up', '-d', '--wait', '--wait-timeout', '180', 'mysql', 'redis', 'rabbitmq', 'minio')
    wait_http('http://127.0.0.1:29000/minio/health/ready', 90)


def identity(pid):
    proc = Path('/proc') / str(pid)
    if not proc.exists(): return None
    stat = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
    if stat[0] == 'Z': return None
    return {'startTicks': stat[19], 'exe': str((proc / 'exe').resolve())}


def stop(entry):
    current = identity(entry['pid'])
    if current is None: return
    require(current == entry['identity'])
    os.kill(entry['pid'], signal.SIGTERM)
    until = time.monotonic() + 30
    while time.monotonic() < until:
        if identity(entry['pid']) is None: return
        time.sleep(.2)
    # Do not kill an unrelated/reused process, even if shutdown takes too long.
    raise RuntimeError('OWNED_PROCESS_STOP_TIMEOUT')


def wait_http(url, timeout, entry=None, expected=200):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if entry: require(identity(entry['pid']) == entry['identity'])
        try:
            with opener.open(url, timeout=3) as response:
                if response.status == expected: return
        except urllib.error.HTTPError as error:
            if error.code == expected: return
        except (OSError, ValueError): pass
        time.sleep(.3)
    raise RuntimeError('READINESS_TIMEOUT')


def artifacts():
    files = {s: ROOT / s / 'target' / (s + '-1.0.0.jar') for s in PORTS}
    source = ROOT / 'ai-service/target/test-classes/com/javaee/aiservice/consistency'
    classes = sorted(source.glob('ServiceFlowEvaluationApplication*.class'))
    require(len(classes) >= 2 and all(p.is_file() for p in files.values()))
    files.update({p.name: p for p in classes})
    files['modelConfig'] = ROOT / 'scripts/baseline/no-external-models.yml'
    return files


def launch(service, env):
    java = str((Path(env['JAVA_HOME']) / 'bin/java').resolve())
    require(Path(java).is_file())
    with socket.socket() as sock: require(sock.connect_ex(('127.0.0.1', PORTS[service])) != 0)
    jar = ROOT / service / 'target' / (service + '-1.0.0.jar')
    args = [java, '-Xms128m', '-Xmx640m']
    if service == 'ai-service':
        fixture = STATE / 'embedding-fixture'
        package = fixture / 'com/javaee/aiservice/consistency'; package.mkdir(parents=True, exist_ok=True)
        for name, source in artifacts().items():
            if name.endswith('.class'): shutil.copy2(source, package / name)
        support = Path(env['HOME']) / '.m2/repository/org/springframework/boot/spring-boot-test/3.2.0/spring-boot-test-3.2.0.jar'
        require(support.is_file())
        args += ['-Dloader.main=com.javaee.aiservice.consistency.ServiceFlowEvaluationApplication',
                 f'-Dloader.path={fixture},{support}', '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher']
    else: args += ['-jar', str(jar)]
    args += ['--spring.profiles.active=local', '--server.address=127.0.0.1', f'--server.port={PORTS[service]}',
             '--spring.data.redis.port=26379', '--spring.rabbitmq.port=25673', '--logging.level.com.javaee=INFO',
             '--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl']
    if service == 'document-service':
        args += ['--file.service.url=http://127.0.0.1:18082', '--omnidoc.index.embedding-model=synthetic-embedding-v1', '--omnidoc.index.embedding-dimension=16']
    if service == 'ai-service':
        args += [f'--spring.config.additional-location={ROOT}/scripts/baseline/no-external-models.yml',
                 '--spring.ai.dashscope.embedding.enabled=false', '--document.service.url=http://127.0.0.1:18084',
                 '--omnidoc.index.worker-enabled=true', '--omnidoc.index.poll-ms=500', '--spring.data.redis.database=11',
                 '--ai.vector.mode=baseline', '--ai.vector.dimension=16', '--ai.vector.embedding.model=synthetic-embedding-v1',
                 '--ai.review-m0.persistence.enabled=true', '--ai.review-m0.persistence.schema.auto-migration.enabled=true',
                 '--ai.review-m0.runtime.file-service-base-uri=http://127.0.0.1:18082', '--ai.aiops.scheduling.enabled=false',
                 '--ai.agent.recovery.enabled=true', '--ai.agent.recovery.schema-auto-migration=true']
    if service == 'gateway-service':
        args += [f'--gateway.route.{kind}-uri=http://127.0.0.1:{port}' for kind, port in [('user',18081),('file',18082),('document',18084),('ai',18083)]]
        args += ['--gateway.route.ai-ws-uri=ws://127.0.0.1:18083']
    with (STATE / f'{service}-{time.time_ns()}.private.log').open('wb') as log:
        process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=log, start_new_session=True)
    entry = {'pid': process.pid, 'identity': identity(process.pid), 'artifactSha256': sha(jar)}
    require(entry['identity'] is not None)
    return entry


def start():
    env = environment()
    require('17.' in subprocess.check_output([str(Path(env['JAVA_HOME']) / 'bin/java'), '-version'], stderr=subprocess.STDOUT).decode())
    # Save the build identity before launch; restart rejects every changed artifact.
    manifest = {'artifacts': {k: sha(v) for k, v in artifacts().items()}, 'processes': {}}
    if MANIFEST.exists():
        previous = json.loads(MANIFEST.read_text())
        require(all(identity(e['pid']) is None for e in previous['processes'].values()))
    save(MANIFEST, manifest)
    for service in PORTS:
        manifest['processes'][service] = launch(service, env); save(MANIFEST, manifest)
    for service, entry in manifest['processes'].items():
        # OpenAPI is a real application endpoint; socket-open alone is not readiness.
        if service == 'gateway-service':
            # Gateway has no OpenAPI endpoint. GET on the public POST-only login
            # route verifies both gateway routing and user-service readiness.
            wait_http('http://127.0.0.1:18080/api/users/login', 180, entry, expected=405)
        else:
            wait_http(f'http://127.0.0.1:{PORTS[service]}/v3/api-docs', 180, entry)


def restart(proof):
    path = Path(proof).resolve(); require(path.is_relative_to(ROOT / '.local'))
    manifest = json.loads(MANIFEST.read_text())
    require(manifest['artifacts'] == {k: sha(v) for k, v in artifacts().items()})
    old = manifest['processes']['ai-service']; stop(old)
    new = launch('ai-service', environment())
    manifest['processes']['ai-service'] = new; save(MANIFEST, manifest)
    wait_http('http://127.0.0.1:18083/v3/api-docs', 180, new)
    require(old['pid'] != new['pid'])
    save(path, {'beforePid': old['pid'], 'afterPid': new['pid'], 'artifactSha256': new['artifactSha256']})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['initialize', 'start', 'restart', 'stop'])
    parser.add_argument('--proof'); args = parser.parse_args()
    try:
        require(sys.platform == 'linux')
        if args.action == 'initialize': initialize()
        elif args.action == 'start': start()
        elif args.action == 'restart': restart(args.proof)
        else:
            if MANIFEST.exists():
                for entry in json.loads(MANIFEST.read_text())['processes'].values(): stop(entry)
            if (STATE / 'runtime.env').exists(): compose('stop')
        print(json.dumps({'runtime': args.action, 'status': 'PASS'})); return 0
    except Exception:
        # Exception messages may contain credentials or connection strings.
        print(json.dumps({'runtime': args.action, 'status': 'FAIL'})); return 1


if __name__ == '__main__': raise SystemExit(main())
