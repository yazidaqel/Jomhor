// Shared by onsale.js and hot-row.js (plan lot-5 §5 BE-44, Q158): the seed, round-robin over the instances, the fan
// calls of docs/04-api.md, the simulated gateway's signed callback, and the gates every run shares.
//   BASE_URLS  comma-separated API bases with /v1 (default: the smoke's api-1 and api-2 on the host)
//   SEED       the LoadSeeder file (default: backend/build/loadtest/seed.json)
//   PAYMENTS_FAKE_SECRET  the instances' simulated-gateway secret (default: the smoke compose's)
// Every order answered 201 is logged as "ORDER <id>" (console.log: run k6 with --console-output=<file> to keep them
// off the terminal); smoke.sh checks that each one exists (no order lost).
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const BASES = (__ENV.BASE_URLS || 'http://localhost:8081/v1,http://localhost:8082/v1').split(',');
const SEED = __ENV.SEED || '../../build/loadtest/seed.json';
const FAKE_SECRET = __ENV.PAYMENTS_FAKE_SECRET || 'smoke-only-payments-fake-secret-0001';

// One copy of the tokens for every VU; the ids are small.
export const tokens = new SharedArray('tokens', () => JSON.parse(open(SEED)).tokens);
export const seed = new SharedArray('seed', () => {
  const s = JSON.parse(open(SEED));
  delete s.tokens;
  return [s];
})[0];

// 429 (busy, rate limits) is an answer the flow expects; an order's 409 too (per request, below), and the order check
// then accepts only SOLD_OUT and PER_ACCOUNT_LIMIT. Anything else counts in http_req_failed.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));
const orderAnswers = http.expectedStatuses({ min: 200, max: 399 }, 409, 429);

const http5xx = new Counter('http_5xx');
export const ordersPlaced = new Counter('orders_placed');
export const ordersPaid = new Counter('orders_paid');

// Gating (§5.5): no 5xx at all, every check holds. Status 0 (no answer: a killed instance) is neither a 5xx nor a
// failed check, so the kill run can pass; it still counts in http_req_failed.
export const thresholds = {
  http_5xx: ['count==0'],
  checks: ['rate==1'],
  http_req_failed: ['rate<0.01'],
};

// A check that a request with no answer (status 0) doesn't fail.
export function expect(res, name, pass) {
  return check(res, { [name]: (r) => r.status === 0 || pass(r) });
}

// Round-robin per VU. An instance that gives no answer (killed) is skipped by this VU for 10 s, so the load moves to
// the others; the failed request is not retried (k6 counts it).
let next = __VU;
const deadUntil = {};

function base() {
  for (let i = 0; i < BASES.length; i++) {
    const b = BASES[next++ % BASES.length];
    if (!(deadUntil[b] > Date.now())) return b;
  }
  return BASES[next++ % BASES.length];
}

export function call(method, path, body, params) {
  const b = base();
  const res = http.request(method, b + path, body, params);
  if (res.status === 0) deadUntil[b] = Date.now() + 10000;
  http5xx.add(res.status >= 500 ? 1 : 0, { instance: b });
  return res;
}

// Fan i: the seed's i-th token; 50 fans per client IP (198.18.0.0/15, the benchmarking range) and one device each.
export function fan(i) {
  const n = Math.floor(i / 50);
  return {
    i,
    token: tokens[i % tokens.length],
    ip: `198.18.${(n >> 8) & 255}.${n & 255}`,
    device: `load-device-${String(i).padStart(8, '0')}`,
  };
}

function headers(f, extra) {
  return Object.assign(
    { 'Content-Type': 'application/json', Authorization: `Bearer ${f.token}`, 'X-Forwarded-For': f.ip },
    extra,
  );
}

// A field of a JSON answer, or undefined (error page, empty body).
export function json(res, path) {
  try {
    return res.json(path);
  } catch (e) {
    return undefined;
  }
}

export function join(f, eventId) {
  const res = call('POST', '/fan/queue', JSON.stringify({ event_id: eventId, device_id: f.device }), {
    headers: headers(f),
    tags: { name: 'join' },
  });
  expect(res, 'join 200/201', (r) => r.status === 200 || r.status === 201);
  return res;
}

export function poll(queueToken) {
  const res = call('GET', '/fan/queue', null, { headers: { 'X-Queue-Token': queueToken }, tags: { name: 'poll' } });
  expect(res, 'poll 200/429', (r) => r.status === 200 || r.status === 429);
  return res;
}

// POST /fan/orders. Accepted: 201, 429 SALE_BUSY, 409 SOLD_OUT or PER_ACCOUNT_LIMIT; anything else (ADMISSION_REQUIRED
// included) fails the check. Returns the order {id, total_mad} on 201, else null.
export function order(f, eventId, priceId, qty, admission) {
  const holders = Array.from({ length: qty }, () => ({ name: 'Supporter Charge' }));
  const body = { event_id: eventId, channel: 'APP', items: [{ price_category_id: priceId, qty, holders }] };
  const res = call('POST', '/fan/orders', JSON.stringify(body), {
    headers: headers(f, admission ? { 'X-Admission-Token': admission } : {}),
    tags: { name: 'order' },
    responseCallback: orderAnswers,
  });
  expect(res, 'order 201, 429, or 409 SOLD_OUT/PER_ACCOUNT_LIMIT', (r) => r.status === 201 || r.status === 429
    || (r.status === 409 && ['SOLD_OUT', 'PER_ACCOUNT_LIMIT'].includes(json(r, 'code'))));
  if (res.status !== 201) return null;
  ordersPlaced.add(1);
  const id = json(res, 'id');
  console.log(`ORDER ${id}`);
  return { id, total: json(res, 'total_mad') };
}

// /pay CARD, the hosted page's redirect (sets redirected_at), then the gateway's signed callback (303).
export function payCard(f, o, approved) {
  const pay = call('POST', `/fan/orders/${o.id}/pay`, JSON.stringify({ method: 'CARD' }), {
    headers: headers(f),
    tags: { name: 'pay' },
  });
  expect(pay, 'pay 200', (r) => r.status === 200);
  const paymentId = json(pay, 'payment_id');
  if (!paymentId) return;
  call('GET', `/payments/card/${paymentId}/redirect`, null, { redirects: 0, tags: { name: 'redirect' } });
  const outcome = approved ? 'APPROVED' : 'DECLINED';
  const ref = `FAKE-K6-${f.i}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
  const fields = { payment_id: paymentId, outcome, amount_mad: String(o.total), provider_ref: ref };
  fields.signature = crypto.hmac('sha256', FAKE_SECRET, [paymentId, outcome, fields.amount_mad, ref].join('|'), 'hex');
  const cb = call('POST', '/payments/fake/callback', fields, { redirects: 0, tags: { name: 'callback' } });
  if (expect(cb, 'callback 303', (r) => r.status === 303) && cb.status === 303 && approved) ordersPaid.add(1);
}
