// @vitest-environment node
import { ESLint } from 'eslint'
import { expect, it } from 'vitest'
import { dirOf, fmt, getDictionary, locales, merge } from './index'
import { fr } from './fr'

it.each(locales)('%s has every FR key (stubs fall back to FR)', (locale) => {
  const keys = (o: object, p = ''): string[] =>
    Object.entries(o).flatMap(([k, v]) => (typeof v === 'string' ? [p + k] : keys(v as object, `${p}${k}.`)))
  expect(keys(getDictionary(locale))).toEqual(keys(fr))
})

it('an override replaces only the strings it defines', () => {
  const d = merge(fr, { login: { title: 'Login' } })
  expect(d.login.title).toBe('Login')
  expect(d.login.verify).toBe(fr.login.verify)
  expect(d.errors).toEqual(fr.errors)
})

it('AR is RTL, FR and EN are LTR', () => {
  expect([dirOf('ar'), dirOf('fr'), dirOf('en')]).toEqual(['rtl', 'ltr', 'ltr'])
})

it('fmt fills placeholders', () => {
  expect(fmt('au {phone} ({seconds} s) {x}', { phone: '+212612345678', seconds: 5 })).toBe('au +212612345678 (5 s) {x}')
})

// The "no hard-coded user-visible strings" guard is ESLint (eslint.config.mjs); this pins what it catches.
const eslint = new ESLint()
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
