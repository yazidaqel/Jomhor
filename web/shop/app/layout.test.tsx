import { renderToString } from 'react-dom/server'
import { beforeEach, expect, it, vi } from 'vitest'
import RootLayout from './layout'

let locale: string | undefined
let acceptLanguage: string | null = null
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (name === 'locale' && locale ? { name, value: locale } : undefined) }),
  headers: async () => new Headers(acceptLanguage ? { 'accept-language': acceptLanguage } : {}),
}))
// next/font is compiled away by Next; here each family is just its CSS variable and family names.
vi.mock('next/font/google', () => {
  const font = (family: string) => (o: { variable: string }) => ({ variable: o.variable, style: { fontFamily: `'${family}', '${family} Fallback'` } })
  return { Barlow_Condensed: font('Barlow Condensed'), Inter: font('Inter'), Cairo: font('Cairo'), IBM_Plex_Sans_Arabic: font('IBM Plex Sans Arabic') }
})
vi.mock('./globals.css', () => ({}))

beforeEach(() => {
  locale = undefined
  acceptLanguage = null
})
const html = async () => renderToString(await RootLayout({ children: null }))

it('FR by default: <html lang="fr" dir="ltr">, Latin stacks', async () => {
  const h = await html()
  expect(h).toMatch(/<html lang="fr" dir="ltr"/)
  expect(h).not.toContain('--font-ar-display)')
})

it('AR: <html lang="ar" dir="rtl">; the Latin face first (digits, codes), then the Arabic face', async () => {
  locale = 'ar'
  const h = await html()
  expect(h).toMatch(/<html lang="ar" dir="rtl"/)
  expect(h).toContain("--font-display:&#x27;Barlow Condensed&#x27;, var(--font-ar-display)")
  expect(h).toContain("--font-body:&#x27;Inter&#x27;, var(--font-ar-body)")
})

it('Accept-Language picks the locale when there is no cookie; the cookie wins over it', async () => {
  acceptLanguage = 'en-US,en;q=0.9'
  expect(await html()).toMatch(/<html lang="en" dir="ltr"/)
  locale = 'ar'
  expect(await html()).toMatch(/<html lang="ar" dir="rtl"/)
})
