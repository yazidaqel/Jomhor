import { ar } from './ar'
import { en } from './en'
import { fr } from './fr'

export const locales = ['fr', 'ar', 'en'] as const
export type Locale = (typeof locales)[number]
export const defaultLocale: Locale = 'fr'

/** FR is the reference; `ar` and `en` are typed `Dict`, so a missing key fails the typecheck. */
export type Dict = typeof fr

const dicts: Record<Locale, Dict> = { fr, ar, en }

export const getDictionary = (locale: Locale): Dict => dicts[locale]

export const dirOf = (locale: Locale) => (locale === 'ar' ? 'rtl' : 'ltr')

/** Each language in its own name, for the switcher (never translated). */
export const localeNames: Record<Locale, string> = { fr: 'Français', ar: 'العربية', en: 'English' }

/** Cookie holding the fan's choice (Q65). Set by `?lang=` (proxy.ts). */
export const LOCALE_COOKIE = 'locale'

export const isLocale = (v: unknown): v is Locale => locales.includes(v as Locale)

/**
 * Plan Q65: `?lang=`, else the `locale` cookie, else the first supported `Accept-Language` range by q, else FR.
 * `ar-MA` and `ar` both pick `ar`; `*` and q=0 never pick anything.
 */
export function resolveLocale({ lang, cookie, acceptLanguage }: { lang?: string | null; cookie?: string | null; acceptLanguage?: string | null }): Locale {
  if (isLocale(lang)) return lang
  if (isLocale(cookie)) return cookie
  const ranges = (acceptLanguage ?? '')
    .split(',')
    .map((part, i) => {
      const [tag = '', ...params] = part.trim().split(';')
      const q = params.map((p) => p.trim()).find((p) => p.startsWith('q='))
      return { lang: tag.trim().split('-')[0]!.toLowerCase(), q: q ? Number(q.slice(2)) : 1, i }
    })
    .filter((r) => r.q > 0 && isLocale(r.lang))
    .sort((a, b) => b.q - a.q || a.i - b.i)
  return (ranges[0]?.lang as Locale | undefined) ?? defaultLocale
}

/**
 * LTR isolate (U+2066 … U+2069) for prices, codes, phone numbers and ticket numbers inside text: they keep their
 * order in an RTL line (DESIGN.md, Lot 4 AR/RTL) and are invisible in FR and EN.
 */
export const ltr = (s: string) => `\u2066${s}\u2069`

/** First-strong isolate (U+2068 \u2026 U+2069) for a name inside text: an Arabic name in a French line, or the reverse. */
export const isolate = (s: string) => `\u2068${s}\u2069`

/** `"au {phone}"` + `{ phone: '+212…' }` → `"au +212…"`. Unknown placeholders stay as they are. */
export const fmt = (s: string, vars: Record<string, string | number>) =>
  s.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m))
