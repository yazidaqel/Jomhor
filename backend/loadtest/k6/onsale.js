// The on-sale load (plan lot-5 §5.5): fans join the pre-queue, poll the waiting room at poll_after_s, buy at their
// turn (30 % abandon the hold, 5 % of cards refused), and cold visitors read the event and ask for an OTP.
//
// One scenario at a time, at any size (the smoke, docs/TESTING.md §Load):
//   k6 run --vus 5 --duration 30s -e DURATION_S=30 -e SCENARIO=prequeue|poll|buy|cold-visitor onsale.js
// DURATION_S is the scenario's length: no iteration waits past it (a buyer still waiting for a turn gives up).
// Without SCENARIO, the §5.5 run: all four together, sized by env (FANS, BUYERS, POLL_VUS, POLL_RPS, COLD_RPS,
// DURATION_S). Start it 5 min before the seeded sale_starts_at (LOADTEST_SALE_IN): the pre-queue joins take 4 min,
// then half of them in the last minute, then FANS / 7 more over 2 min after T0. Pollers and buyers touch a fan only
// once its pre-queue arrival has passed, so the late fans join after T0 and rank by arrival.
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { call, expect, fan, join, json, order, payCard, poll as pollQueue, seed, thresholds, tokens } from './lib.js';

const FANS = Number(__ENV.FANS || tokens.length);
const BUYERS = Number(__ENV.BUYERS || 1000); // fans [0, BUYERS) run whole journeys; the pollers have the rest
const POLL_VUS = Number(__ENV.POLL_VUS || 300); // full run: one poller per slice of the non-buyers
const POLL_RPS = Number(__ENV.POLL_RPS || 500); // full run: the pollers' request cap, all together
const COLD_RPS = Number(__ENV.COLD_RPS || 20);
const DURATION_S = Number(__ENV.DURATION_S || 1500);
const MAX_WAIT_S = Number(__ENV.MAX_WAIT_S || 1200); // a buyer gives up after this

/** When the running scenario ends: its start + DURATION_S. */
const scenarioEnd = () => exec.scenario.startTime + DURATION_S * 1000;
/** Sleeps {@code s} seconds, never past {@code end}; false once {@code end} has passed. */
function nap(s, end) {
  const left = (end - Date.now()) / 1000;
  if (left <= 0) return false;
  sleep(Math.min(s, left));
  return true;
}

const admissions = new Counter('admissions');
const ordersAbandoned = new Counter('orders_abandoned');
const cardsRefused = new Counter('cards_refused');
const turnWait = new Trend('turn_wait_s');

// The pre-queue schedule: half of the early fans over 4 min, the other half in the last minute, the late ones (FANS /
// 7) over 2 min after T0.
const LATE = Math.round(FANS / 7);
const HALF = (FANS - LATE) / 2;

/** Fans whose pre-queue join is due by {@code t} s into the run (all of them outside the full run). */
function arrived(t) {
  if (__ENV.SCENARIO) return FANS;
  if (t <= 0) return 0;
  if (t < 240) return (HALF * t) / 240;
  if (t < 300) return HALF + (HALF * (t - 240)) / 60;
  return Math.min(FANS, 2 * HALF + (LATE * (t - 300)) / 120);
}

/** Fans already joined by the pre-queue scenario, with 5 s for it to keep up. */
const joined = () => arrived(exec.instance.currentTestRunDuration / 1000 - 5);

const gates = { orders_placed: ['count>0'], orders_paid: ['count>0'], admissions: ['count>0'] };

function fullRun() {
  const hold = (rate, s) => [{ target: rate, duration: '1s' }, { target: rate, duration: `${s - 1}s` }];
  return {
    prequeue: {
      executor: 'ramping-arrival-rate', exec: 'prequeue', timeUnit: '1s', startRate: 0,
      preAllocatedVUs: 50, maxVUs: 300,
      stages: [...hold(HALF / 240, 240), ...hold(HALF / 60, 60), ...hold(LATE / 120, 120)],
    },
    poll: {
      executor: 'per-vu-iterations', exec: 'poller', vus: POLL_VUS, iterations: 1,
      maxDuration: `${DURATION_S + 60}s`,
    },
    buy: {
      executor: 'per-vu-iterations', exec: 'buy', vus: BUYERS, iterations: 1, maxDuration: `${DURATION_S + 60}s`,
    },
    'cold-visitor': {
      executor: 'constant-arrival-rate', exec: 'coldVisitor', rate: COLD_RPS, timeUnit: '1s', duration: '7m',
      preAllocatedVUs: 10, maxVUs: 100,
    },
  };
}

export const options = !__ENV.SCENARIO
  ? { thresholds: { ...thresholds, ...gates }, setupTimeout: '4m', scenarios: fullRun() }
  : { thresholds: __ENV.SCENARIO === 'buy' ? { ...thresholds, ...gates } : thresholds, setupTimeout: '4m' };

// The smoke's buy runs alone: wait for the draw (at most 3 min), so its fans are ranked when they start.
export function setup() {
  if (__ENV.SCENARIO !== 'buy') return;
  for (let i = 0; i < 180; i++) {
    const room = json(call('GET', `/fan/events/${seed.event_id}`, null, { tags: { name: 'event' } }), 'room');
    if (!room || room.status === 'OPEN') return;
    sleep(1);
  }
}

export default function () {
  ({ prequeue, poll, buy, 'cold-visitor': coldVisitor })[__ENV.SCENARIO]();
}

// ---------- prequeue: one join per iteration, fans in order (so the buyers are in the draw) ----------

export function prequeue() {
  join(fan(exec.scenario.iterationInTest % FANS), seed.event_id);
}

// ---------- poll: the non-buyers in slices, each fan at its poll_after_s; a fan whose turn comes buys ----------

const fans = {}; // this VU's fans: index -> {token, due, done}
let cursor = 0;

/** One request for the next due fan of the slice; false if none is due. */
function pollStep(slice, slices) {
  const size = Math.ceil((FANS - BUYERS - slice) / slices);
  const limit = joined();
  for (let k = 0; k < size; k++) {
    const i = BUYERS + slice + slices * (cursor++ % size);
    const s = fans[i] || (fans[i] = { due: 0 });
    if (i >= limit || s.done || s.due > Date.now()) continue;
    const f = fan(i);
    if (!s.token) { // the app reopened: an idempotent join hands back the entry's token
      s.token = json(join(f, seed.event_id), 'queue_token');
      return true;
    }
    const r = pollQueue(s.token);
    if (r.status !== 200) return true;
    const q = r.json();
    s.token = q.queue_token;
    s.due = Date.now() + q.poll_after_s * 1000;
    s.done = q.poll_after_s === 0; // ADMITTED, EXPIRED, ENDED
    if (q.status === 'ADMITTED') turn(f, q);
    return true;
  }
  return false;
}

/** A smoke step: the --vus VUs are the slices (the only scenario, so VU ids are 1..vus). */
export function poll() {
  const slices = exec.instance.vusInitialized;
  if (!pollStep((exec.vu.idInTest - 1) % slices, slices)) sleep(1);
}

/** The full run: one iteration per poller VU (so iterationInTest is its slice, 0..POLL_VUS-1), for the run. */
export function poller() {
  const slice = exec.scenario.iterationInTest;
  const end = scenarioEnd();
  let polling = true;
  while (polling) polling = nap(pollStep(slice, POLL_VUS) ? POLL_VUS / POLL_RPS : 1, end);
}

// ---------- buy: one fan's whole journey: join, poll at poll_after_s until the turn, buy ----------

export function buy() {
  const end = Math.min(scenarioEnd(), Date.now() + MAX_WAIT_S * 1000);
  const i = exec.scenario.iterationInTest % BUYERS;
  while (joined() <= i) { // full run: not before the fan's pre-queue arrival
    if (!nap(1, end)) return;
  }
  const f = fan(i);
  let token = json(join(f, seed.event_id), 'queue_token');
  const start = Date.now();
  while (token && Date.now() < end) {
    const p = pollQueue(token);
    if (p.status !== 200) {
      nap(1, end);
      continue;
    }
    const q = p.json();
    token = q.queue_token;
    if (q.status === 'ADMITTED') {
      turnWait.add((Date.now() - start) / 1000);
      turn(f, q);
      return;
    }
    if (q.poll_after_s === 0) return; // EXPIRED, ENDED
    nap(q.poll_after_s, end);
  }
}

// The turn: 1–4 places (per_account_limit 4), the hot zone half the time. By fan index, so a small run is
// predictable: i % 20 = 13..18 abandon the hold (30 %), 19 has the card refused (5 %), the rest pay.
function turn(f, q) {
  admissions.add(1);
  const price = Math.random() < 0.5 ? seed.prices[0] : seed.prices[1 + Math.floor(Math.random() * 2)];
  const o = order(f, seed.event_id, price, 1 + Math.floor(Math.random() * 4), q.admission.token);
  if (!o) return;
  const r = f.i % 20;
  if (r >= 13 && r <= 18) {
    ordersAbandoned.add(1);
    return;
  }
  if (r === 19) cardsRefused.add(1);
  payCard(f, o, r !== 19);
}

// ---------- cold-visitor: a shop visitor without a session: the list, the event, an OTP request ----------

export function coldVisitor() {
  expect(call('GET', '/fan/events', null, { tags: { name: 'events' } }), 'events 200', (r) => r.status === 200);
  expect(call('GET', `/fan/events/${seed.event_id}`, null, { tags: { name: 'event' } }), 'event 200',
    (r) => r.status === 200);
  // +9995 + 8 digits: 13 characters, never a seeded fan (12); a fresh benchmarking-range IP per visitor.
  const phone = `+9995${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`;
  const ip = `198.19.${Math.floor(Math.random() * 256)}.${Math.floor(Math.random() * 256)}`;
  const otp = call('POST', '/fan/auth/otp', JSON.stringify({ phone }), {
    headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': ip },
    tags: { name: 'otp' },
  });
  expect(otp, 'otp 202/429', (r) => r.status === 202 || r.status === 429);
}
