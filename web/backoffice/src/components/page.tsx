import { Fragment, type ReactNode } from 'react'
import { AlertCircle } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, BreadcrumbList, BreadcrumbSeparator } from '@/components/ui/breadcrumb'
import { Button } from '@/components/ui/button'
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty'
import { Skeleton } from '@/components/ui/skeleton'
import { errorKey } from '@/lib/errors'
import { cn } from '@/lib/utils'

// Page building blocks shared by every back-office page: one header, one empty state, one loading and one error look.

/** Sticky header inside a scrolling table card: the header row stays while a long list scrolls. */
export const STICKY_TABLE =
  '[&_[data-slot=table-container]]:max-h-[calc(100vh-15rem)] [&_thead]:sticky [&_thead]:top-0 [&_thead]:z-10 [&_thead]:bg-muted/80 [&_thead]:backdrop-blur [&_thead_th]:h-10 [&_thead_th]:text-xs [&_thead_th]:font-medium [&_thead_th]:text-muted-foreground [&_th:first-child]:pl-5 [&_td:first-child]:pl-5 [&_th:last-child]:pr-5 [&_td:last-child]:pr-5 [&_td]:py-3 [&_tbody_th]:py-3 [&_tfoot_th]:py-3'

export type Crumb = { label: string; to?: string }

/** Breadcrumbs (nested pages), h1 + optional badge, description, actions on the right. */
export function PageHeader(props: { title: ReactNode; description?: ReactNode; crumbs?: Crumb[]; badge?: ReactNode; actions?: ReactNode }) {
  const { t } = useTranslation()
  return (
    <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
      <div className="min-w-0 space-y-1.5">
        {props.crumbs && (
          <Breadcrumb aria-label={t('common.breadcrumb')}>
            <BreadcrumbList className="sm:gap-1.5">
              {props.crumbs.map((c, i) => (
                <Fragment key={i}>
                  {i > 0 && <BreadcrumbSeparator />}
                  <BreadcrumbItem className="min-w-0">
                    {c.to ? (
                      <BreadcrumbLink asChild>
                        <Link to={c.to} className="truncate rounded-sm focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
                          {c.label}
                        </Link>
                      </BreadcrumbLink>
                    ) : (
                      <span aria-current="page" className="truncate text-foreground">
                        {c.label}
                      </span>
                    )}
                  </BreadcrumbItem>
                </Fragment>
              ))}
            </BreadcrumbList>
          </Breadcrumb>
        )}
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight text-balance">{props.title}</h1>
          {props.badge}
        </div>
        {props.description && <p className="max-w-prose text-sm text-muted-foreground">{props.description}</p>}
      </div>
      {props.actions && <div className="flex flex-wrap items-center gap-2">{props.actions}</div>}
    </header>
  )
}

/** Empty state with the Jomhor mark: what is missing and, when the role allows, the action that fills it. */
export function BrandEmpty({ title, description, action, className }: { title: string; description: string; action?: ReactNode; className?: string }) {
  return (
    <Empty className={cn('gap-5 md:p-10', className)}>
      <EmptyHeader>
        <EmptyMedia>
          <img src="/jomhor-mark-dark.svg" alt="" className="size-12 rounded-xl" />
        </EmptyMedia>
        <EmptyTitle className="text-base font-semibold">{title}</EmptyTitle>
        <EmptyDescription>{description}</EmptyDescription>
      </EmptyHeader>
      {action && <EmptyContent>{action}</EmptyContent>}
    </Empty>
  )
}

/** Skeleton rows in place of the content being loaded (same rhythm as a table, so nothing jumps when it lands). */
export function LoadingRows({ rows = 4, className }: { rows?: number; className?: string }) {
  const { t } = useTranslation()
  return (
    <div aria-busy="true" className={cn('flex flex-col gap-3', className)}>
      <span className="sr-only">{t('common.loading')}</span>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9 w-full" style={{ opacity: 1 - i * 0.15 }} />
      ))}
    </div>
  )
}

/** A failed load, with its FR message and a retry. */
export function ErrorNotice({ error, onRetry, className }: { error: unknown; onRetry: () => void; className?: string }) {
  const { t } = useTranslation()
  return (
    <div className={cn('flex flex-wrap items-center gap-3 p-4 text-sm', className)}>
      <AlertCircle className="size-4 shrink-0 text-destructive" aria-hidden="true" />
      <span className="text-destructive">{t(errorKey(error))}</span>
      <Button variant="outline" size="sm" onClick={onRetry}>
        {t('common.retry')}
      </Button>
    </div>
  )
}
