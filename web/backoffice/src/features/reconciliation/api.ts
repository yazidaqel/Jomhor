import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useOrganizer, useOrgQuery } from '@/features/auth/organizer'
import type { EventStatus } from '@/features/events/api'
import { download, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'

// GET /org/events/{id}/reconciliation and its .csv (04-api §Organizer, §Gate reports). ADMIN, OPS and FINANCE read.
// Computed on read (Q37): late syncs keep correcting it until `final`.
export type OverrideReason = 'ID_CHECKED' | 'DEVICE_FAULT' | 'DAMAGED_TICKET' | 'OTHER'
export type ReconciliationZone = { zone_id: number; code: string; name: string; sold: number; entered: number; no_show: number; overrides: number }
export type ReconciliationGate = {
  gate_id: string
  code: string
  name: string
  zone_codes: string[]
  entered: number
  /** Refused codes only, counts > 0. */
  refused_by_result: Record<string, number>
  duplicates_late: number
  overrides: number
}
export type Reconciliation = {
  event_id: number
  status: EventStatus
  generated_at: string
  /** The event is CLOSED and every assigned device has acked its wipe. */
  final: boolean
  /** `unsynced` = assigned devices that have not acked the close (every one of them before the close). */
  devices: { assigned: number; acked: number; unsynced: { device_id: string; label: string; gate_code: string; last_seen_at: string | null }[] }
  totals: {
    sold: number
    /** Tickets with a canonical scan. */
    entered: number
    /** Sold tickets with no canonical scan: entered + no_show = sold. */
    no_show: number
    refused_by_result: Record<string, number>
    /** Admitted − canonical (Q34, derived on read). */
    duplicates_late: number
    overrides_by_reason: Partial<Record<OverrideReason, number>>
  }
  /** By the ticket's zone. */
  zones: ReconciliationZone[]
  /** By the scan's gate. */
  gates: ReconciliationGate[]
}

/** While provisional, late syncs still move the figures: refetch every 30 s; once final, never. */
export const RECONCILIATION_REFETCH_MS = 30_000

// Under ['events', id]: closing the event (B4) refetches it too.
export const useReconciliation = (eventId: string) =>
  useOrgQuery<Reconciliation>(['events', eventId, 'reconciliation'], `${path('org', 'events', eventId)}/reconciliation`, {
    refetchInterval: (q) => (q.state.data?.final ? false : RECONCILIATION_REFETCH_MS),
  })

/** "Exporter CSV" (B4 and B5): Bearer + X-Organizer-Id, saved under the server's filename with its BOM. */
export function useReconciliationCsv(eventId: string) {
  const { t } = useTranslation()
  const { organizer_id: organizerId } = useOrganizer()
  return useMutation({
    mutationFn: () => download(`${path('org', 'events', eventId)}/reconciliation.csv`, `reconciliation-${eventId}.csv`, { organizerId }),
    onError: (e) => toast.error(t(errorKey(e))),
  })
}

export const sum = (counts: Record<string, number | undefined>) => Object.values(counts).reduce<number>((a, n) => a + (n ?? 0), 0)
