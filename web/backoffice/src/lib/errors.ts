import { fr } from '@/i18n/fr'
import { ApiError } from './api'

type Code = keyof typeof fr.errors

/** i18n key for an error's message: the API code when there is copy for it, else `errors.generic`. */
export function errorKey(e: unknown): `errors.${Code}` {
  const code = e instanceof ApiError ? e.code : ''
  return Object.hasOwn(fr.errors, code) ? `errors.${code as Code}` : 'errors.generic'
}
