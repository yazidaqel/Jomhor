import { renderToString } from 'react-dom/server'
import { beforeEach, expect, it, vi } from 'vitest'
import PublicLayout from './layout'

let locale: string | undefined
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (name === 'locale' && locale ? { name, value: locale } : undefined) }),
  headers: async () => new Headers(),
}))
// Client islands (router hooks) are covered by their own tests.
vi.mock('./AccountMenu', () => ({ AccountMenu: () => null }))
vi.mock('./LanguageSwitcher', () => ({ LanguageSwitcher: () => null }))
beforeEach(() => {
  locale = undefined
})

const render = async () => {
  const h = renderToString(await PublicLayout({ children: null }))
  return { header: h.slice(h.indexOf('<header'), h.indexOf('</header>')), footer: h.slice(h.indexOf('<footer'), h.indexOf('</footer>')) }
}
// Vite inlines the design/logo SVGs as data URLs: the mark (viewBox 120 x 120), then the wordmark (347 x 120).
const LOCKUP = /<span role="img" aria-label="Jomhor" dir="ltr"[^>]*><img [^>]*viewBox=&#x27;0%200%20120%20120[^>]*><img [^>]*viewBox=&#x27;0%200%20347%20120[^>]*><\/span>/

it('header: the Jomhor lockup at the start (mark then wordmark), the club as secondary text, no crest', async () => {
  const { header } = await render()
  expect(header).toMatch(LOCKUP)
  expect(header.search(LOCKUP)).toBeLessThan(header.indexOf('Billetterie ATLAS FC'))
  expect(header.indexOf('Billetterie ATLAS FC')).toBeLessThan(header.indexOf('<nav'))
  expect(header).not.toContain('bg-accent')
})

it('AR: the lockup stays first and LTR (never mirrored); the club line is in Arabic', async () => {
  locale = 'ar'
  const { header } = await render()
  expect(header.search(LOCKUP)).toBeGreaterThan(-1)
  expect(header.search(LOCKUP)).toBeLessThan(header.indexOf('تذاكر ATLAS FC'))
})

it('footer: lockup + tagline', async () => {
  const { footer } = await render()
  expect(footer).toMatch(LOCKUP)
  expect(footer).toContain('Jomhor. Le 12e homme.')
})
