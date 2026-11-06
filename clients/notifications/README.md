# @omp/notifications

A TypeScript/ES module client for the platform's authenticated STOMP notifications.
It emits JavaScript and declarations, uses `@stomp/stompjs`, and supports browsers
with native WebSocket plus Node 24+. No package has been published to a registry.

```sh
cd clients/notifications
npm ci --ignore-scripts
npm test
npm pack
```

Install the resulting `omp-notifications-0.1.0.tgz` in a consumer application.
CI builds/tests the package and retains the archive for review.

```typescript
import { NotificationClient } from '@omp/notifications';

const client = new NotificationClient({
  url: 'wss://notification.example.com/ws/websocket',
  getIdentity: async signal => {
    const session = await auth.getFreshSession({ signal });
    return { accessToken: session.accessToken, subject: session.subject,
             expiresAt: session.expiresAtMilliseconds };
  },
  onIdentityChange: () => store.clear(),
  resync: async (destinations, { signal }) => {
    const snapshot = await api.readCurrentState(destinations, { signal });
    if (!signal.aborted) store.replace(snapshot);
  },
  onState: state => renderConnectionState(state),
  onError: code => reportConnectionCode(code),
});

const unsubscribe = client.subscribe('/user/queue/notifications', hint => {
  // Canonical state has been fetched. Hints may be coalesced; do not replay them as mutations.
  renderNotification(hint.eventType);
});
client.connect();
// Later: unsubscribe(); await client.disconnect();
```

`auth`, `api` and `store` above are the consumer's existing authentication, API
and state facilities. The SDK must supply a stable subject and token expiry in
Unix epoch **milliseconds**. Identity fields select local state; the server
validates credentials and resource access. On logout, immediately disconnect and
clear private application state. A changed subject on reconnect clears desired
subscriptions before opening the next socket; `onIdentityChange` lets the
application clear its state and register destinations for the new caller.

Use the native endpoint `/ws/websocket`, not the SockJS base `/ws`. Production
requires `wss:`. Plain `ws:` is allowed only on localhost names with
`allowInsecureLocalhost: true`; credentials, query strings and fragments in broker
URLs are rejected. Credentials travel only in STOMP CONNECT, refresh on each
attempt, and trigger reconnection before expiry. Headers are cleared after CONNECT.
Neither raw frames nor exception text are logged or forwarded to diagnostics.
Broker ERROR frames stop automatic retry; repair credentials/subscriptions and
call `connect()` explicitly. Missing/expired credentials fail before opening a socket.

Each destination shares one wire subscription across independent listeners.
Unsubscribe handles are idempotent, disconnect cancels pending credentials and
state recovery, and callbacks from earlier connections are ignored. Subscription
changes are restored after reconnect; changing identities removes the old set.
Only the documented order/payment/inventory/private destinations are accepted.

The platform's simple broker supplies neither subscription receipts nor durable
replay. After connect, the client sends subscriptions and fetches canonical state.
It also fetches after hints and periodically (30 seconds by default), so a missed
subscription/event gap converges at a later successful read. `live` means that
connection's initial state read succeeded; it is not a receipt or a promise of
complete event history. Slow consumers coalesce to the latest hint per destination.
Do not build financial ledgers or apply state transitions by replaying these hints.

State reads are serialized and rate-limited (100 ms minimum). The default limits
are 100 listeners, 128 buffered destinations, 64 KiB message text and a 15-second
credential/read/connection timeout. Overflow or malformed data reconnects and
resynchronizes. Consecutive failed attempts stop after 12 tries, with exponential
jittered delay capped at 60 seconds. A successful canonical read resets that
failure budget. All limits are configurable positive integers bounded by the
JavaScript timer range. A rejected/hung state read never announces a recovered
connection. Recovery callbacks must honor the supplied AbortSignal before writing
state; the library cannot undo an external store mutation that ignores cancellation.

`npm test` runs strict compilation and 17 real WebSocket/STOMP transport cases.
These use a controlled broker with no receipts, rather than replacing the client
transport with mocks. Production authorization, issuer and event delivery are also
covered by the separately deployed Java suites. `npm run test:deployed` requires a
fresh isolated platform and verifies the package against its actual issuer,
ownership policy, event pipeline and reconnect state reads. It is never silently
included/skipped when no deployment is available.

Deployed test overrides are `KEYCLOAK_URL`, `ORDER_SERVICE_URL`,
`NOTIFICATION_STOMP_URL` (native `/ws/websocket`), and the development
`E2E_*` credential/client variables used by the Java suites. `NOTIFICATION_WS_URL`
belongs to the Java SockJS client and uses the `/ws` base instead.
