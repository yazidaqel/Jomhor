// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { CLUB_SLUG } from '@/lib/clubs'

// `/{club}` lives beside the shop's own top-level paths, so docs/03-schema.sql `organizers_slug` must reserve every
// one of them: a club named `orders` would otherwise never be reachable (or would shadow a page).
const schema = readFileSync(new URL('../../../docs/03-schema.sql', import.meta.url), 'utf8')
const check = /constraint organizers_slug check \(([\s\S]*?\))\)/.exec(schema)![1]!
const reserved = new Set([.../slug not in \(([^)]*)\)/.exec(check)![1]!.matchAll(/'([^']+)'/g)].map((m) => m[1]))

// Next.js metadata file routes served at the root (`/manifest.webmanifest`, `/opengraph-image`, …).
const FILE_ROUTES = ['manifest', 'opengraph-image', 'twitter-image', 'icon', 'apple-icon', 'robots', 'sitemap', 'favicon']

/** Top-level URL segments of a folder: route folders (not `(group)`, `[param]` or `_private`) and file routes. */
const segments = (dir: string) =>
  readdirSync(new URL(dir, import.meta.url), { withFileTypes: true }).flatMap((e) => {
    if (e.isDirectory()) return /^[([_]/.test(e.name) ? [] : [e.name]
    const base = e.name.split('.')[0]!
    return FILE_ROUTES.includes(base) && !e.name.includes('.test.') ? [base] : []
  })

it('the reserved list was found in docs/03-schema.sql', () => {
  expect(reserved).toContain('mine')
  expect(reserved).toContain('all')
})

it.each([...new Set([...segments('./'), ...segments('./(public)/')])])('top-level segment /%s is a reserved slug', (seg) => {
  expect(reserved).toContain(seg)
})

it('the shop checks slugs with the 03 pattern', () => {
  expect(check).toContain(`slug ~ '${CLUB_SLUG.source}'`)
  expect(check).toContain('length(slug) between 2 and 40')
})
