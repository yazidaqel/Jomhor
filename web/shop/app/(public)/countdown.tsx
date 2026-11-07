import type { Dict } from '@/lib/i18n'
import type { countdown } from '@/lib/time'

const pad = (n: number) => String(n).padStart(2, '0')

/** Days · hours · minutes tiles (DESIGN.md countdown tile), server-rendered from `countdown()`; nothing once started. */
export function CountdownTiles({ left, copy }: { left: ReturnType<typeof countdown>; copy: Pick<Dict['event'], 'countdown' | 'days' | 'hours' | 'minutes'> }) {
  if (!left) return null
  return (
    <div role="group" aria-label={copy.countdown} className="flex flex-col gap-2">
      <p aria-hidden="true" className="text-label uppercase text-text-muted">
        {copy.countdown}
      </p>
      <div className="flex gap-2">
        {(
          [
            [left.days, copy.days],
            [left.hours, copy.hours],
            [left.minutes, copy.minutes],
          ] as const
        ).map(([n, unit]) => (
          <p
            key={unit}
            className="flex min-w-[76px] flex-col items-center gap-1 rounded-chip border border-border bg-ground px-3 pt-3 pb-2.5"
          >
            <span className="font-display text-[36px] font-extrabold leading-none">{pad(n)}</span>
            <span className="text-caption uppercase tracking-[1px] text-text-muted">{unit}</span>
          </p>
        ))}
      </div>
    </div>
  )
}
