import QRCode from 'react-qr-code'
import { ArrowLeft, Printer } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { ErrorNotice, LoadingRows } from '@/components/page'
import { Button } from '@/components/ui/button'
import { useOrganizer } from '@/features/auth/organizer'
import { formatDh } from '@/lib/money'
import { formatTime } from '@/lib/time'
import { shortNo, usePrintData, type PrintData, type PrintTicket } from './api'

// Q48: 80 mm thermal roll printed from Chrome (`--kiosk-printing` skips the dialog). Scoped to this page: rendered
// only while it is mounted, so no other page prints at 80 mm. Not `size: 80mm auto` (the plan's wording): that is not
// valid CSS and Chrome drops the whole descriptor. One fixed page per slip instead, the slip clipped to it.
// ponytail: fixed 140 mm per slip (content ~132 mm, title clamped to 2 lines); measure per print if titles grow.
const SLIP_HEIGHT = '140mm'
const PAGE_CSS = `@page { size: 80mm ${SLIP_HEIGHT}; margin: 0 }`

/** POS print view `/pos/print/:orderId`, outside the shell chrome: one slip per ticket (DESIGN §POS slip). */
export function PrintPage() {
  const { t } = useTranslation()
  const orderId = useParams().orderId ?? ''
  const query = usePrintData(orderId)
  return (
    <div className="min-h-screen bg-muted/40 print:min-h-0 print:bg-white">
      <style>{PAGE_CSS}</style>
      <header className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b bg-background/90 px-4 py-3 backdrop-blur print:hidden">
        <Button variant="ghost" asChild>
          <Link to="/pos">
            <ArrowLeft />
            {t('pos.print.back')}
          </Link>
        </Button>
        <div className="min-w-0">
          <h1 className="font-semibold">{t('pos.print.title')}</h1>
          {query.data && <p className="text-xs text-muted-foreground">{t('pos.print.description', { count: query.data.tickets.length })}</p>}
        </div>
        <Button className="ml-auto" disabled={!query.data} onClick={() => window.print()}>
          <Printer />
          {t('pos.print.print')}
        </Button>
      </header>
      <main>
        {query.isPending ? (
          <LoadingRows className="mx-auto max-w-sm p-6" />
        ) : query.isError ? (
          // No automatic retry (each GET is an audited print): this button is the explicit reprint.
          <ErrorNotice error={query.error} onRetry={() => void query.refetch()} className="justify-center" />
        ) : (
          <div className="flex flex-col items-center gap-6 py-6 print:block print:p-0">
            {query.data.tickets.map((ticket) => (
              <Slip key={ticket.id} data={query.data} ticket={ticket} />
            ))}
          </div>
        )}
      </main>
    </div>
  )
}

/**
 * One ticket on 80 mm paper (72 mm printable): black only, no raster. Holder = `holder_label` only, never a full name.
 * The QR is the API's `qr` exactly (static v1 token, ECC M) at 38 mm.
 */
function Slip({ data, ticket }: { data: PrintData; ticket: PrintTicket }) {
  const { t, i18n } = useTranslation()
  const club = useOrganizer().organizer_name
  const { event } = data
  const date = new Intl.DateTimeFormat(i18n.language, { timeZone: event.timezone, dateStyle: 'medium' }).format(Date.parse(event.starts_at))
  return (
    <article
      aria-label={t('pos.print.slip', { no: ticket.ticket_no })}
      style={{ height: SLIP_HEIGHT }}
      className="flex w-[80mm] break-after-page flex-col overflow-hidden bg-white px-[4mm] py-[5mm] font-sans text-[11pt] leading-snug text-black shadow-sm last:break-after-auto print:shadow-none"
    >
      <div className="text-center">
        <div className="text-[9pt] font-bold tracking-[0.2em] uppercase">{club}</div>
        <div className="mt-1 line-clamp-2 text-[13pt] leading-tight font-bold">{event.title}</div>
        <div className="mt-1">{t('pos.print.when', { date, time: formatTime(event.starts_at, i18n.language, event.timezone) })}</div>
        <div>{event.venue.name}</div>
      </div>
      <div className="my-[3mm] border-t border-dashed border-black" />
      <dl className="grid grid-cols-2 gap-x-[3mm]">
        <div>
          <dt className="text-[8pt] font-bold uppercase">{t('pos.print.zone')}</dt>
          <dd className="text-[16pt] leading-tight font-bold">{ticket.zone.code}</dd>
          <dd className="text-[9pt]">{ticket.zone.name}</dd>
        </div>
        <div className="text-right">
          <dt className="text-[8pt] font-bold uppercase">{t('pos.print.gates', { count: ticket.gate_codes.length })}</dt>
          <dd className="text-[16pt] leading-tight font-bold">{ticket.gate_codes.join(' · ')}</dd>
        </div>
        <div className="col-span-2 mt-[2mm]">
          <dt className="text-[8pt] font-bold uppercase">{t('pos.print.holder')}</dt>
          <dd className="text-[12pt] font-bold">{ticket.holder_label}</dd>
        </div>
      </dl>
      <div className="mt-[3mm] flex flex-col items-center">
        {ticket.qr ? (
          <QRCode value={ticket.qr} level="M" size={256} fgColor="#000" bgColor="#fff" role="img" title={t('pos.print.qr', { no: ticket.ticket_no })} style={{ width: '38mm', height: '38mm' }} />
        ) : (
          <div className="flex h-[38mm] w-full items-center justify-center border-2 border-black text-center text-[12pt] font-bold uppercase">
            {t('pos.print.notValid', { status: t(`pos.print.status.${ticket.status}`) })}
          </div>
        )}
        {/* 4 mm under the QR: its quiet zone stays clear of the number. */}
        <div className="mt-[4mm] font-mono text-[13pt] font-bold tracking-[0.15em]">{ticket.ticket_no}</div>
      </div>
      <div className="my-[3mm] border-t border-dashed border-black" />
      <div className="flex items-baseline justify-between gap-2 text-[9pt]">
        <span>
          {t('pos.print.price')} <b className="text-[11pt]">{t('pos.amount', { amount: formatDh(ticket.unit_price_mad, i18n.language) })}</b>
        </span>
        <span>{t('pos.print.order', { no: shortNo(data.order_id) })}</span>
      </div>
      <div className="mt-auto pt-[2mm] text-center text-[8pt]">{t('pos.print.footer')}</div>
    </article>
  )
}
