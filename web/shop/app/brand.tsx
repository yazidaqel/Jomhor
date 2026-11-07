import Image from 'next/image'
import mark from '../../../design/logo/jomhor-mark-transparent.svg'
import { club } from '@/lib/club'

/**
 * The Jomhor 12-dot J, the file from design/logo as is (BRAND.md: never redrawn, recoloured or put in a shape).
 * Decorative wherever it is used: the text next to it names the brand.
 */
export function JMark({ size, className = '' }: { size: number; className?: string }) {
  return <Image src={mark} alt="" width={size} height={size} unoptimized className={`shrink-0 ${className}`} />
}

/** The club crest: design/tokens.json `club` (initial on accent until a crest asset lands, Q23). */
export function Crest({ size, className = '' }: { size: 'sm' | 'md' | 'lg'; className?: string }) {
  const s = { sm: 'size-8 text-lg', md: 'size-11 text-[22px]', lg: 'size-16 text-[32px]' }[size]
  return (
    <span
      aria-hidden="true"
      className={`flex shrink-0 items-center justify-center rounded-pill bg-accent font-display font-extrabold text-on-accent ${s} ${className}`}
    >
      {club.crestInitial}
    </span>
  )
}

/** The crest, stadium-sized and faint, behind a hero (wide screens only). The parent is `relative isolate`. */
export function Watermark() {
  return (
    <span
      aria-hidden="true"
      className="pointer-events-none absolute -end-6 -bottom-28 -z-10 font-display text-[460px] leading-none font-extrabold text-text opacity-[0.04] select-none max-lg:hidden"
    >
      {club.crestInitial}
    </span>
  )
}
