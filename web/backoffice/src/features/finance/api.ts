import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useOrganizer, useOrgQuery } from '@/features/auth/organizer'
import { download } from '@/lib/api'
import { errorKey } from '@/lib/errors'

// GET /org/finance/cash and its .csv (04-api §Organizer, Lot 3 Q50). ADMIN, OPS, FINANCE. Every `*_mad` is integer
// centimes. Network statements are matched by hand against this report.
export type CashDay = {
  /** Local date (`YYYY-MM-DD`) in the report's `timezone`. */
  day: string
  /** The payment's `provider`: `POS` for a till, the network's (e.g. `FAKE_CASH`) for a cash code. */
  collector: string
  /** Its display name: "Guichet" for `POS`, the network's name otherwise. */
  collector_name: string
  method: 'POS_CASH' | 'CASH_AGENCY'
  payments: number
  amount_mad: number
}
/** A till session opened in the range. Still open: `closed_at`, `declared_mad`, `variance_mad` null, `expected_mad` so far. */
export type CashSession = {
  id: string
  label: string
  staff: { id: string; name: string }
  opened_at: string
  closed_at: string | null
  orders: number
  tickets: number
  prints: number
  expected_mad: number
  declared_mad: number | null
  /** declared − expected. */
  variance_mad: number | null
}
export type CashReport = {
  from: string
  to: string
  timezone: string
  days: CashDay[]
  sessions: CashSession[]
  totals: { collected_mad: number; declared_mad: number; variance_mad: number; open_sessions: number }
}

/** RFC 3339 [from, to) bounds and the IANA zone that makes `day`: at most 92 days (422 past that). */
export type CashRange = { from: string; to: string; timezone: string }

const query = ({ from, to, timezone }: CashRange) => `?${new URLSearchParams({ from, to, timezone })}`

export const useCashReport = (range: CashRange) => useOrgQuery<CashReport>(['finance', 'cash', range], `/org/finance/cash${query(range)}`)

/** "Exporter CSV": Bearer + X-Organizer-Id, saved under the server's filename with its BOM. */
export function useCashCsv() {
  const { t } = useTranslation()
  const { organizer_id: organizerId } = useOrganizer()
  return useMutation({
    mutationFn: ({ range, fallback }: { range: CashRange; fallback: string }) => download(`/org/finance/cash.csv${query(range)}`, fallback, { organizerId }),
    onError: (e) => toast.error(t(errorKey(e))),
  })
}
