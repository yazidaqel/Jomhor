import { useOrgMutation, useOrgQuery, type Send } from '@/features/auth/organizer'
import { path } from '@/lib/api'

// docs/04-api.md §Organizer /org/devices*, /org/staff*, /org/events/{id}/devices. ADMIN/OPS only (FINANCE: 403).
export type DeviceStatus = 'PENDING' | 'ACTIVE' | 'REVOKED'
export type Device = {
  id: string
  label: string
  status: DeviceStatus
  /** `AB12 CD34`, also on the device's screen: matched before approval (Q30). */
  fingerprint: string
  enrolled_at: string
  approved_at: string | null
  last_seen_at: string | null
}
export type EnrolCode = { enrol_code: string; expires_at: string }
export type StaffRole = 'GATE' | 'SUPERVISOR'
export const STAFF_ROLES: readonly StaffRole[] = ['GATE', 'SUPERVISOR']
export type Staff = { id: string; name: string; role: StaffRole | 'ADMIN' | 'OPS' | 'FINANCE'; active: boolean; has_pin: boolean }
export type Assignment = {
  device_id: string
  label: string
  status: DeviceStatus
  gate_id: string
  gate_code: string
  manifest_version: number | null
  last_seen_at: string | null
  /** The device wiped its data after the event closed. */
  closed_ack_at: string | null
}

// Assignments sit under 'devices' too: approving or revoking a device refetches every assignment list.
export const useDevices = (refetchInterval?: number) => useOrgQuery<Device[]>(['devices'], '/org/devices', { refetchInterval })
export const useStaff = () => useOrgQuery<Staff[]>(['staff'], '/org/staff')
export const useAssignments = (eventId: number) =>
  useOrgQuery<Assignment[]>(['devices', 'events', String(eventId)], `${path('org', 'events', eventId)}/devices`)

export const useDeviceMutation = <V, R = unknown>(run: (v: V, send: Send) => Promise<R>) => useOrgMutation(['devices'], run)

/**
 * Staff writes. A PIN (create, reset) goes to `onPin` and is stripped from the result, so it never sits in the
 * mutation cache: the caller keeps it in the PIN dialog's state only (Q31: shown once).
 */
export function useStaffMutation(onPin: (pin: string, name: string) => void) {
  return useOrgMutation(['staff'], async (r: StaffWrite, send): Promise<void> => {
    if (r.kind === 'create') {
      const { pin, name } = await send<Staff & { pin: string }>({ method: 'POST', path: '/org/staff', body: r.body })
      onPin(pin, name)
    } else if (r.kind === 'pin') {
      const { pin } = await send<{ pin: string }>({ method: 'POST', path: `${path('org', 'staff', r.staff.id)}/pin` })
      onPin(pin, r.staff.name)
    } else {
      await send({ method: 'PATCH', path: path('org', 'staff', r.staff.id), body: { active: r.active } })
    }
  })
}
export type StaffWrite =
  | { kind: 'create'; body: { name: string; role: StaffRole } }
  | { kind: 'pin'; staff: Staff }
  | { kind: 'active'; staff: Staff; active: boolean }
