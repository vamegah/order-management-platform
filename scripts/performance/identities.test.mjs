import assert from 'node:assert/strict';
import { test } from 'node:test';
import { logicalCaller } from './identities.mjs';

test('one fast worker distributes a complete soak below unchanged per-user quotas', () => {
  const callers = new Set(), reads = new Map(), writes = new Map();
  for (let i = 0; i < 4800; i++) {
    const caller = logicalCaller(i, 32);
    callers.add(caller);
    if (i % 2 === 1) {
      const key = `${Math.floor(i / 480)}:${caller}`;
      reads.set(key, (reads.get(key) || 0) + 1);
    }
  }
  for (let i = 0; i < 600; i++) {
    const key = `${Math.floor(i / 60)}:${logicalCaller(i, 32, 16)}`;
    writes.set(key, (writes.get(key) || 0) + 1);
  }
  assert.equal(callers.size, 32);
  assert.equal([...reads.values()].reduce((a, b) => a + b, 0), 2400);
  assert.equal([...writes.values()].reduce((a, b) => a + b, 0), 600);
  assert.ok(Math.max(...reads.values()) < 60, 'GraphQL callers must stay below the shared 60/minute quota');
  assert.ok(Math.max(...writes.values()) < 30, 'Checkout callers must stay below the 30/minute quota');
});

test('invalid iteration or caller catalogues fail before authentication', () => {
  for (const args of [[-1, 32], [0, 0], [0, 65], [NaN, 32], [Infinity, 32], [0, 32, -1]]) {
    assert.throws(() => logicalCaller(...args));
  }
});
