"""Renew and atomically distribute gRPC leaves without restarting application processes.

Run on the protected issuer host, never inside an application container. The CA
private key is read only by openssl. Mount service/trust directories in consumers,
not individual files: file bind mounts pin old inodes after atomic replacement.
"""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time

SERVICES = ('order-service', 'inventory-service', 'payment-service')


def openssl(*arguments, input_data=None):
    result = subprocess.run([os.environ.get('OPENSSL', 'openssl'), *map(str, arguments)],
                            input=input_data, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Certificate operation failed; no unvalidated credentials were published')
    return result.stdout


def publish(source, target, private=False):
    """Replace one file on the same filesystem, preserving the consumer's ownership."""
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o750)
    descriptor, temporary = tempfile.mkstemp(prefix='.projection-', dir=target.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            os.fchmod(output.fileno(), 0o640 if private else 0o644) if hasattr(os, 'fchmod') else None
            output.write(source.read_bytes())
            output.flush()
            os.fsync(output.fileno())
        if target.exists() and hasattr(os, 'chown'):
            previous = target.stat()
            os.chown(temporary, previous.st_uid, previous.st_gid)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def _renew(tls_dir, renew_before=7, lifetime=30, force=False, trust_bundle=None):
    tls_dir = tls_dir.resolve(strict=True)
    if lifetime < 1 or not 0 <= renew_before < lifetime:
        raise ValueError('Require 0 <= renew-before-days < lifetime-days')
    ca_certificate = tls_dir / 'ca/ca.crt'
    ca_key = tls_dir / 'ca/ca.key'
    if not ca_certificate.is_file() or not ca_key.is_file():
        raise ValueError('Issuer requires an existing CA certificate and protected private key')
    trust = Path(trust_bundle).resolve(strict=True) if trust_bundle else ca_certificate
    updated = []
    # Preflight every identity before replacing any credentials. Never overwrite an incomplete pair.
    for service in SERVICES:
        certificate = tls_dir / service / 'tls.crt'
        key = tls_dir / service / 'tls.key'
        if not certificate.is_file() or not key.is_file():
            raise ValueError('Restore complete existing identities before renewal')
        openssl('verify', '-no_check_time', '-CAfile', trust, '-verify_hostname', service, certificate)
        public = openssl('x509', '-in', certificate, '-pubkey', '-noout')
        if openssl('pkey', '-pubin', '-outform', 'DER', input_data=public) != openssl('pkey', '-in', key, '-pubout', '-outform', 'DER'):
            raise ValueError('Existing identity key mismatch')
    # Trust expansion is distributed first; existing leaves must validate in the new bundle.
    publish(trust, tls_dir / 'trust/ca.crt')
    for service in SERVICES:
        certificate = tls_dir / service / 'tls.crt'
        check = subprocess.run([os.environ.get('OPENSSL', 'openssl'), 'x509', '-in', str(certificate),
                                '-noout', '-checkend', str(renew_before * 86400)], capture_output=True, timeout=10)
        if not force and check.returncode == 0:
            continue
        with tempfile.TemporaryDirectory(prefix='.renew-', dir=tls_dir) as temporary:
            stage = Path(temporary)
            key, csr, cert, extensions = (stage / name for name in ('tls.key', 'tls.csr', 'tls.crt', 'extensions'))
            openssl('genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', key)
            os.chmod(key, 0o600)
            openssl('req', '-new', '-key', key, '-subj', '/CN=' + service, '-out', csr)
            extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
                                  'extendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:' + service + '\n')
            openssl('x509', '-req', '-in', csr, '-CA', ca_certificate, '-CAkey', ca_key,
                    '-set_serial', str(secrets.randbits(128)), '-days', str(lifetime), '-sha256', '-extfile', extensions, '-out', cert)
            openssl('verify', '-CAfile', trust, '-verify_hostname', service, cert)
            # Each rename is atomic. The watcher rejects the short unmatched-pair interval.
            publish(key, tls_dir / service / 'tls.key', private=True)
            publish(cert, certificate)
            updated.append(service)
    return updated


def renew(tls_dir, renew_before=7, lifetime=30, force=False, trust_bundle=None):
    root = tls_dir.resolve(strict=True)
    with (root / '.grpc-renewal.lock').open('a+b') as lock:
        if os.name == 'nt':
            import msvcrt
            if lock.tell() == 0:
                lock.write(b'0')
                lock.flush()
            lock.seek(0)
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as failure:
                raise RuntimeError('Another issuer renewal is active') from failure
        else:
            import fcntl
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as failure:
                raise RuntimeError('Another issuer renewal is active') from failure
        try:
            return _renew(root, renew_before, lifetime, force, trust_bundle)
        finally:
            if os.name == 'nt':
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tls-dir', type=Path, required=True)
    parser.add_argument('--renew-before-days', type=int, default=7)
    parser.add_argument('--lifetime-days', type=int, default=30)
    parser.add_argument('--trust-bundle', type=Path)
    parser.add_argument('--force', action='store_true', help='Force a one-shot renewal, intended for acceptance')
    parser.add_argument('--watch', action='store_true')
    parser.add_argument('--interval-seconds', type=int, default=3600)
    args = parser.parse_args()
    if args.interval_seconds < 1 or (args.watch and args.force):
        parser.error('Use a positive interval; force is a one-shot operation')
    while True:
        updated = renew(args.tls_dir, args.renew_before_days, args.lifetime_days, args.force, args.trust_bundle)
        print('gRPC renewal completed; renewed identities: ' + ', '.join(updated), flush=True)
        if not args.watch:
            return
        time.sleep(args.interval_seconds)


if __name__ == '__main__':
    main()
