import 'server-only'
import { cookies, headers } from 'next/headers'
import { cache } from 'react'
import { getDictionary, LOCALE_COOKIE, resolveLocale } from './index'

/**
 * The request's locale for server components: the `locale` cookie, else `Accept-Language`, else FR. `?lang=` reaches
 * here as that cookie (proxy.ts rewrites it into the request). Reading it makes the page dynamic, as event pages are.
 */
export const getLocale = cache(async () =>
  resolveLocale({ cookie: (await cookies()).get(LOCALE_COOKIE)?.value, acceptLanguage: (await headers()).get('accept-language') }),
)

/** `{ locale, d }` for this request. */
export const getI18n = cache(async () => {
  const locale = await getLocale()
  return { locale, d: getDictionary(locale) }
})
