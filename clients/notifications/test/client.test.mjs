import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { NotificationClient } from '../dist/index.js';

const destination = '/topic/orders/00000000-0000-0000-0000-000000000001';
const other = '/topic/orders/00000000-0000-0000-0000-000000000002';
const envelope = JSON.stringify({ eventType: 'ORDER_PLACED', occurredAt: new Date().toISOString(), data: { orderId: '1' } });
const tick = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate) {
  const deadline = Date.now() + 4000;
  while (!predicate()) { if (Date.now() > deadline) throw new Error('Condition timed out'); await tick(5); }
}

async function broker(t) {
  const server = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(server, 'listening');
  const sessions = [];
  const frames = [];
  server.on('connection', socket => {
    const session = { socket, subscriptions: new Map() };
    sessions.push(session);
    socket.on('message', bytes => {
      for (const part of bytes.toString().split('\0')) {
        const text = part.replace(/^\n+/, '');
        if (!text) continue;
        const [head] = text.split('\n\n');
        const [command, ...lines] = head.split('\n');
        const headers = Object.fromEntries(lines.map(line => { const i = line.indexOf(':'); return [line.slice(0,i),line.slice(i+1)]; }));
        frames.push({ session, command, headers });
        if (command === 'CONNECT') socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
        if (command === 'SUBSCRIBE') session.subscriptions.set(headers.id, headers.destination);
        if (command === 'UNSUBSCRIBE') session.subscriptions.delete(headers.id);
      }
    });
  });
  t.after(async () => { for (const session of sessions) session.socket.terminate(); await new Promise(resolve => server.close(resolve)); });
  function send(body = envelope, target = destination) {
    for (const session of sessions) for (const [id, name] of session.subscriptions) {
      if (name === target && session.socket.readyState === 1) session.socket.send(`MESSAGE\nsubscription:${id}\nmessage-id:event\ndestination:${name}\ncontent-type:application/json\n\n${body}\0`);
    }
  }
  return { sessions, frames, send, url: `ws://127.0.0.1:${server.address().port}/ws/websocket` };
}
function client(t, wire, options = {}) {
  const instance = new NotificationClient({ url: wire.url, allowInsecureLocalhost: true,
    getIdentity: async () => ({ accessToken: 'first-token', subject: 'owner', expiresAt: Date.now() + 60000 }), resync: async () => {},
    minRetryDelayMs: 10, maxRetryDelayMs: 30, minResyncIntervalMs: 5, resyncIntervalMs: 40,
    timeoutMs: 300, maxAttempts: 5, ...options });
  t.after(() => instance.disconnect());
  return instance;
}

test('reconnect refreshes credentials, restores subscriptions and reconciles offline changes', async t => {
  const wire = await broker(t);
  let tokens = 0, canonical = 1, visible;
  const instance = client(t, wire, { getIdentity: async () => ({ accessToken: `token-${++tokens}`, subject: 'owner', expiresAt: Date.now() + 60000 }),
    resync: async (_, { signal }) => { if (!signal.aborted) visible = canonical; } });
  instance.subscribe(destination, () => {});
  instance.connect();
  await until(() => instance.state === 'live' && wire.sessions[0]?.subscriptions.size === 1);
  assert.equal(visible, 1);
  canonical = 2;
  wire.sessions[0].socket.close();
  await until(() => tokens >= 2 && visible === 2 && wire.sessions[1]?.subscriptions.size === 1);
  const credentials = wire.frames.filter(frame => frame.command === 'CONNECT').map(frame => frame.headers.Authorization);
  assert.deepEqual(credentials.slice(0, 2), ['Bearer token-1', 'Bearer token-2']);
});

test('listeners share a wire subscription, unsubscribe independently and reconcile before hints', async t => {
  const wire = await broker(t);
  let canonical = 1, visible = 0, first = 0, second = 0;
  const instance = client(t, wire, { resync: async () => { visible = canonical; } });
  const removeFirst = instance.subscribe(destination, () => { assert.equal(visible, canonical); first++; });
  const removeSecond = instance.subscribe(destination, () => { second++; });
  instance.connect();
  await until(() => instance.state === 'live' && wire.sessions[0]?.subscriptions.size === 1);
  canonical = 2; wire.send();
  await until(() => first === 1 && second === 1);
  assert.equal(wire.frames.filter(frame => frame.command === 'SUBSCRIBE').length, 1);
  removeFirst(); removeFirst(); wire.send();
  await until(() => second === 2);
  assert.equal(first, 1);
  removeSecond();
  await until(() => wire.sessions[0].subscriptions.size === 0);
});

test('periodic canonical reads repair a missed subscription/event gap without receipts', async t => {
  const wire = await broker(t);
  let canonical = 1, visible;
  const instance = client(t, wire, { resync: async () => { visible = canonical; } });
  instance.subscribe(destination, () => {});
  instance.connect(); await until(() => visible === 1);
  canonical = 7; // The simple broker deliberately provides no receipt and no replay.
  await until(() => visible === 7);
});

test('identity switch clears old destinations and cancels old state recovery', async t => {
  const wire = await broker(t);
  let identity = 'alice', changed, snapshot;
  const instance = client(t, wire, { getIdentity: async () => ({ accessToken: identity, subject: identity, expiresAt: Date.now() + 60000 }),
    onIdentityChange: subject => { changed = subject; snapshot = undefined; },
    resync: async (destinations, { subject, signal }) => { if (!signal.aborted) snapshot = { subject, destinations }; } });
  instance.subscribe(destination, () => assert.fail('Previous identity listener must not run'));
  instance.connect(); await until(() => wire.sessions[0]?.subscriptions.size === 1 && instance.state === 'live');
  identity = 'bob'; wire.sessions[0].socket.close();
  await until(() => snapshot?.subject === 'bob');
  assert.equal(changed, 'bob');
  assert.deepEqual(snapshot.destinations, []);
  assert.equal(wire.sessions[1].subscriptions.size, 0);
});

test('disconnect cancels a late credential response before any socket is opened', async t => {
  const wire = await broker(t);
  let resolve, signal;
  const instance = client(t, wire, { getIdentity: value => { signal = value; return new Promise(done => { resolve = done; }); } });
  instance.connect(); await until(() => resolve);
  await instance.disconnect();
  assert.equal(signal.aborted, true);
  resolve({ accessToken: 'late-secret', subject: 'owner', expiresAt: Date.now() + 60000 });
  await tick(40);
  assert.equal(wire.sessions.length, 0);
  assert.equal(instance.state, 'stopped');
});

test('broker denial stops reconnect and diagnostics contain no credentials or error bodies', async t => {
  const wire = await broker(t);
  const errors = [];
  const instance = client(t, wire, { onError: code => errors.push(code) });
  instance.connect(); await until(() => wire.sessions[0]?.socket.readyState === 1 && instance.state === 'live');
  wire.sessions[0].socket.send('ERROR\nmessage:private-secret-token\n\nprivate-error-body\0');
  await until(() => instance.state === 'failed');
  assert.deepEqual(errors, ['BROKER_REJECTED']);
  await tick(50);
  assert.equal(wire.sessions.length, 1);
});

test('credential failures exhaust a bounded retry budget without opening sockets', async t => {
  const wire = await broker(t);
  let calls = 0;
  const errors = [];
  const instance = client(t, wire, { maxAttempts: 2,
    getIdentity: async () => { calls++; throw new Error('secret-credential-detail'); }, onError: code => errors.push(code) });
  instance.connect(); await until(() => instance.state === 'failed');
  assert.equal(calls, 2);
  assert.equal(wire.sessions.length, 0);
  assert.equal(errors.at(-1), 'RETRIES_EXHAUSTED');
  assert.equal(JSON.stringify(errors).includes('secret'), false);
});

test('malformed events trigger reconciliation reconnect instead of invoking listeners', async t => {
  const wire = await broker(t);
  const errors = [];
  const instance = client(t, wire, { onError: code => errors.push(code) });
  instance.subscribe(destination, () => assert.fail('Malformed event delivered'));
  instance.connect(); await until(() => wire.sessions[0]?.subscriptions.size === 1 && instance.state === 'live');
  for (const body of ['{', JSON.stringify({ eventType: '\u20ac'.repeat(30000), occurredAt: new Date().toISOString(), data: {} })]) {
    const count = wire.sessions.length;
    wire.send(body);
    await until(() => wire.sessions.length === count + 1 && wire.sessions.at(-1).subscriptions.size === 1 && instance.state === 'live');
  }
  assert.ok(errors.includes('INVALID_EVENT'));
});

test('events during an in-flight snapshot trigger another serialized canonical read', async t => {
  const wire = await broker(t);
  let resume, canonical = 1, visible, calls = 0, concurrent = 0, maximum = 0;
  const instance = client(t, wire, { resync: async () => {
    maximum = Math.max(maximum, ++concurrent); calls++;
    const value = canonical;
    if (calls === 1) await new Promise(resolve => { resume = resolve; });
    visible = value; concurrent--;
  } });
  instance.subscribe(destination, () => {});
  instance.connect(); await until(() => resume && wire.sessions[0]?.subscriptions.size === 1);
  canonical = 8; wire.send(); await tick(20); resume();
  await until(() => visible === 8);
  assert.equal(maximum, 1);
});

test('bounds destinations and forbids remote plaintext or URL credentials', () => {
  const options = { url: 'wss://notifications.example/ws/websocket', getIdentity: async () => ({ accessToken: 'x', subject: 'x', expiresAt: Date.now() + 60000 }), resync: async () => {} };
  for (const url of ['ws://remote.example/ws', 'wss://user:secret@example/ws', 'wss://example/ws?token=secret']) {
    assert.throws(() => new NotificationClient({ ...options, url, allowInsecureLocalhost: true }));
  }
  const instance = new NotificationClient({ ...options, maxSubscriptions: 1 });
  assert.throws(() => instance.subscribe('/topic/orders/*', () => {}));
  instance.subscribe(destination, () => {});
  assert.throws(() => instance.subscribe(other, () => {}));
});


test('stopping aborts canonical recovery and suppresses late hint delivery', async t => {
  const wire = await broker(t);
  let resume, signal, delivered = 0;
  const instance = client(t, wire, { resync: (_, context) => { signal = context.signal; return new Promise(resolve => { resume = resolve; }); } });
  instance.subscribe(destination, () => delivered++);
  instance.connect(); await until(() => resume && wire.sessions[0]?.subscriptions.size === 1);
  wire.send(); await tick(10);
  await instance.disconnect();
  assert.equal(signal.aborted, true);
  resume(); await tick(30);
  assert.equal(delivered, 0);
  assert.equal(instance.state, 'stopped');
});

test('buffer overflow reconnects and fetches current state instead of silently dropping recovery', async t => {
  const wire = await broker(t);
  let calls = 0;
  const errors = [];
  const instance = client(t, wire, { maxBufferedMessages: 1, onError: code => errors.push(code),
    resync: async (_, { signal }) => {
      if (++calls === 1) await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }));
    } });
  instance.subscribe(destination, () => {}); instance.subscribe(other, () => {});
  instance.connect(); await until(() => calls === 1 && wire.sessions[0]?.subscriptions.size === 2);
  wire.send(envelope, destination); wire.send(envelope, other);
  await until(() => calls >= 2 && instance.state === 'live');
  assert.ok(errors.includes('BUFFER_FULL'));
});

test('credential header injection is rejected before transport activation', async t => {
  const wire = await broker(t);
  const errors = [];
  const instance = client(t, wire, { maxAttempts: 1, onError: code => errors.push(code),
    getIdentity: async () => ({ accessToken: 'token\nlogin:injected', subject: 'owner', expiresAt: Date.now() + 60000 }) });
  instance.connect(); await until(() => instance.state === 'failed');
  assert.equal(wire.sessions.length, 0);
  assert.deepEqual(errors, ['CREDENTIALS', 'RETRIES_EXHAUSTED']);
});

test('stalled state reads time out within the bounded connection budget', async t => {
  const wire = await broker(t);
  const errors = [];
  const instance = client(t, wire, { maxAttempts: 2, timeoutMs: 50,
    resync: async () => new Promise(() => {}), onError: code => errors.push(code) });
  instance.connect(); await until(() => instance.state === 'failed');
  assert.ok(errors.includes('RECOVERY'));
  assert.equal(errors.at(-1), 'RETRIES_EXHAUSTED');
});

test('timer overflow cannot turn configured backoff into a tight reconnect loop', () => {
  assert.throws(() => new NotificationClient({ url: 'wss://notifications.example/ws/websocket',
    getIdentity: async () => ({ accessToken: 'x', subject: 'x', expiresAt: Date.now() + 60000 }), resync: async () => {},
    timeoutMs: 2147483648 }));
});


test('proactively renews the connection before token expiry with a fresh identity', async t => {
  const wire = await broker(t);
  let credentials = 0;
  const instance = client(t, wire, { getIdentity: async () => ({ accessToken: `renewed-${++credentials}`,
    subject: 'owner', expiresAt: Date.now() + (credentials === 1 ? 160 : 60000) }) });
  instance.subscribe(destination, () => {});
  instance.connect();
  await until(() => credentials >= 2 && wire.sessions[1]?.subscriptions.size === 1 && instance.state === 'live');
  assert.equal(wire.frames.filter(frame => frame.command === 'CONNECT')[1].headers.Authorization, 'Bearer renewed-2');
});

test('expired credentials never open a transport', async t => {
  const wire = await broker(t);
  const instance = client(t, wire, { maxAttempts: 1,
    getIdentity: async () => ({ accessToken: 'expired', subject: 'owner', expiresAt: Date.now() - 1 }) });
  instance.connect(); await until(() => instance.state === 'failed');
  assert.equal(wire.sessions.length, 0);
});
