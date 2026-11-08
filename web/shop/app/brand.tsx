import Image from 'next/image'
import mark from '../../../design/logo/jomhor-mark-transparent.svg'
import wordmark from '../../../design/logo/jomhor-wordmark-dark.svg'

/**
 * The Jomhor 12-dot J, the file from design/logo as is (BRAND.md: never redrawn, recoloured or put in a shape).
 * Decorative wherever it is used: the text next to it names the brand.
 */
export function JMark({ size, className = '' }: { size: number; className?: string }) {
  return <Image src={mark} alt="" width={size} height={size} unoptimized className={`shrink-0 ${className}`} />
}

/**
 * The Jomhor lockup: the mark and wordmark files laid out as in design/logo/jomhor-lockup-dark.svg (viewBox 495 x 120:
 * mark 108 tall at x 0, wordmark at x 128), without that file's ink tile so it sits on the translucent header too.
 * `dir="ltr"`: never mirrored in Arabic; the parent puts it at the start. BRAND.md minimum: 120 px wide (height >= 31).
 */
export function Lockup({ height, label, className = '' }: { height: number; label: string; className?: string }) {
  const u = height / 120
  return (
    <span role="img" aria-label={label} dir="ltr" className={`inline-flex shrink-0 items-center ${className}`} style={{ gap: 20 * u }}>
      <Image src={mark} alt="" width={Math.round(108 * u)} height={Math.round(108 * u)} unoptimized />
      <Image src={wordmark} alt="" width={Math.round(347 * u)} height={height} unoptimized />
    </span>
  )
}

/** A club as drawn: its name and crest (04 §Club directory). Never a colour: the chrome stays Jomhor. */
export type CrestOf = { name: string; crest_url: string | null }

/** The crest stand-in's letter: the name's first character, uppercased (`Olympique Tensift` → `O`, `الرجاء` → `ا`). */
export const initialOf = (name: string) => (Array.from(name.trim())[0] ?? '').toUpperCase()

/**
 * The club crest, decorative (the club name is always written next to it). `crest_url` set → the image as supplied
 * (never recoloured, never on a disc), loaded by the browser from Jomhor's crest host: `unoptimized`, so the shop never
 * fetches it and needs no `remotePatterns`. Null → the stand-in: the name's first letter on a disc (DESIGN.md
 * §Multi-club), in Jomhor tokens (`standIn` picks which: amber by default, ink on an amber ticket band).
 * ponytail: no fallback when the image fails to load (needs a client island); add one when real crests exist (Q72).
 */
export function Crest({ club, size, standIn = 'bg-accent text-on-accent', className = '' }: { club: CrestOf; size: number; standIn?: string; className?: string }) {
  if (club.crest_url) {
    return <Image src={club.crest_url} alt="" width={size} height={size} unoptimized className={`shrink-0 object-contain ${className}`} />
  }
  return (
    <span
      aria-hidden="true"
      style={{ width: size, height: size, fontSize: Math.round(size / 2) }}
      className={`flex shrink-0 items-center justify-center rounded-pill font-display leading-none font-extrabold ${standIn} ${className}`}
    >
      {initialOf(club.name)}
    </span>
  )
}

/** The club's initial, stadium-sized and faint, behind a hero (wide screens only). The parent is `relative isolate`. */
export function Watermark({ club }: { club: CrestOf }) {
  return (
    <span
      aria-hidden="true"
      className="pointer-events-none absolute -end-6 -bottom-28 -z-10 font-display text-[460px] leading-none font-extrabold text-text opacity-[0.04] select-none max-lg:hidden"
    >
      {initialOf(club.name)}
    </span>
  )
}
