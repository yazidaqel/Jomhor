// The hot row (plan lot-5 Q151, §5.5): BUYERS concurrent buyers on the seeded hot-row event (no room, one category),
// each placing 1-place orders and paying them, so every order takes the same price_categories row while holds stay
// under the SALE_BUSY cap. orders_placed's rate is the figure (orders/s).
//   k6 run hot-row.js                          100 buyers, 60 s
//   k6 run --vus 5 --duration 30s hot-row.js   the smoke
import exec from 'k6/execution';
import { fan, order, payCard, seed, thresholds } from './lib.js';

export const options = {
  thresholds: { ...thresholds, orders_placed: ['count>0'], orders_paid: ['count>0'] },
  scenarios: {
    hot: { executor: 'constant-vus', vus: Number(__ENV.BUYERS || 100), duration: `${Number(__ENV.DURATION_S || 60)}s` },
  },
};

export default function () {
  const f = fan(exec.vu.idInTest - 1);
  const o = order(f, seed.hot_event_id, seed.hot_price, 1);
  if (o) payCard(f, o, true);
}
