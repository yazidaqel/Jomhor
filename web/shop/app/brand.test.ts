// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { expect, it } from 'vitest'

// BRAND §Where the logo appears: the shop serves the Jomhor files as they are, never a retyped or edited copy.
const BRAND = new URL('../public/brand/', import.meta.url)
const LOGO = new URL('../../../design/logo/', import.meta.url)

it.each(readdirSync(BRAND))('public/brand/%s is the design/logo file of the same name, byte for byte', (name) => {
  expect(readFileSync(new URL(name, BRAND)).equals(readFileSync(new URL(name, LOGO)))).toBe(true)
})
