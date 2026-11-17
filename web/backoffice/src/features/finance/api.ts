import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useOrganizer, useOrgMutation, useOrgQuery, type Role } from '@/features/auth/organizer'
import { download, path } from '@/lib/api'
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
  /** Lot 5 step 5: its live deposit (`deposited_on` a local date), null while not deposited. */
  deposit: { id: string; reference: string; deposited_on: string } | null
}
export type CashReport = {
  from: string
  to: string
  timezone: string
  days: CashDay[]
  sessions: CashSession[]
  /** `undeposited_mad` (Lot 5 step 5): Σ `declared_mad` of the range's closed sessions with no live deposit. */
  totals: { collected_mad: number; declared_mad: number; variance_mad: number; open_sessions: number; undeposited_mad: number }
}

/**
 * Local dates `YYYY-MM-DD`, both included, in the IANA `timezone`: at most 92 days (422 past that). The server turns
 * them into instants with its own tzdata (a browser's Casablanca rules can be out of date).
 */
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

// GET, POST /org/finance/deposits and POST /org/finance/deposits/{id}/void (04-api §Finance, Lot 5 step 5). GET:
// ADMIN, OPS, FINANCE; POST: ADMIN, FINANCE; void: ADMIN.
export type DepositSession = { id: string; label: string; staff: { id: string; name: string }; closed_at: string; declared_mad: number }
export type Deposit = {
  id: string
  /** Local date of the bank slip (`YYYY-MM-DD`). */
  deposited_on: string
  amount_mad: number
  reference: string
  recorded_by: { id: string; name: string }
  recorded_at: string
  voided_at: string | null
  voided_by: { id: string; name: string } | null
  void_reason: string | null
  /** Empty once voided; `declared_mad` and `variance_mad` are null then. */
  sessions: DepositSession[]
  declared_mad: number | null
  /** amount − Σ declared. */
  variance_mad: number | null
}
/** `deposits` newest `deposited_on` first, voided included; `undeposited` every closed session in no live deposit (not range-bound), oldest first. */
export type Deposits = { deposits: Deposit[]; undeposited: DepositSession[] }
export type NewDeposit = { deposited_on: string; amount_mad: number; reference: string; session_ids: string[] }

export const RECORD_ROLES: readonly Role[] = ['ADMIN', 'FINANCE']
export const VOID_ROLES: readonly Role[] = ['ADMIN']

/** Local dates `YYYY-MM-DD` on `deposited_on`, both included, ≤ 92 days. */
export const useDeposits = (range: { from: string; to: string }) =>
  useOrgQuery<Deposits>(['finance', 'deposits', range], `/org/finance/deposits?${new URLSearchParams(range)}`)

/** Both writes refetch every finance query of the organizer: a session's "Dépôt" column and `undeposited_mad` move too. */
export const useRecordDeposit = () => useOrgMutation(['finance'], (body: NewDeposit, send) => send<Deposit>({ method: 'POST', path: '/org/finance/deposits', body }))
export const useVoidDeposit = () =>
  useOrgMutation(['finance'], ({ id, reason }: { id: string; reason: string }, send) => send<Deposit>({ method: 'POST', path: `${path('org', 'finance', 'deposits', id)}/void`, body: { reason } }))
