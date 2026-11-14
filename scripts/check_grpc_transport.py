"""Reject resolved production deployments that weaken rotating gRPC mTLS."""
import json
import sys
from check_stream_topology import check as check_streams


def check(configuration):
    errors = check_streams(configuration)
    for name in ('order-service', 'inventory-service', 'payment-service'):
        service = configuration.get('services', {}).get(name, {})
        environment = service.get('environment', {})
        if environment.get('SPRING_PROFILES_ACTIVE') != 'prod':
            errors.append(name + ': require the production profile')
        for variable in ('GRPC_MTLS_ENABLED', 'GRPC_SERVER_SECURITY_ENABLED', 'GRPC_CERTIFICATE_RELOAD_ENABLED', 'OMP_GRPC_TLS_RELOAD_ENABLED'):
            if str(environment.get(variable, 'true')).lower() != 'true':
                errors.append(name + ': ' + variable + ' cannot disable transport security')
        for variable in ('GRPC_REFLECTION_ENABLED', 'GRPC_SERVER_ENABLE_REFLECTION'):
            if str(environment.get(variable, 'false')).lower() != 'false':
                errors.append(name + ': production reflection must remain disabled')
        for variable, value in environment.items():
            if variable.endswith('_NEGOTIATION_TYPE') and str(value).upper() != 'TLS':
                errors.append(name + ': plaintext gRPC channel override')
            if variable.endswith('_CLIENT_AUTH') and str(value).upper() != 'REQUIRE':
                errors.append(name + ': mutual authentication override')
            if variable.endswith('_CLIENT_AUTH_ENABLED') and str(value).lower() != 'true':
                errors.append(name + ': client identity override')
        mounts = {mount.get('target'): mount for mount in service.get('volumes', [])}
        for directory in ('/etc/tls/identity', '/etc/tls/trust'):
            if not mounts.get(directory, {}).get('read_only'):
                errors.append(name + ': require read-only directory projection ' + directory)
        for target, mount in mounts.items():
            source = str(mount.get('source', '')).replace('\\', '/')
            if source.endswith('/tls/ca') or source.endswith('/ca.key'):
                errors.append(name + ': issuer private key must never be mounted in workloads')
            if target in ('/etc/tls/tls.crt', '/etc/tls/tls.key', '/etc/tls/ca.crt'):
                errors.append(name + ': file bind mounts pin obsolete certificate inodes')
    return errors


def main():
    try:
        errors = check(json.load(sys.stdin))
    except (ValueError, TypeError, AttributeError) as failure:
        print('Invalid Compose JSON: ' + str(failure), file=sys.stderr)
        return 1
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('Production gRPC requires rotating mTLS, read-only directory projections and one stream replica')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
