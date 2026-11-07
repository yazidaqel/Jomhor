import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { RISE, stagger } from '@/lib/motion'
import { cn } from '@/lib/utils'

/** KPI card (Aperçu, Ventes, Portes en direct): label + icon, the figure, then muted detail lines. `index` staggers the entrance. */
export function Kpi({ index = 0, icon: Icon, label, value, children }: { index?: number; icon: LucideIcon; label: string; value: string; children?: ReactNode }) {
  return (
    <Card className={cn('gap-2 py-5', RISE)} style={stagger(index)}>
      <CardContent className="flex flex-col gap-2 px-5">
        <div className="flex items-center justify-between gap-3">
          <span className="text-sm font-medium text-muted-foreground">{label}</span>
          <span className="flex size-8 items-center justify-center rounded-lg bg-muted">
            <Icon className="size-4 text-foreground/70" aria-hidden="true" />
          </span>
        </div>
        {/* Wraps rather than overflows: a revenue in DH can be long. */}
        <div className="text-2xl leading-tight font-semibold tracking-tight tabular-nums md:text-[1.75rem]">{value}</div>
        {children && <div className="flex flex-col gap-2 text-xs text-muted-foreground">{children}</div>}
      </CardContent>
    </Card>
  )
}
