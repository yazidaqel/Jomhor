'use client'

import { fmt } from '@/lib/i18n'
import { mmss } from '@/lib/queue'
import { useAdmission, useServerClock } from '@/lib/queue-hooks'
import { ClockIcon } from '../../../icons'

/**
 * W3 while admitted (DESIGN Lot 5): the slim bar "Ton tour · {mm:ss}" counting to this tab's W8 turn
 * `expires_at` on server time (`now` at SSR plus the page clock). Nothing without a running turn of the current sale
 * `phase` (a presale turn is none in the general sale), nor on the server render.
 */
export function TurnBar({ eventId, phase, now, text }: { eventId: number; phase: number; now: number; text: string }) {
  const [serverNow] = useServerClock(now)
  const turn = useAdmission(eventId, serverNow, phase)
  if (!turn) return null
  return (
    <p
      data-turn
      className="flex w-full items-center justify-center gap-2 rounded-chip bg-accent/10 px-3 py-2 text-sm font-semibold text-accent"
    >
      <ClockIcon className="size-4" />
      {fmt(text, { time: mmss(Math.ceil((Date.parse(turn.expires_at) - serverNow) / 1000)) })}
    </p>
  )
}
