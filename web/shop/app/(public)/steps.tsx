import type { Dict } from '@/lib/i18n'
import { CheckIcon } from '../icons'

/**
 * Where the fan is in the purchase: Zone → Billets → Paiement → Confirmation. `current` is 1-based; a value past the
 * last step marks every step done. Pure markup, shared by W3 (server) and W4's live status (client).
 */
export function Steps({ current, copy }: { current: number; copy: Dict['steps'] }) {
  const names = [copy.zone, copy.tickets, copy.payment, copy.done]
  return (
    <ol aria-label={copy.label} className="flex items-center gap-2">
      {names.map((name, i) => {
        const n = i + 1
        const done = n < current
        const on = n === current
        return (
          <li key={name} data-step={done ? 'done' : on ? 'current' : 'todo'} className="flex min-w-0 flex-1 items-center gap-2 last:flex-none">
            <span
              className={`flex size-8 shrink-0 items-center justify-center rounded-pill font-display text-base font-extrabold transition-colors duration-200 ${
                done ? 'bg-accent text-on-accent' : on ? 'border-2 border-accent text-accent' : 'border border-border text-text-muted'
              }`}
            >
              {done ? <CheckIcon className="size-4" /> : n}
            </span>
            <span className={`truncate text-sm max-sm:sr-only ${on ? 'font-semibold text-text' : done ? 'text-text-secondary' : 'text-text-muted'}`}>
              {name}
              {(on || done) && <span className="sr-only"> {on ? copy.current : copy.complete}</span>}
            </span>
            {n < names.length && <span aria-hidden="true" className={`h-0.5 min-w-4 flex-1 rounded-pill ${done ? 'bg-accent' : 'bg-border'}`} />}
          </li>
        )
      })}
    </ol>
  )
}
