import type { Metadata } from 'next'
import { Barlow_Condensed, Inter } from 'next/font/google'
import { shopName } from '@/lib/club'
import { defaultLocale, dirOf, getDictionary } from '@/lib/i18n'
import './globals.css'

// Families and weights of design/tokens.json `font` (next/font needs literals).
const display = Barlow_Condensed({ weight: ['600', '700', '800'], subsets: ['latin'], variable: '--font-display' })
const body = Inter({ weight: ['400', '500', '600'], subsets: ['latin'], variable: '--font-body' })

const d = getDictionary(defaultLocale)

/** Read per request: `SHOP_ORIGIN` makes canonical and Open Graph URLs absolute (unset at build time). */
export function generateMetadata(): Metadata {
  const origin = process.env.SHOP_ORIGIN
  return {
    title: { default: shopName, template: `%s · ${shopName}` },
    ...(URL.canParse(origin ?? '') && { metadataBase: new URL(origin!) }),
    openGraph: { siteName: shopName, locale: d.meta.og, type: 'website' },
  }
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // ponytail: one locale (FR) until the language switch lands (Q26); `dirOf` already flips for AR.
  return (
    <html lang={defaultLocale} dir={dirOf(defaultLocale)} className={`${display.variable} ${body.variable}`}>
      <body className="min-h-dvh">{children}</body>
    </html>
  )
}
