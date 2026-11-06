// Backend `/fan/events/{id}` bodies for tests. The SECRET_* and stray fields must never reach a mapped event, HTML or
// client props.

export const ORG = '0190f3a2-0000-7000-8000-000000000001'
export const NOW = Date.parse('2026-02-01T12:00:00Z')
export const SECRETS = ['SECRET', 'event_key', 'k_e', 'k_t', '"key"', 'quota', 'sold"', 'internal_note']

export function rawEvent(over: Record<string, unknown> = {}) {
  return {
    id: 12,
    organizer_id: ORG,
    event_type: 'FOOTBALL_MATCH',
    title: 'ATLAS FC vs Rapid Oued',
    starts_at: '2026-02-07T19:00:00Z',
    doors_open_at: '2026-02-07T17:30:00Z',
    timezone: 'Africa/Casablanca',
    venue: { name: "Stade de l'Atlas", city: 'Rabat' },
    metadata: { competition: 'Championnat', matchday: 11, internal_note: 'SECRET-META' },
    sale_starts_at: '2026-01-20T10:00:00Z',
    sales_close_at: '2026-02-07T18:00:00Z',
    on_sale: true,
    sold_out: false,
    from_price_mad: 5000,
    per_account_limit: 4,
    zones: [
      {
        id: 3,
        code: 'NORD',
        name: 'Tribune Nord',
        seated: false,
        gate_codes: ['P10', 'P4'],
        prices: [
          { price_category_id: 'pc-tribune', name: 'Tribune', price_mad: 12050, remaining: 30, quota: 100, sold: 70 },
          { price_category_id: 'pc-virage', name: 'Virage', price_mad: 5000, remaining: 2300 },
        ],
        key: 'SECRET-ZONE',
      },
      {
        id: 4,
        code: 'SUD',
        name: 'Tribune Sud',
        seated: true,
        gate_codes: ['P2'],
        prices: [{ price_category_id: 'pc-sud', name: 'Plein', price_mad: 8000, remaining: 0 }],
      },
    ],
    // Fields the contract doesn't have: a backend bug must not leak them.
    key: 'SECRET-KEY',
    k_e: 'SECRET-KE',
    event_key: 'SECRET-EK',
    event_key_enc: 'SECRET-ENC',
    k_t: 'SECRET-KT',
    quota: 1000,
    sold: 999,
    ...over,
  }
}

/** A list row (`GET /fan/events`): the detail without zones. */
export function rawListRow(over: Record<string, unknown> = {}) {
  const row: Record<string, unknown> = rawEvent(over)
  delete row.zones
  return row
}

export const json = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
