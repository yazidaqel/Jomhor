import { NextResponse, type NextRequest } from 'next/server'
import { isLocale, LOCALE_COOKIE } from '@/lib/i18n'

/**
 * Plan Q65: `?lang=ar|fr|en` wins and sets the `locale` cookie for the next visits, so a shared Arabic link opens in
 * Arabic. URLs stay unprefixed. The cookie is also written into this request, so the page rendered now uses it.
 */
export function proxy(req: NextRequest) {
  const lang = req.nextUrl.searchParams.get('lang')
  if (!isLocale(lang)) return NextResponse.next()
  req.cookies.set(LOCALE_COOKIE, lang)
  const res = NextResponse.next({ request: { headers: req.headers } })
  // Read by the server only (the switcher is plain links), so httpOnly; Secure outside local dev (plain http).
  res.cookies.set(LOCALE_COOKIE, lang, {
    path: '/',
    maxAge: 60 * 60 * 24 * 365,
    sameSite: 'lax',
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
  })
  return res
}

// Pages only: route handlers (`/api/…`), Next assets, the favicon and the OG image (`…/opengraph-image-<hash>`) are
// skipped (proxy.test.ts checks the matcher).
export const config = { matcher: ['/((?!api/|_next/|favicon\\.ico|.*/opengraph-image).*)'] }
