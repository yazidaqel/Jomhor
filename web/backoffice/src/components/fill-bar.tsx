import { cn } from '@/lib/utils'

/**
 * Paid (ink) then held (amber) on a slate track, as shares of `quota`. Decorative: the numbers sit next to it in text.
 * A non-zero share keeps 2 px so a first sale is visible.
 */
export function FillBar({ paid, held = 0, quota, className }: { paid: number; held?: number; quota: number; className?: string }) {
  const width = (n: number) => (quota > 0 ? `${Math.min(100, (n / quota) * 100)}%` : '0%')
  return (
    <div aria-hidden="true" className={cn('flex h-1.5 w-full overflow-hidden rounded-full bg-muted', className)}>
      <div className="h-full bg-chart-1 transition-[width] duration-700 ease-out" style={{ width: width(paid), minWidth: paid > 0 ? 2 : 0 }} />
      {held > 0 && <div className="h-full bg-chart-2 transition-[width] duration-700 ease-out" style={{ width: width(held), minWidth: 2 }} />}
    </div>
  )
}
