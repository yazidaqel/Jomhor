// Venue map ("the stage", 04 §Venue map): the geometry is built in from docs/venue-layouts.json, never fetched.
// Client-safe (no server-only import): the W2 zone picker draws it.
import layouts from '../../../docs/venue-layouts.json'

type Rect = readonly number[] // [x, y, w, h] on the 400×300 canvas
type Layout = { center: { kind: string; rect: Rect }; slots: { id: string; rect: Rect }[] }
/** The fields the map reads (lib/events.ts `Zone` is server-only to import, so the shape is restated here). */
type MapZone = { map_slot: string | null; prices: { price_category_id: string; price_mad: number; remaining: number; audience: 'ALL' | 'MEMBERS' }[] }

/** Position in % of the canvas, x from the left in every locale (never mirrored). */
export type Box = { left: string; top: string; width: string; height: string }
export type MapView = {
  center: { kind: 'FIELD' | 'STAGE'; box: Box }
  /** Every slot in JSON order (= focus order); `zone` = index into the event's zones, null = inert. */
  slots: { id: string; box: Box; zone: number | null }[]
}

const { w: W, h: H } = layouts.canvas
const LAYOUTS = layouts.layouts as Record<string, Layout>
const pct = (v: number, of: number) => `${(v / of) * 100}%`
const box = ([x = 0, y = 0, w = 0, h = 0]: Rect): Box => ({ left: pct(x, W), top: pct(y, H), width: pct(w, W), height: pct(h, H) })

/** Every layout id the JSON knows. */
export const LAYOUT_IDS = Object.keys(LAYOUTS)

/**
 * The map to draw, or null (the screen is the grid only): an unknown layout, or a `map_slot` that isn't in that layout,
 * counts as null; a map needs at least one listed zone on a known slot (04 §Venue map, Unknown values).
 */
export function mapView(layout: string | null, zones: readonly MapZone[]): MapView | null {
  const l = layout !== null && Object.hasOwn(LAYOUTS, layout) ? LAYOUTS[layout]! : null
  if (!l) return null
  const slots = l.slots.map((s) => {
    const i = zones.findIndex((z) => z.map_slot === s.id)
    return { id: s.id, box: box(s.rect), zone: i < 0 ? null : i }
  })
  if (slots.every((s) => s.zone === null)) return null
  return { center: { kind: l.center.kind === 'FIELD' ? 'FIELD' : 'STAGE', box: box(l.center.rect) }, slots }
}

/**
 * The price a zone tap selects (the app's `Zone.firstOpen`): the cheapest open `ALL` price, else the cheapest open
 * `MEMBERS` price; undefined when the zone is sold out.
 */
export function firstOpen<P extends MapZone['prices'][number]>(prices: readonly P[]): P | undefined {
  return prices
    .filter((p) => p.remaining > 0)
    .sort((a, b) => Number(a.audience === 'MEMBERS') - Number(b.audience === 'MEMBERS') || a.price_mad - b.price_mad)[0]
}
