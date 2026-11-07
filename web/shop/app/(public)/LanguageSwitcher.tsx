'use client'

import { usePathname, useSearchParams } from 'next/navigation'
import { localeNames, locales, type Locale } from '@/lib/i18n'
import { CheckIcon, GlobeIcon } from '../icons'

/** The button's short form on phones, in the language's own script. */
const short: Record<Locale, string> = { fr: 'FR', ar: 'عربي', en: 'EN' }

/**
 * Header language switch (plan Q65): each language in its own name and script, the current one checked. Each option
 * is this same URL with `?lang=`, which sets the `locale` cookie (proxy.ts); a full page load, so `<html lang dir>` and
 * the fonts follow. The other query parameters are kept. A signed-in fan's choice is also saved on their account
 * (`PATCH /api/me {locale}`, so WhatsApp and push follow it): best-effort, `keepalive` so it outlives the page load, and
 * a failure (or no session: 401, no backend call) never holds the switch back.
 */
export function LanguageSwitcher({ locale, label }: { locale: Locale; label: string }) {
  const pathname = usePathname()
  const search = useSearchParams()
  const href = (l: Locale) => {
    const q = new URLSearchParams(search)
    q.set('lang', l)
    return `${pathname}?${q}`
  }
  const save = (l: Locale) =>
    fetch('/api/me', { method: 'PATCH', keepalive: true, headers: { 'content-type': 'application/json' }, body: JSON.stringify({ locale: l }) }).catch(
      () => {},
    )
  return (
    <>
      <button
        type="button"
        popoverTarget="language-menu"
        className="flex min-h-touch-min items-center gap-2 rounded-pill px-3 text-sm text-text-secondary transition-colors duration-200 hover:text-text"
      >
        <GlobeIcon className="size-5" />
        <span className="sr-only">{label}</span>
        <span lang={locale} className="font-semibold max-sm:hidden">
          {localeNames[locale]}
        </span>
        <span lang={locale} aria-hidden="true" className="font-semibold sm:hidden">
          {short[locale]}
        </span>
      </button>
      <div
        id="language-menu"
        popover="auto"
        className="inset-auto end-[max(1.5rem,calc((100vw-1180px)/2+1.5rem))] top-18 m-0 w-56 rounded-control border border-border bg-surface p-2 text-text shadow-2xl shadow-ground"
      >
        <p className="px-3 pt-2 pb-2 text-caption uppercase tracking-[1px] text-text-muted">{label}</p>
        <ul>
          {locales.map((l) => (
            <li key={l}>
              <a
                href={href(l)}
                lang={l}
                hrefLang={l}
                aria-current={l === locale ? 'true' : undefined}
                onClick={() => l !== locale && save(l)}
                className={`flex min-h-touch-min items-center justify-between gap-3 rounded-chip px-3 text-sm transition-colors duration-200 hover:bg-surface2 aria-[current]:font-semibold aria-[current]:text-accent ${l === 'ar' ? 'font-ar text-base' : ''}`}
              >
                {localeNames[l]}
                {l === locale && <CheckIcon className="size-4" />}
              </a>
            </li>
          ))}
        </ul>
      </div>
    </>
  )
}
