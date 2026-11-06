// @vitest-environment node
import { ESLint } from 'eslint'
import { beforeAll, expect, it } from 'vitest'

// The "no hard-coded user-visible strings" guard is ESLint (eslint.config.js: i18next/no-literal-string +
// no-restricted-syntax for text attributes). This pins what it catches and what it lets through.
const eslint = new ESLint()

// The first lintText loads the whole config (typescript-eslint, plugins): seconds when the suite runs in parallel.
// Pay it here, under the hook's own timeout, rather than inside the first test's 5 s.
beforeAll(() => eslint.lintText('', { filePath: 'src/features/sample/Sample.tsx' }), 60_000)

async function literalErrors(jsx: string) {
  const code = `export function Sample({ ok, name }: { ok: boolean; name: string }) {\n  return ${jsx}\n}\n`
  const [result] = await eslint.lintText(code, { filePath: 'src/features/sample/Sample.tsx' })
  return result!.messages.filter((m) => /i18next|no-restricted-syntax/.test(m.ruleId ?? '')).length
}

it.each([
  '<p>Bonjour</p>',
  '<p>Bonjour {name}</p>',
  "<p>{'Bonjour'}</p>",
  "<p>{ok ? 'Oui' : 'Non'}</p>",
  '<p>{`Bonjour`}</p>',
  "<input placeholder={'Nom'} />",
  '<input placeholder="Nom" />',
  '<img alt="Logo du club" />',
  '<div title="Infos" />',
  '<div aria-label={`Fermer`} />',
  '<div aria-description="Détails" />',
  "<div aria-roledescription={'Diapositive'} />",
  "<div aria-valuetext={ok ? 'Plein' : undefined} />",
  '<Comp label="Libellé" />',
])('flags %s', async (jsx) => {
  expect(await literalErrors(jsx)).toBeGreaterThan(0)
})

it.each([
  "<p>{t('events.title')}</p>",
  "<div aria-label={t('shell.logout')} title={t('shell.logout')} />",
  '<img alt="" />',
  '<Comp variant="ghost" size="icon" to="/events" htmlFor="phone" autoComplete="one-time-code" />',
  "<div aria-describedby={ok ? 'code-error' : undefined} aria-hidden=\"true\" data-slot=\"x\" />",
  '<p>{name}</p>',
])('allows %s', async (jsx) => {
  expect(await literalErrors(jsx)).toBe(0)
})
