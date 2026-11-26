// Logical callers are independent of how k6 reuses its small worker pool.
export function logicalCaller(iteration, count, offset = 0) {
  if (!Number.isSafeInteger(iteration) || iteration < 0 || !Number.isSafeInteger(count) || count < 32 || count > 64 || !Number.isSafeInteger(offset) || offset < 0) {
    throw new Error('A bounded prepared workload caller pool is required');
  }
  return (iteration + offset) % count;
}
