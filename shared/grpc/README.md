# gRPC Spring Boot integration

Applications keep their existing net.devh client/server transport dependencies.
This module does not introduce a server into client-only applications.

Boot 4 integration replaces the starter's legacy security auto-configuration,
which references the removed `AccessDecisionManager` API. Applications still
provide their authentication manager and credential reader. The same global
authentication and exception interceptors propagate caller identity and reject
invalid credentials. Method security and service owner/scope checks remain in
the applications.

The module also preserves channel health through Boot 4's health API and orders
the existing client/server metrics integrations after Boot 4's meter registry.
It does not change mTLS, credentials, RPC definitions, stream behavior or deadlines.

Run `./mvnw -pl shared/grpc -am verify` for the transport integration regressions
and the 80% instruction gate. Service and external suites remain required.
