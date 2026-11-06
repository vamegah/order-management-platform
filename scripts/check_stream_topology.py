"""Reject deployments that split process-local gRPC stream subscriptions.

Read resolved Docker Compose JSON from stdin. Run after merging every deployment
overlay, so replica overrides and update/rollback overlap cannot bypass the gate.
"""
import json
import sys

SERVICES = ("inventory-service", "payment-service")


def check(configuration):
    errors = []
    for name in SERVICES:
        service = configuration.get("services", {}).get(name, {})
        deploy = service.get("deploy", {})
        if deploy.get("mode", "replicated") != "replicated":
            errors.append(f"{name}: only replicated mode is supported")
        replicas = deploy.get("replicas")
        if type(replicas) is not int or replicas != 1:
            errors.append(f"{name}: deploy.replicas must explicitly equal 1")
        if service.get("scale", 1) != 1:
            errors.append(f"{name}: scale must equal 1")
        for operation in ("update_config", "rollback_config"):
            policy = deploy.get(operation, {})
            if policy.get("order") != "stop-first" or policy.get("parallelism") != 1:
                errors.append(f"{name}: {operation} requires stop-first and parallelism 1")
    return errors


def main():
    try:
        errors = check(json.load(sys.stdin))
    except (ValueError, TypeError, AttributeError) as failure:
        print(f"Invalid Compose JSON: {failure}", file=sys.stderr)
        return 1
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Inventory/payment use one replica and stop-first updates/rollbacks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
