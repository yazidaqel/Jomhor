// @vitest-environment node
import { ESLint } from 'eslint'
import { beforeAll, expect, it } from 'vitest'
import { dirOf, fmt, getDictionary, locales, resolveLocale } from './index'
import { fr } from './fr'

const entries = (o: object, p = ''): [string, string][] =>
  Object.entries(o).flatMap(([k, v]) => (typeof v === 'string' ? [[p + k, v] as [string, string]] : entries(v as object, `${p}${k}.`)))

it.each(locales)('%s has every FR key, each a non-empty string with the same placeholders', (locale) => {
  const d = entries(getDictionary(locale))
  expect(d.map(([k]) => k)).toEqual(entries(fr).map(([k]) => k))
  const vars = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()
  for (const [[k, v], [, f]] of d.map((e, i) => [e, entries(fr)[i]!] as const)) {
    expect(v.trim(), k).not.toBe('')
    expect(vars(v), k).toEqual(vars(f))
  }
})

it('AR copy is in Arabic script (Darija, Q64), apart from codes, brand names and examples', () => {
  const text = (v: string) => v.replace(/\{\w+\}/g, '')
  const latinOnly = entries(getDictionary('ar')).filter(([k, v]) => !k.startsWith('meta.') && /\p{L}/u.test(text(v)) && !/\p{Script=Arabic}/u.test(v))
  expect(latinOnly.map(([k]) => k)).toEqual(['app.name'])
})

it('AR is RTL, FR and EN are LTR', () => {
  expect([dirOf('ar'), dirOf('fr'), dirOf('en')]).toEqual(['rtl', 'ltr', 'ltr'])
})

it('Q65 order: ?lang= > cookie > Accept-Language > fr', () => {
  const al = 'ar-MA,ar;q=0.9,fr;q=0.8'
  expect(resolveLocale({ lang: 'en', cookie: 'ar', acceptLanguage: al })).toBe('en')
  expect(resolveLocale({ lang: 'xx', cookie: 'ar', acceptLanguage: 'en' })).toBe('ar') // unknown ?lang= is ignored
  expect(resolveLocale({ cookie: 'en', acceptLanguage: al })).toBe('en')
  expect(resolveLocale({ cookie: 'de', acceptLanguage: al })).toBe('ar') // unknown cookie is ignored
  expect(resolveLocale({ acceptLanguage: al })).toBe('ar')
  expect(resolveLocale({ acceptLanguage: 'de-DE, en-GB;q=0.7, ar;q=0.8' })).toBe('ar') // by q, then order
  expect(resolveLocale({ acceptLanguage: 'EN-us' })).toBe('en')
  expect(resolveLocale({ acceptLanguage: 'ar;q=0, *' })).toBe('fr') // q=0 refuses, * picks nothing
  expect(resolveLocale({ acceptLanguage: 'de, es' })).toBe('fr')
  expect(resolveLocale({})).toBe('fr')
})

it('fmt fills placeholders', () => {
  expect(fmt('au {phone} ({seconds} s) {x}', { phone: '+212612345678', seconds: 5 })).toBe('au +212612345678 (5 s) {x}')
})

// The "no hard-coded user-visible strings" guard is ESLint (eslint.config.mjs); this pins what it catches.
const eslint = new ESLint()
// The first lint loads the flat config, typescript-eslint and the plugins: seconds on a cold, busy machine (all test
// files run in parallel). Pay it once here with a generous timeout so no single case races the 5 s default.
beforeAll(async () => {
  await eslint.lintText('export {}', { filePath: 'app/warmup/Warmup.tsx' })
}, 60_000)
async function literalErrors(jsx: string, filePath = 'app/sample/Sample.tsx') {
  const code = `export function Sample({ ok, d }: { ok: boolean; d: { t: string } }) {\n  return ${jsx}\n}\n`
  const [result] = await eslint.lintText(code, { filePath })
  return result!.messages.filter((m) => /i18next|no-restricted-syntax/.test(m.ruleId ?? '')).length
}

it.each(['<p>Bonjour</p>', "<p>{ok ? 'Oui' : 'Non'}</p>", '<input placeholder="Nom" />', '<div aria-label={`Fermer`} />'])(
  'lint flags %s',
  async (jsx) => expect(await literalErrors(jsx)).toBeGreaterThan(0),
)

it.each(['<p>{d.t}</p>', '<input placeholder={d.t} id="phone" type="tel" autoComplete="tel" />', '<img alt="" />'])(
  'lint allows %s',
  async (jsx) => expect(await literalErrors(jsx)).toBe(0),
)

it('the guard covers .tsx outside app/ too', async () => {
  expect(await literalErrors('<p>Bonjour</p>', 'components/Sample.tsx')).toBeGreaterThan(0)
  expect(await literalErrors('<p>Bonjour</p>', 'lib/sample/Sample.tsx')).toBeGreaterThan(0)
})
