"""Disposable CA overlap/retirement controls; deliberately unavailable to production projects."""
import argparse
import os
from pathlib import Path
import re
import shutil
from rotate_grpc_certificates import openssl, publish, renew


def run(phase, issuer):
    if os.environ.get('E2E_GRPC_CONTROLS') != 'true' or not re.fullmatch(r'omp-grpc-acceptance-[a-f0-9]{12}', os.environ.get('COMPOSE_PROJECT_NAME', '')):
        raise ValueError('CA acceptance controls require an opted-in disposable project')
    issuer = issuer.resolve(strict=True)
    if issuer != Path(os.environ['E2E_GRPC_ISSUER_DIRECTORY']).resolve(strict=True):
        raise ValueError('Issuer directory does not belong to the selected acceptance project')
    next_ca = issuer / 'next-ca'
    expanded = issuer / 'expanded-ca.crt'
    if phase == 'prepare':
        next_ca.mkdir(mode=0o700)  # Refuse a repeated/incomplete CA transition.
        retired = issuer / 'retired-client'
        retired.mkdir(mode=0o700)
        shutil.copyfile(issuer / 'order-service/tls.crt', retired / 'tls.crt')
        shutil.copyfile(issuer / 'order-service/tls.key', retired / 'tls.key')
        os.chmod(retired / 'tls.key', 0o600)
        if hasattr(os, 'chown'):
            consumer = issuer.stat()
            for path in (retired, retired / 'tls.key', retired / 'tls.crt'):
                os.chown(path, consumer.st_uid, consumer.st_gid)
        openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '60', '-subj', '/CN=Acceptance replacement root',
                '-addext', 'basicConstraints=critical,CA:TRUE', '-keyout', next_ca / 'ca.key', '-out', next_ca / 'ca.crt')
        os.chmod(next_ca / 'ca.key', 0o600)
        expanded.write_bytes((issuer / 'ca/ca.crt').read_bytes() + (next_ca / 'ca.crt').read_bytes())
        publish(expanded, issuer / 'trust/ca.crt')
    elif phase == 'activate':
        if not expanded.is_file():
            raise ValueError('Expand trust and await all consumers before activating the new issuer')
        publish(next_ca / 'ca.key', issuer / 'ca/ca.key', private=True)
        os.chmod(issuer / 'ca/ca.key', 0o600)
        publish(next_ca / 'ca.crt', issuer / 'ca/ca.crt')
        renew(issuer, force=True, trust_bundle=expanded)
    elif phase == 'retire':
        for service in ('order-service', 'inventory-service', 'payment-service'):
            openssl('verify', '-CAfile', next_ca / 'ca.crt', issuer / service / 'tls.crt')
        publish(next_ca / 'ca.crt', issuer / 'trust/ca.crt')
    else:
        raise ValueError('Unknown CA acceptance phase')
    print('Disposable CA acceptance phase completed: ' + phase)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('prepare', 'activate', 'retire'))
    parser.add_argument('--tls-dir', type=Path, required=True)
    args = parser.parse_args()
    run(args.phase, args.tls_dir)


if __name__ == '__main__':
    main()
