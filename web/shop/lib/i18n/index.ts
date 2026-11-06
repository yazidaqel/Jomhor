import { ar } from './ar'
import { en } from './en'
import { fr } from './fr'

export const locales = ['fr', 'ar', 'en'] as const
export type Locale = (typeof locales)[number]
export const defaultLocale: Locale = 'fr'

export type Dict = typeof fr
type DeepPartial<T> = { [K in keyof T]?: T[K] extends string ? string : DeepPartial<T[K]> }
export type Stub = DeepPartial<Dict>

/** `base` with every string `over` defines replaced; anything missing keeps the FR value. */
export function merge<T extends object>(base: T, over: DeepPartial<T> | undefined): T {
  const out = { ...base } as Record<string, unknown>
  for (const [k, v] of Object.entries(over ?? {})) {
    const b = (base as Record<string, unknown>)[k]
    if (typeof v === 'string' && typeof b === 'string') out[k] = v
    else if (v && typeof v === 'object' && b && typeof b === 'object') out[k] = merge(b, v)
  }
  return out as T
}

const partials: Record<Locale, Stub> = { fr: {}, ar, en }

export const getDictionary = (locale: Locale): Dict => merge(fr, partials[locale])

export const dirOf = (locale: Locale) => (locale === 'ar' ? 'rtl' : 'ltr')

/** `"au {phone}"` + `{ phone: '+212…' }` → `"au +212…"`. Unknown placeholders stay as they are. */
export const fmt = (s: string, vars: Record<string, string | number>) =>
  s.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m))
