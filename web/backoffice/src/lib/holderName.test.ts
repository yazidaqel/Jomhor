import { expect, it } from 'vitest'
import shared from '../../../../docs/holder-name-vectors.json'
import { normalizeHolderName } from './holderName'

// docs/holder-name-vectors.json: the case list Java, Dart and TS share (04 §Holder names). Labels and initials come
// from the server, so the back-office checks `ok` and `normalized`.
type Vector = { input: string; ok: boolean; normalized?: string }
const vectors: Vector[] = shared

it('the shared vectors file is not empty', () => expect(vectors.length).toBeGreaterThan(0))

it.each(vectors.map((v) => [v.input, v.ok ? v.normalized! : null] as const))('shared vector %j → %j', (input, normalized) => {
  expect(normalizeHolderName(input)).toBe(normalized)
})
