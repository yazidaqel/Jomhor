// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'

// DESIGN.md Lot 4 AR/RTL: layouts mirror, so app/ uses logical utilities only (ms-/me-/ps-/pe-/start-/end-,
// text-start, float-start…). A physical one would stay put in Arabic.
const PHYSICAL = /(?<=^|[\s"'`:])-?(?:(?:ml|mr|pl|pr|left|right|border-[lr]|rounded-[lr]|scroll-m[lr]|scroll-p[lr])-[\w[]|(?:text|float|clear)-(?:left|right)\b)/

const files = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? files(join(dir, e.name)) : /\.tsx?$/.test(e.name) ? [join(dir, e.name)] : []))

it('app/ has no physical left/right utilities', () => {
  const hits = files('app').flatMap((f) =>
    readFileSync(f, 'utf8')
      .split('\n')
      .flatMap((line, i) => (PHYSICAL.test(line) && !line.trimStart().startsWith('//') && !f.endsWith('rtl.test.ts') ? [`${f}:${i + 1}: ${line.trim()}`] : [])),
  )
  expect(hits).toEqual([])
})

it('the guard catches physical utilities, variants and negatives, and lets logical ones through', () => {
  for (const c of ['ml-2', 'mr-4', 'pl-3', 'pr-[3px]', 'left-0', '-right-4', 'md:ml-2', 'rtl:mr-1', 'text-left', 'float-right', '"pl-2 x"'])
    expect(PHYSICAL.test(` ${c}`), c).toBe(true)
  for (const c of ['ms-2', 'me-4', 'ps-3', 'pe-1', 'start-0', '-end-40', 'text-start', 'float-start', 'inset-x-0', 'safe-area-inset-left', 'templ-x'])
    expect(PHYSICAL.test(` ${c}`), c).toBe(false)
})
