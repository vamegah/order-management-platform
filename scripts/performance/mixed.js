import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';
import encoding from 'k6/encoding';
import execution from 'k6/execution';

// All secrets are file-mounted and never emitted in summaries or URLs.
const identities = JSON.parse(open(__ENV.OMP_IDENTITIES_FILE));
let token, tokenExpiresAt = 0, reportedFailures = 0;
const refundSamples = new Counter('refund_completed');
const readSamples = new Counter('read_completed');
const checkoutSamples = new Counter('checkout_completed');
const data = JSON.parse(open(__ENV.OMP_LOAD_DATA_FILE));
const unexpected = new Rate('unexpected_errors');
const duration = __ENV.OMP_SOAK === 'true' ? '600s' : '120s';
const base = __ENV.OMP_API_URL;
const params = (name) => ({ headers: { Authorization: `Bearer ${credential()}`, 'Content-Type': 'application/json', Host: (name === 'GET products' ? __ENV.OMP_PRODUCT_HOST : name === 'POST refund' ? __ENV.OMP_PAYMENT_HOST : __ENV.OMP_ORDER_HOST) || 'api.localhost' },
  tags: { name }, timeout: '5s', redirects: 0 });

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  systemTags: ['method', 'name', 'status', 'scenario', 'expected_response', 'check'],
  scenarios: {
    warmup: { executor: 'constant-vus', exec: 'browse', vus: 1, duration: '30s', gracefulStop: '5s' },
    reads: { executor: 'constant-arrival-rate', exec: 'browse', rate: 8, timeUnit: '1s', duration,
      startTime: '35s', preAllocatedVUs: 4, maxVUs: 12, gracefulStop: '10s' },
    checkout: { executor: 'constant-arrival-rate', exec: 'checkout', rate: 1, timeUnit: '1s', duration,
      startTime: '35s', preAllocatedVUs: 2, maxVUs: 4, gracefulStop: '20s' },
  },
  thresholds: {
    unexpected_errors: ['rate<=0.001'],
    'http_req_duration{scenario:reads}': ['p(95)<=1000', 'p(99)<=2500'],
    'http_req_duration{scenario:checkout}': ['p(95)<=1000', 'p(99)<=2500'],
    dropped_iterations: ['count==0'],
    checks: ['rate==1'],
    refund_completed: ['count>=10'],
    read_completed: [`count>=${__ENV.OMP_SOAK === 'true' ? 4800 : 960}`],
    checkout_completed: [`count>=${__ENV.OMP_SOAK === 'true' ? 600 : 120}`],
  },
};

function credential(index = execution.vu.idInTest - 1) {
  if (token && Date.now() < tokenExpiresAt) return token;
  const identity = identities.clients[index];
  if (!identity) fail('A distinct prepared client identity is required for every VU');
  const response = http.post(identities.tokenUrl, { grant_type: 'client_credentials' }, {
    headers: { Authorization: `Basic ${encoding.b64encode(`${identity.clientId}:${identity.clientSecret}`)}`, Host: 'keycloak.localhost' },
    tags: { name: 'POST workload token' }, timeout: '5s', redirects: 0,
  });
  if (response.status !== 200) fail('The isolated issuer rejected a workload client');
  const result = response.json();
  if (!result.access_token || Number(result.expires_in) < 45) fail('Issuer returned an invalid or insufficient token lifetime');
  token = result.access_token;
  tokenExpiresAt = Date.now() + (Number(result.expires_in) - 30) * 1000;
  return token;
}
export function setup() {
  if (!/^http:\/\/(localhost|127\.0\.0\.1|traefik)(:\d+)?$/.test(base || '') ||
      !/^omp-operations-[a-z0-9]{6,24}$/.test(__ENV.OMP_ACCEPTANCE_PROJECT || '')) {
    fail('An isolated local acceptance project and loopback/private ingress are required');
  }
  if (!data.customerId || !data.productId || !Array.isArray(data.refundPayments) || data.refundPayments.length < 10) fail('Prepared isolated checkout and captured refund fixtures are required');
  if (!/^http:\/\/(localhost|127\.0\.0\.1|keycloak)(:\d+)?\/realms\/omp\/protocol\/openid-connect\/token$/.test(identities.tokenUrl || '') || !Array.isArray(identities.clients) || identities.clients.length < 32) fail('The isolated issuer and at least 32 distinct workload clients are required');
  if (new Set(identities.clients.map(i => i.clientId)).size !== identities.clients.length) fail('Load identities must be distinct');
  // Use the same workload credential, issuer Host and ingress path as measured VUs.
  // Reject authentication/routing mistakes before a long, meaningless load run.
  const authorization = `Bearer ${credential(0)}`;
  const rest = http.get(`${base}/api/v1/products?size=1`, {
    headers: { Authorization: authorization, Host: __ENV.OMP_PRODUCT_HOST }, timeout: '5s', redirects: 0,
    tags: { name: 'GET workload preflight' },
  });
  const graphql = http.post(`${base}/graphql`, JSON.stringify({ query: '{ products(size: 1) { content { id } } }' }), {
    headers: { Authorization: authorization, Host: __ENV.OMP_GRAPHQL_HOST, 'Content-Type': 'application/json' },
    timeout: '5s', redirects: 0, tags: { name: 'POST workload preflight' },
  });
  if (rest.status !== 200 || graphql.status !== 200 || graphql.json().errors || !Array.isArray(graphql.json().data?.products?.content)) {
    fail(`Authenticated workload preflight failed (REST ${rest.status}, GraphQL ${graphql.status}); no load was started`);
  }
}
function record(response, expected, label) {
  const good = expected.includes(response.status);
  unexpected.add(!good);
  if (!good && reportedFailures++ < 3) {
    let code;
    try { code = response.json().code; } catch (_) { /* No response bodies are logged. */ }
    console.error(JSON.stringify({ stage: 'unexpected-http-status', label, status: response.status,
      code: /^[A-Z_]{1,64}$/.test(code || '') ? code : undefined,
      requestId: /^[0-9a-f-]{36}$/i.test(response.headers['X-Request-Id'] || '') ? response.headers['X-Request-Id'] : undefined,
      scenario: execution.scenario.name, iteration: execution.scenario.iterationInTest }));
  }
  check(response, { [label]: () => good });
  return good;
}
export function browse() {
  const graphql = execution.scenario.iterationInTest % 2 === 1;
  const request = params(graphql ? 'POST catalog GraphQL' : 'GET products');
  if (graphql) request.headers.Host = __ENV.OMP_GRAPHQL_HOST || 'graphql.localhost';
  const response = graphql
    ? http.post(`${base}/graphql`, JSON.stringify({ query: '{ products(size: 10) { content { id name } } }' }), request)
    : http.get(`${base}/api/v1/products?size=10`, request);
  const accepted = record(response, [200], 'catalog available');
  if (accepted && !graphql && execution.scenario.name === 'reads') readSamples.add(1);
  if (accepted && graphql) {
    const body = response.json();
    const valid = !body.errors && Array.isArray(body.data?.products?.content);
    unexpected.add(!valid);
    check(body, { 'GraphQL aggregate has no errors': () => valid });
    if (valid && execution.scenario.name === 'reads') readSamples.add(1);
  }
  if (execution.scenario.name === 'warmup') sleep(1);
}
export function checkout() {
  const response = http.post(`${base}/api/v1/orders`, JSON.stringify({ customerId: data.customerId,
    shippingAddress: { line1: 'Isolated operations load', city: 'Test', postalCode: '10001', country: 'US' },
    lines: [{ productId: data.productId, quantity: 1 }], notes: `operations-${execution.scenario.iterationInTest}` }), params('POST order'));
  if (!record(response, [201], 'checkout accepted')) return;
  let order;
  try { order = response.json(); } catch (_) { check(null, { 'valid order response': () => false }); return; }
  check(order, { 'durable reservation and payment references': (o) => !!o.id && !!o.paymentId && !!o.lines[0].reservationId });
  // Always cancel the created order so the drill does not accumulate stock reservations.
  if (record(http.post(`${base}/api/v1/orders/${order.id}/cancel`, JSON.stringify({ reason: 'isolated load cleanup' }), params('POST cancel')), [200,202], 'cleanup accepted')) checkoutSamples.add(1);
  // Every prepared captured payment is used at most once; fixture preparation owns the exact budget.
  const payment = data.refundPayments[execution.scenario.iterationInTest];
  if (payment) {
    const refund = http.post(`${base}/api/v1/payments/${payment.id}/refunds`, JSON.stringify({ amountMinor: payment.amountMinor,
      idempotencyKey: `operations-refund-${payment.id}`, reason: 'isolated load' }), { ...params('POST refund'), headers: { ...params('POST refund').headers,
      'Idempotency-Key': `operations-refund-${payment.id}` } });
    if (record(refund, [200], 'noop refund completed')) {
      const body = refund.json();
      const complete = body.status === 'COMPLETED' && body.paymentId === payment.id && body.amountMinor === payment.amountMinor && !!body.id;
      unexpected.add(!complete);
      check(body, { 'correlated refund completed': () => complete });
      if (complete) refundSamples.add(1);
    }
  }
}
export function handleSummary(value) {
  return { '/results/k6-summary.json': JSON.stringify(value, null, 2) };
}
