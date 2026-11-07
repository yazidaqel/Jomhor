import { renderToString } from 'react-dom/server'
import { expect, it } from 'vitest'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { Steps } from './steps'

const d = getDictionary(defaultLocale)
const steps = (current: number) => {
  const h = renderToString(<Steps current={current} copy={d.steps} />)
  return { h, marks: [...h.matchAll(/data-step="(\w+)"/g)].map((m) => m[1]) }
}

it('a labelled list of the four steps, in order', () => {
  const { h } = steps(2)
  expect(h).toMatch(new RegExp(`^<ol aria-label="${d.steps.label}"`))
  const names = [d.steps.zone, d.steps.tickets, d.steps.payment, d.steps.done]
  const at = names.map((n) => h.indexOf(`${n}<`))
  expect(at.every((i) => i > 0)).toBe(true)
  expect(at).toEqual([...at].sort((a, b) => a - b))
})

it('steps before `current` are done, `current` is current, the rest to do; screen readers hear which', () => {
  const { h, marks } = steps(2)
  expect(marks).toEqual(['done', 'current', 'todo', 'todo'])
  expect(h.split(d.steps.complete)).toHaveLength(2)
  expect(h.split(d.steps.current)).toHaveLength(2)
})

it('past the last step every step is done (PAID)', () => {
  expect(steps(5).marks).toEqual(['done', 'done', 'done', 'done'])
})
