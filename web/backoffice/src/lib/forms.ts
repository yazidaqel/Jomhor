import type { TFunction } from 'i18next'
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { z } from 'zod'
import { ApiError } from './api'
import { errorKey } from './errors'

/** A whole number written with digits only: "12.5", "1e3", "abc" and "" are rejected; spaces around are fine. */
export const integerInput = (message: string, min: number, max = 2 ** 31 - 1) =>
  z
    .string()
    .trim()
    .regex(/^\d+$/, message)
    .transform(Number)
    .refine((n) => n >= min && n <= max, message) // default max: the API column is a Postgres integer

/** True when `key` (dotted, e.g. `metadata.matchday`) is a field of the form values. */
function hasField(values: unknown, key: string) {
  let v = values
  for (const k of key.split('.')) {
    if (typeof v !== 'object' || v === null || !Object.hasOwn(v, k)) return false
    v = (v as Record<string, unknown>)[k]
  }
  return true
}

/**
 * Maps an API error onto the form: VALIDATION_FAILED on the fields named in `details.fields` (API keys, snake_case
 * or dotted like `metadata.matchday`, so form fields carry the API names), anything else as the form's root error.
 */
export function showFormError<T extends FieldValues, C, O extends FieldValues>(
  e: unknown,
  form: UseFormReturn<T, C, O>,
  t: TFunction,
  rootMessage: (e: unknown) => string = (e) => t(errorKey(e)),
) {
  const values = form.getValues()
  const invalid = e instanceof ApiError && e.code === 'VALIDATION_FAILED' ? z.record(z.string(), z.unknown()).safeParse(e.details.fields) : null
  const known = invalid?.success ? Object.keys(invalid.data).filter((f) => hasField(values, f)) : []
  if (known.length) {
    known.forEach((f, i) => form.setError(f as Path<T>, { message: t('common.fieldInvalid') }, { shouldFocus: i === 0 }))
    return
  }
  form.setError('root', { message: rootMessage(e) })
}
