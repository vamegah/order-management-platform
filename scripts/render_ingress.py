"""Render fixed production routes without granting ingress access to the Docker API."""
import argparse, json, re
from pathlib import Path


def routes(domain):
    if (
        len(domain) > 253
        or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", domain)
        or ".." in domain
    ):
        raise ValueError("Invalid deployment domain")
    services = {
        "product": {
            "loadBalancer": {"servers": [{"url": "http://product-service:8081"}]}
        },
        "customer": {
            "loadBalancer": {"servers": [{"url": "http://customer-service:8082"}]}
        },
        "order": {"loadBalancer": {"servers": [{"url": "http://order-service:8083"}]}},
        "payment": {
            "loadBalancer": {"servers": [{"url": "http://payment-service:8080"}]}
        },
        "notification": {
            "loadBalancer": {"servers": [{"url": "http://notification-service:8084"}]}
        },
        "graphql": {
            "loadBalancer": {"servers": [{"url": "http://graphql-gateway:8000"}]}
        },
        "banking": {
            "loadBalancer": {"servers": [{"url": "http://legacy-banking-adapter:8085"}]}
        },
        "identity": {"loadBalancer": {"servers": [{"url": "http://keycloak:8080"}]}},
        "grafana": {"loadBalancer": {"servers": [{"url": "http://grafana:3000"}]}},
        "prometheus": {
            "loadBalancer": {"servers": [{"url": "http://prometheus:9090"}]}
        },
    }
    routers = {}

    def add(name, service, rule, admin=False):
        routers[name] = {
            "rule": rule,
            "service": service,
            "entryPoints": ["websecure"],
            "tls": {"certResolver": "le"},
            "middlewares": ["security-policy"] + (["admin-auth"] if admin else []),
        }

    api = "Host(`api." + domain + "`)"
    for service, prefixes in {
        "product": ["products", "categories"],
        "customer": ["customers"],
        "order": [
            "orders",
            "order-cleanup",
            "order-lifecycle",
            "order-recovery",
            "order-reservation-repairs",
        ],
        "payment": ["payments"],
        "notification": [
            "webhook-subscriptions",
            "webhook-deliveries",
            "webhook-dlq",
            "event-recovery",
        ],
        "banking": ["banking"],
    }.items():
        rule = (
            api
            + " && ("
            + " || ".join("PathPrefix(`/api/v1/" + prefix + "`)" for prefix in prefixes)
            + ")"
        )
        add(service, service, rule)
    add("websocket", "notification", api + " && (Path(`/ws`) || PathPrefix(`/ws/`))")
    add("graphql", "graphql", api + " && Path(`/graphql`)")
    add(
        "identity",
        "identity",
        "Host(`auth."
        + domain
        + "`) && (PathPrefix(`/realms/omp/`) || PathPrefix(`/resources/`) || PathPrefix(`/js/`))",
    )
    add("identity-admin", "identity", "Host(`keycloak-admin." + domain + "`)", True)
    for service in ("grafana", "prometheus"):
        add(service, service, "Host(`" + service + "." + domain + "`)", True)
    add("dashboard", "api@internal", "Host(`traefik." + domain + "`)", True)
    return {
        "http": {
            "routers": routers,
            "services": services,
            "middlewares": {
                "admin-auth": {
                    "basicAuth": {
                        "usersFile": "/run/secrets/ingress_admin_users",
                        "removeHeader": True,
                    }
                }
            },
        },
        "tls": {
            "options": {"default": {"minVersion": "VersionTLS12", "sniStrict": True}}
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.output.open("x", encoding="utf-8") as target:
        json.dump(routes(args.domain), target, indent=2)
    print('{"rendered":true,"dockerApiAccess":false}')


if __name__ == "__main__":
    main()
