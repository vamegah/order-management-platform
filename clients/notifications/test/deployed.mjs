import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setDefaultResultOrder } from 'node:dns';
import { NotificationClient } from '../dist/index.js';

// Requires a dedicated test stack. Never skips or silently starts/mutates another deployment.
setDefaultResultOrder('ipv4first');
const issuer = process.env.KEYCLOAK_URL ?? 'http://keycloak.localhost';
const orders = process.env.ORDER_SERVICE_URL ?? 'http://order-service.localhost';
const broker = process.env.NOTIFICATION_STOMP_URL ?? 'ws://notification.localhost/ws/websocket';
const info = broker.replace(/^ws/, 'http').replace(/\/websocket$/, '/info');
const customer = '00000000-0000-0000-0000-000000000101';
const bob = '00000000-0000-0000-0000-000000000102';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate) {
  const deadline = Date.now() + 30000;
  while (!predicate()) { if (Date.now() > deadline) throw new Error('Deployed client condition timed out'); await pause(50); }
}
async function credentials(who = 'ALICE', signal = new AbortController().signal) {
  const username = process.env[`E2E_${who}_USERNAME`] ?? who.toLowerCase();
  const password = process.env[`E2E_${who}_PASSWORD`] ?? username + '-dev-password';
  const response = await fetch(issuer + '/realms/omp/protocol/openid-connect/token', {
    method: 'POST', signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
    body: new URLSearchParams({ grant_type: 'password', client_id: process.env.E2E_CLIENT_ID ?? 'postman', username, password }),
  });
  assert.equal(response.status, 200, 'Development identity endpoint');
  const body = await response.json();
  // Only for refresh/cache identity. Resource servers validate signatures and ownership.
  const payload = JSON.parse(Buffer.from(body.access_token.split('.')[1], 'base64url').toString());
  assert.ok(payload.sub, 'Development realm must include a stable subject');
  return { accessToken: body.access_token, subject: payload.sub, expiresAt: payload.exp * 1000 };
}
async function api(path, identity, method = 'GET', body, signal = new AbortController().signal) {
  const response = await fetch(orders + '/api/v1/orders' + path, {
    method, signal: AbortSignal.any([signal, AbortSignal.timeout(20000)]),
    headers: { Authorization: 'Bearer ' + identity.accessToken, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return response;
}
async function create(identity, customerId = customer) {
  const response = await api('', identity, 'POST', { customerId,
    shippingAddress: { line1: '1 Client Test Way', city: 'Oakland', postalCode: '94607', country: 'US' },
    lines: [{ productId: '00000000-0000-0000-0000-000000000204', quantity: 1 }],
    notes: 'notification-client-' + randomUUID() });
  assert.equal(response.status, 201, 'Independent client test order');
  return response.json();
}
async function cancel(order, identity) {
  const response = await api('/' + order.id + '/cancel', identity, 'POST', { reason: 'notification client test cleanup' });
  assert.equal(response.status, 200, 'Cancel/retry cleanup');
}

const ready = await fetch(info, { signal: AbortSignal.timeout(15000) });
assert.equal(ready.status, 200, 'Notification ingress must be ready');
assert.equal((await ready.json()).websocket, true);

test('actual issuer, native STOMP, event pipeline and reconnect converge canonical order state', { timeout: 90000 }, async () => {
  let identity = await credentials();
  const order = await create(identity);
  let snapshot, acquired = 0;
  const hints = [];
  const client = new NotificationClient({ url: broker, allowInsecureLocalhost: true,
    getIdentity: async signal => { acquired++; identity = await credentials('ALICE', signal); return identity; },
    resyncIntervalMs: 1000,
    resync: async (_, { signal }) => {
      const response = await api('/' + order.id, identity, 'GET', undefined, signal);
      assert.equal(response.status, 200);
      const body = await response.json();
      if (!signal.aborted) snapshot = body.status;
    } });
  client.subscribe('/topic/orders/' + order.id, hint => hints.push(hint));
  try {
    client.connect(); await until(() => client.state === 'live' && snapshot === 'DRAFT');
    const placed = await api('/' + order.id + '/place', identity, 'POST', {});
    assert.equal(placed.status, 200);
    await until(() => snapshot === 'PLACED' && hints.some(hint => hint.data.orderId === order.id));
    await client.disconnect();
    const delivered = hints.length;
    await cancel(order, identity);
    assert.equal(snapshot, 'PLACED', 'Offline state is not fabricated from a missing event');
    assert.equal(hints.length, delivered);
    client.connect();
    await until(() => acquired >= 2 && client.state === 'live' && snapshot === 'CANCELLED');
  } finally { await client.disconnect(); await cancel(order, identity); }
});

test('actual ownership policy rejects a foreign subscription and the package stops retrying', { timeout: 90000 }, async () => {
  const administrator = await credentials('ADMIN');
  const identity = await credentials();
  const foreign = await create(administrator, bob);
  const errors = [];
  let delivered = 0;
  const client = new NotificationClient({ url: broker, allowInsecureLocalhost: true,
    getIdentity: signal => credentials('ALICE', signal), resync: async () => {}, onError: code => errors.push(code) });
  try {
    const denied = await api('/' + foreign.id, identity);
    assert.equal(denied.status, 403);
    client.subscribe('/topic/orders/' + foreign.id, () => delivered++);
    client.connect(); await until(() => client.state === 'failed');
    assert.ok(errors.includes('BROKER_REJECTED'));
    assert.equal(delivered, 0);
  } finally { await client.disconnect(); await cancel(foreign, administrator); }
});
