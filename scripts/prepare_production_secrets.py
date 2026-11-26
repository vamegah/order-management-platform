"""Generate fresh mounted runtime credentials without overwriting files or printing secret values."""
import argparse, base64, hashlib, json, os, secrets, stat
from pathlib import Path

PASSWORDS = (
    "bank_ledger_svc_password",
    "bank_adapter_svc_password",
    "postgres_superuser_password",
    "postgres_exporter_password",
    "products_svc_password",
    "customers_svc_password",
    "orders_svc_password",
    "inventory_svc_password",
    "payments_svc_password",
    "notifications_svc_password",
    "keycloak_db_password",
    "keycloak_admin_password",
    "grafana_admin_password",
    "redis_password",
    "quota_redis_password",
    "redis_notifications_password",
    "redis_sentinel_password",
    "redis_monitor_password",
    "kafka_broker_password",
    "kafka_admin_password",
    "kafka_monitor_password",
    "pgbackrest_repo_cipher",
    "alert_receiver_token",
)
CLIENTS = {
    "order-service": "orders_svc",
    "inventory-service": "inventory_svc",
    "payment-service": "payments_svc",
    "notification-service": "notifications_svc",
}


def prepare(directory, htpasswd, owner=None, group=None):
    directory = Path(directory)
    if (
        not directory.is_absolute()
        or directory.is_symlink()
        or directory.resolve() != directory.absolute()
    ):
        raise ValueError("An absolute non-symlink secret directory is required")
    if directory.exists() and any(directory.iterdir()):
        raise ValueError(
            "Refusing to overwrite existing credentials; use the rotation runbook"
        )
    users = Path(htpasswd).read_text(encoding="utf-8").strip()
    import re

    if not re.fullmatch(
        r"[A-Za-z0-9_.-]{1,64}:\$2[aby]\$(?:12|13|14|15|16)\$[./A-Za-z0-9]{53}", users
    ):
        raise ValueError(
            "Provide exactly one externally prepared bcrypt htpasswd entry"
        )
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    values = {name: secrets.token_urlsafe(32) for name in PASSWORDS}
    values.update(
        {name + "_kafka_password": secrets.token_urlsafe(32) for name in CLIENTS}
    )

    def jaas(user, password):
        return (
            'org.apache.kafka.common.security.scram.ScramLoginModule required username="'
            + user
            + '" password="'
            + password
            + '";'
        )

    for name, user in CLIENTS.items():
        values[name + "_kafka_jaas"] = jaas(user, values[name + "_kafka_password"])
    values["kafka_admin_properties"] = (
        "security.protocol=SASL_PLAINTEXT\nsasl.mechanism=SCRAM-SHA-512\nsasl.jaas.config="
        + jaas("omp_admin", values["kafka_admin_password"])
        + "\n"
    )
    values["kafka_cluster_id"] = (
        base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")
    )
    users_for_scram = {
        "broker": values["kafka_broker_password"],
        "omp_admin": values["kafka_admin_password"],
        "omp_monitor": values["kafka_monitor_password"],
    } | {user: values[name + "_kafka_password"] for name, user in CLIENTS.items()}
    records = []
    for user, password in users_for_scram.items():
        salt = secrets.token_bytes(32)
        salted = hashlib.pbkdf2_hmac("sha512", password.encode(), salt, 8192)
        records.append(
            "SCRAM-SHA-512=[name="
            + user
            + ',iterations=8192,salt="'
            + base64.b64encode(salt).decode()
            + '",saltedpassword="'
            + base64.b64encode(salted).decode()
            + '"]'
        )
    values["kafka_scram_bootstrap"] = "\n".join(records) + "\n"
    values["redis_exporter_passwords"] = json.dumps(
        {"redis://redis:6379": values["redis_monitor_password"]}
    )
    values["ingress_admin_users"] = users + "\n"
    # Directory config trees use the same database credentials as PostgreSQL bootstrap.
    bank_password = secrets.token_urlsafe(32)
    values.update(
        {
            "bank-ledger/spring.datasource.password": values[
                "bank_ledger_svc_password"
            ],
            "bank-adapter/spring.datasource.password": values[
                "bank_adapter_svc_password"
            ],
            "bank-ledger/bank.security.password": bank_password,
            "bank-adapter/bank.ws.password": bank_password,
            "bank-ledger/server.ssl.key-store-password": secrets.token_urlsafe(32),
            "bank-adapter/bank.ws.trust-store-password": secrets.token_urlsafe(32),
        }
    )
    for child in ("bank-ledger", "bank-adapter"):
        folder = directory / child
        folder.mkdir(mode=0o750)
        if owner is not None and group is not None:
            os.chown(folder, owner, group)
    created = []
    for name, value in values.items():
        target = directory / name
        fd = os.open(
            target,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
            0o440,
        )
        try:
            if owner is not None and group is not None:
                os.fchown(fd, owner, group)
            with os.fdopen(fd, "wb", closefd=False) as output:
                output.write(value.encode())
        finally:
            os.close(fd)
        created.append(name)
    # Provider credentials must be supplied by the operator; no fabricated Stripe credentials.
    return {
        "created": sorted(created),
        "requiredExternalFiles": ["stripe_api_key", "stripe_webhook_secret"],
        "credentialValuesPrinted": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--htpasswd-file", type=Path, required=True)
    parser.add_argument("--runtime-gid", type=int, default=2000)
    args = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        raise ValueError(
            "Run setup as root on the Linux deployment host; runtime processes use group 2000"
        )
    print(json.dumps(prepare(args.directory, args.htpasswd_file, 0, args.runtime_gid)))


if __name__ == "__main__":
    main()
