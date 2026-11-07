// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { expect, it } from 'vitest'

// BRAND §Where the logo appears: the back-office serves the Jomhor files as they are, never a retyped or edited copy.
const PUBLIC = new URL('../../public/', import.meta.url)
const LOGO = new URL('../../../../design/logo/', import.meta.url)

it.each(readdirSync(PUBLIC))('public/%s is the design/logo file of the same name, byte for byte', (name) => {
  expect(readFileSync(new URL(name, PUBLIC)).equals(readFileSync(new URL(name, LOGO)))).toBe(true)
})

it('the favicon and the apple-touch icon come from those files', () => {
  const html = readFileSync(new URL('../../index.html', import.meta.url), 'utf8')
  const icons = [...html.matchAll(/<link rel="(?:icon|apple-touch-icon)"[^>]*href="\/([^"]+)"/g)].map((m) => m[1])
  expect(icons).toEqual(['jomhor-mark-dark.svg', 'favicon-32.png', 'apple-touch-icon-180.png'])
  expect(icons.every((f) => readdirSync(PUBLIC).includes(f!))).toBe(true)
})
