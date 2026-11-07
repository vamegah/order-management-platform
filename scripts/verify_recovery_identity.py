from pathlib import Path
import base64
import hashlib
import importlib.util
import json
import os
import secrets
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request, build_opener
import uuid

import argparse


def main():
    root = Path(__file__).resolve().parents[1]
    project = root
    script = project / 'scripts/project_service_token.py'
    realm = project / 'infrastructure/keycloak/import/omp-realm.json'
    inputs = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (script, realm, Path(__file__).resolve())}
    spec = importlib.util.spec_from_file_location('identity_projector', script)
    projector = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = projector
    spec.loader.exec_module(projector)
    parser = argparse.ArgumentParser(description='Verify a scoped recovery identity with a fresh, test-owned Keycloak issuer')
    parser.add_argument('--docker', default='docker')
    parser.add_argument('--report', type=Path, default=Path('coverage/recovery-identity-issuer.json'))
    args = parser.parse_args()
    docker = args.docker
    name = 'omp-recovery-identity-check-' + uuid.uuid4().hex[:12]
    environment = {**os.environ, 'KEYCLOAK_ADMIN_PASSWORD': secrets.token_urlsafe(32)}
    directory = root / '.scratch' / name
    directory.mkdir(parents=True)
    credential = directory / 'client-credential'
    credential.write_text('dev-only-order-recovery-secret', encoding='utf-8')
    credential.chmod(0o600)
    output = directory / 'projection' / 'access.jwt'
    opener = build_opener(ProxyHandler({}), projector.NoRedirects())
    checks = []
    started = False

    def command(*args):
        return subprocess.check_output([docker, *args], env=environment, stderr=subprocess.PIPE).decode().strip()

    def claims(token):
        value = token.split('.')[1]
        return json.loads(base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)))

    try:
        command('run', '--detach', '--name', name, '--label', 'omp.task=recovery-identity-verification',
                '--memory', '768m', '--cpus', '2', '--publish', '127.0.0.1::8080',
                '--env', 'KEYCLOAK_ADMIN=identity-test-admin', '--env', 'KEYCLOAK_ADMIN_PASSWORD',
                '--mount', 'type=bind,source=' + str(realm.parent) + ',target=/opt/keycloak/data/import,readonly',
                'quay.io/keycloak/keycloak:25.0', 'start-dev', '--import-realm', '--hostname-strict=false')
        started = True
        state = json.loads(command('inspect', name))[0]
        port = state['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
        origin = 'http://127.0.0.1:' + port
        discovery = origin + '/realms/omp/.well-known/openid-configuration'
        deadline = time.monotonic() + 180
        while True:
            try:
                with opener.open(discovery, timeout=5) as response:
                    metadata = json.load(response)
                break
            except (HTTPError, URLError, TimeoutError, ConnectionError):
                if time.monotonic() > deadline:
                    raise RuntimeError('Dedicated test issuer did not become ready')
                time.sleep(1)
        settings = projector.Settings(origin + '/realms/omp/protocol/openid-connect/token', metadata['issuer'],
                                      'order-recovery', credential, output, True)
        projector.project_once(settings, opener=opener)
        first = output.read_text()
        parsed = claims(first)
        assert parsed['scope'].split() == ['order.reconcile'], parsed['scope']
        assert 'customer_id' not in parsed
        assert not parsed.get('realm_access', {}).get('roles')
        checks.append('Actual imported Keycloak issues a scoped service-account JWT without customer/admin roles')
        projector.project_once(settings, opener=opener)
        second = output.read_text()
        assert first != second and parsed['jti'] != claims(second)['jti']
        checks.append('Repeated client credentials exchange rotates JWT identity and atomically replaces projection')
        credential.write_text('wrong-test-credential', encoding='utf-8')
        try:
            projector.project_once(settings, opener=opener)
            raise AssertionError('Invalid client credential was accepted')
        except HTTPError as rejected:
            assert rejected.code in (400, 401)
        assert output.read_text() == second
        checks.append('Invalid client credential is rejected without replacing the previous projection')
        credential.write_text('dev-only-order-recovery-secret', encoding='utf-8')
        request = Request(settings.token_url, data=urlencode({'grant_type': 'password', 'client_id': 'postman',
                          'username': 'alice', 'password': 'alice-dev-password', 'scope': 'order.reconcile'}).encode(),
                          headers={'Content-Type': 'application/x-www-form-urlencoded'})
        try:
            with opener.open(request, timeout=10) as response:
                ordinary = json.load(response)
            assert 'order.reconcile' not in claims(ordinary['access_token']).get('scope', '').split()
        except HTTPError as rejected:
            assert rejected.code in (400, 403)
        checks.append('Ordinary password-grant client cannot request the privileged recovery scope')
        with opener.open(metadata['jwks_uri'], timeout=10) as response:
            jwks = json.load(response)
        header = first.split('.')[0]
        header = json.loads(base64.urlsafe_b64decode(header + '=' * (-len(header) % 4)))
        assert header['alg'] == 'RS256' and any(key['kid'] == header['kid'] for key in jwks['keys'])
        checks.append('Issued JWT names an RS256 signing key published by its issuer; downstream cryptographic acceptance remains separate')
        for relative, checksum in inputs.items():
            assert hashlib.sha256((root / relative).read_bytes()).hexdigest() == checksum
        record = {'scope': 'Dedicated imported Keycloak issuer and projection acceptance, not downstream recovery or process-crash acceptance',
                  'source_inputs': inputs, 'container': name, 'image': state['Image'], 'checks': checks,
                  'claim_summary': {'azp': parsed['azp'], 'scope': parsed['scope'], 'aud': parsed['aud'],
                                    'customer_claim_present': False, 'realm_roles': []},
                  'tokens_or_credentials_recorded': False}
        report = (root / args.report).resolve()
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(record, indent=2), encoding='utf-8')
        print(json.dumps({'checks': len(checks), 'container': name, 'scope': record['scope']}), flush=True)
    finally:
        if started:
            owned = json.loads(command('inspect', name))[0]
            assert owned['Name'] == '/' + name
            assert owned['Config']['Labels']['omp.task'] == 'recovery-identity-verification'
            command('rm', '--force', name)
        credential.unlink(missing_ok=True)
        output.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
