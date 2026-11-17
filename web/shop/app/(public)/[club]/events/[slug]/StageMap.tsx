'use client'

import type { Zone } from '@/lib/events'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { firstOpen, mapView } from '@/lib/venue-map'

// The zone's colour by its position among the event's zones (zoneA/B/C, as the grid bars). Literal class names for Tailwind.
const FILLS = ['border-zone-a bg-zone-a/16', 'border-zone-b bg-zone-b/16', 'border-zone-c bg-zone-c/16']

/**
 * W2 venue map (DESIGN §Venue map adjustments): the venue's template above the grid, or nothing when the event has no
 * usable map. `dir="ltr"` on the canvas so `/ar` never mirrors it (North on top, West on the left); line text takes its
 * own direction. A block tap = `onPick(zone index)`; `disabled` (sale closed or gated by a room) disables every block.
 */
export function StageMap({
  layout,
  zones,
  selected,
  disabled,
  onPick,
  copy,
}: {
  layout: string | null
  zones: Zone[]
  /** Index of the zone holding the selected price, or null. */
  selected: number | null
  disabled: boolean
  onPick: (zone: number) => void
  copy: Dict['event']
}) {
  const view = mapView(layout, zones)
  if (!view) return null
  const { center } = view
  return (
    <div role="group" aria-label={copy.map} dir="ltr" data-map className="relative aspect-[4/3] max-[368px]:-mx-6">
      <div aria-hidden="true" style={center.box} className="absolute flex items-center justify-center overflow-hidden rounded-chip bg-surface2">
        {center.kind === 'FIELD' && (
          <>
            <span className="absolute inset-y-0 start-1/2 w-px bg-border" />
            <span className="absolute top-1/2 start-1/2 aspect-square h-2/5 -translate-x-1/2 -translate-y-1/2 rounded-pill border border-border" />
          </>
        )}
        <span className="relative text-label uppercase text-text-muted">{center.kind === 'FIELD' ? copy.mapField : copy.mapStage}</span>
      </div>
      {view.slots.map(({ id, box, zone: i }) => {
        const z = i === null ? undefined : zones[i]
        if (!z || i === null) return <div key={id} aria-hidden="true" data-slot={id} style={box} className="absolute rounded-[10px] border border-border bg-surface" />
        const open = firstOpen(z.prices)
        const members = z.prices.some((p) => p.audience === 'MEMBERS')
        const on = i === selected
        const from = open && fmt(copy.mapFrom, { price: formatDh(open.price_mad) })
        const label = (open ? fmt(copy.mapBlock, { zone: z.name, price: formatDh(open.price_mad) }) : fmt(copy.mapSoldOut, { zone: z.name })) + (members ? copy.mapMembers : '')
        return (
          <button
            key={id}
            type="button"
            data-slot={id}
            aria-label={label}
            aria-pressed={on}
            disabled={disabled || !open}
            onClick={() => onPick(i)}
            style={box}
            // Lines that don't fit wrap into a second column, which the overflow hides: they drop from the bottom.
            className={`absolute flex flex-col flex-wrap items-center justify-center overflow-hidden rounded-[10px] px-1.5 text-center transition duration-200 disabled:cursor-not-allowed ${
              on
                ? 'z-10 border-2 border-accent bg-accent text-on-accent motion-safe:scale-[1.03]'
                : open
                  ? `border-[1.5px] text-text hover:enabled:brightness-125 ${FILLS[i % FILLS.length]}`
                  : 'border-[1.5px] border-zone-disabled bg-zone-disabled/30 text-text-muted'
            } ${disabled ? 'opacity-55' : ''}`}
          >
            <span dir="auto" className="w-full truncate text-body-small font-semibold">
              {z.name}
            </span>
            <span dir="auto" className={`w-full truncate text-caption ${on ? '' : 'text-text-muted'}`}>
              {from ?? copy.soldOut}
            </span>
            {members && (
              <span dir="auto" className={`w-full truncate text-caption font-semibold ${on ? '' : 'text-accent'}`}>
                {copy.members}
              </span>
            )}
          </button>
        )
      })}
    </div>
  )
}
