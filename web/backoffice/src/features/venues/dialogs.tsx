import { useRef, type ComponentProps, type ReactNode, type RefObject } from 'react'
import { Controller, useForm, type UseFormRegisterReturn } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Pencil, Trash2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogClose, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useOrgMutation } from '@/features/auth/organizer'
import { path } from '@/lib/api'
import { integerInput } from '@/lib/forms'
import { DEFAULT_ZONE, isVenueZone } from '@/lib/time'
import { gateSchema, showApiError, useVenueMutation, venueErrorKey, type Gate, type Venue, type Zone } from './api'

/** Open state of a dialog, owned by the page so the dialog outlives the table row it acts on. */
export type DialogProps = { open: boolean; onOpenChange: (open: boolean) => void; fallbackFocus?: RefObject<HTMLElement | null> }

/**
 * Where focus goes on close: back to the element that opened the dialog, or to `fallback` when that element is
 * gone (its row was deleted, now or by someone else) or about to go (`markGone`, on a successful delete).
 */
function useReturnFocus(fallback?: RefObject<HTMLElement | null>) {
  const opener = useRef<HTMLElement | null>(null)
  const gone = useRef(false)
  return {
    onOpenAutoFocus: () => {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      gone.current = false
    },
    onCloseAutoFocus: (e: Event) => {
      const target = !gone.current && opener.current?.isConnected ? opener.current : fallback?.current
      if (!target) return
      e.preventDefault()
      target.focus()
    },
    markGone: () => void (gone.current = true),
  }
}

/** The form inside mounts on open, so it always starts from the current values. */
function FormDialog({ title, children, open, onOpenChange, fallbackFocus }: DialogProps & { title: string; children: (close: () => void) => ReactNode }) {
  const { onOpenAutoFocus, onCloseAutoFocus } = useReturnFocus(fallbackFocus)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent aria-describedby={undefined} onOpenAutoFocus={onOpenAutoFocus} onCloseAutoFocus={onCloseAutoFocus}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {children(() => onOpenChange(false))}
      </DialogContent>
    </Dialog>
  )
}

function TextField(props: {
  id: string
  label: string
  hint?: string
  error?: string
  registration: UseFormRegisterReturn
  inputMode?: 'numeric' | 'text'
  upper?: boolean
  list?: string
  /** A textarea, `dir="auto"` (free text may be Arabic). */
  multiline?: boolean
  /** Id of a description shared with other fields. */
  describedBy?: string
}) {
  const { id, error, registration } = props
  const described = [props.hint && `${id}-hint`, props.describedBy, error && `${id}-error`].filter(Boolean).join(' ')
  const aria = { 'aria-invalid': !!error, 'aria-describedby': described || undefined }
  return (
    <Field data-invalid={!!error}>
      <FieldLabel htmlFor={id}>{props.label}</FieldLabel>
      {props.multiline ? (
        <Textarea id={id} dir="auto" rows={3} {...aria} {...registration} />
      ) : (
        <Input
          id={id}
          inputMode={props.inputMode}
          list={props.list}
          autoCapitalize={props.upper ? 'characters' : undefined}
          {...aria}
          {...registration}
          onChange={(e) => {
            if (props.upper) {
              // Codes are typed in capitals; the caret stays where it was.
              const { selectionStart, selectionEnd } = e.target
              e.target.value = e.target.value.toUpperCase()
              e.target.setSelectionRange(selectionStart, selectionEnd)
            }
            return registration.onChange(e)
          }}
        />
      )}
      {props.hint && <FieldDescription id={`${id}-hint`}>{props.hint}</FieldDescription>}
      {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
    </Field>
  )
}

function Footer({ pending, submit, rootError }: { pending: boolean; submit: string; rootError?: string }) {
  const { t } = useTranslation()
  return (
    <>
      {rootError && <FieldError>{rootError}</FieldError>}
      <DialogFooter>
        <DialogClose asChild>
          <Button type="button" variant="outline">
            {t('common.cancel')}
          </Button>
        </DialogClose>
        <Button type="submit" disabled={pending}>
          {submit}
        </Button>
      </DialogFooter>
    </>
  )
}

export function EditButton({ label, ...props }: { label: string } & ComponentProps<typeof Button>) {
  return (
    <Button variant="ghost" size="icon" aria-label={label} title={label} {...props}>
      <Pencil />
    </Button>
  )
}

export function DeleteButton({ label, ...props }: { label: string } & ComponentProps<typeof Button>) {
  return (
    <Button variant="ghost" size="icon" aria-label={label} title={label} {...props}>
      <Trash2 />
    </Button>
  )
}

// ---- Venue ----

export function VenueDialog({ venue, onSaved, ...dialog }: DialogProps & { venue?: Venue; onSaved?: (v: Venue) => void }) {
  const { t } = useTranslation()
  return (
    <FormDialog {...dialog} title={t(venue ? 'venues.editTitle' : 'venues.new')}>
      {(close) => (
        <VenueForm
          venue={venue}
          onDone={(v) => {
            close()
            onSaved?.(v)
          }}
        />
      )}
    </FormDialog>
  )
}

// Suggestions: the zones Intl lists that a venue can use (same rule as the field's validation).
const TIME_ZONES = Intl.supportedValuesOf('timeZone').filter(isVenueZone)

function VenueForm({ venue, onDone }: { venue?: Venue; onDone: (v: Venue) => void }) {
  const { t } = useTranslation()
  const schema = z.object({
    name: z.string().trim().min(1, t('venues.nameRequired')),
    city: z.string().trim(), // "" clears it (PATCH) or leaves it unset (POST)
    capacity: integerInput(t('venues.venueCapacityInvalid'), 1),
    timezone: z.string().trim().refine(isVenueZone, t('venues.timezoneInvalid')),
  })
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      name: venue?.name ?? '',
      city: venue?.city ?? '',
      capacity: venue ? String(venue.capacity) : '',
      timezone: venue?.timezone ?? DEFAULT_ZONE,
    },
  })
  const save = useVenueMutation(({ timezone, ...rest }: z.output<typeof schema>, send) =>
    send<Venue>(
      venue
        ? // The zone only when changed: once events use the venue, any change is refused (VENUE_IN_USE).
          { method: 'PATCH', path: path('org', 'venues', venue.id), body: timezone === venue.timezone ? rest : { ...rest, timezone } }
        : { method: 'POST', path: '/org/venues', body: { ...rest, timezone } },
    ),
  )
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => save.mutate(v, { onSuccess: onDone, onError: (e) => showApiError(e, form, t) }))}>
      <FieldGroup>
        <TextField id="venue-name" label={t('venues.name')} error={errors.name?.message} registration={form.register('name')} />
        <TextField id="venue-city" label={t('venues.cityOptional')} error={errors.city?.message} registration={form.register('city')} />
        <TextField
          id="venue-capacity"
          label={t('venues.capacity')}
          inputMode="numeric"
          error={errors.capacity?.message}
          registration={form.register('capacity')}
        />
        <TextField
          id="venue-timezone"
          label={t('venues.timezone')}
          hint={t('venues.timezoneHint')}
          list="venue-timezones"
          error={errors.timezone?.message}
          registration={form.register('timezone')}
        />
        <datalist id="venue-timezones">
          {TIME_ZONES.map((z) => (
            <option key={z} value={z} />
          ))}
        </datalist>
        <Footer pending={save.isPending} submit={t(venue ? 'common.save' : 'common.create')} rootError={errors.root?.message} />
      </FieldGroup>
    </form>
  )
}

// ---- Zone ----

export function ZoneDialog({ venueId, zone, ...dialog }: DialogProps & { venueId: string; zone?: Zone }) {
  const { t } = useTranslation()
  return (
    <FormDialog {...dialog} title={zone ? t('venues.zones.editTitle', { code: zone.code }) : t('venues.zones.newTitle')}>
      {(close) => <ZoneForm venueId={venueId} zone={zone} onDone={close} />}
    </FormDialog>
  )
}

function ZoneForm({ venueId, zone, onDone }: { venueId: string; zone?: Zone; onDone: () => void }) {
  const { t } = useTranslation()
  const schema = z.object({
    code: z.string().trim().min(1, t('venues.zones.codeRequired')),
    name: z.string().trim().min(1, t('venues.nameRequired')),
    capacity: integerInput(t('venues.zones.capacityInvalid'), 0),
    seated: z.boolean(),
  })
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      code: zone?.code ?? '',
      name: zone?.name ?? '',
      capacity: zone ? String(zone.capacity) : '',
      seated: zone?.seated ?? false,
    },
  })
  const zones = path('org', 'venues', venueId, 'zones')
  const save = useVenueMutation((body: z.output<typeof schema>, send) =>
    send(zone ? { method: 'PATCH', path: `${zones}${path(zone.id)}`, body } : { method: 'POST', path: zones, body }),
  )
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => save.mutate(v, { onSuccess: onDone, onError: (e) => showApiError(e, form, t) }))}>
      <FieldGroup>
        <TextField
          id="zone-code"
          label={t('venues.zones.code')}
          hint={t('venues.zones.codeHint')}
          upper
          error={errors.code?.message}
          registration={form.register('code')}
        />
        <TextField id="zone-name" label={t('venues.zones.name')} error={errors.name?.message} registration={form.register('name')} />
        <TextField
          id="zone-capacity"
          label={t('venues.zones.capacity')}
          inputMode="numeric"
          error={errors.capacity?.message}
          registration={form.register('capacity')}
        />
        <Controller
          control={form.control}
          name="seated"
          render={({ field }) => (
            <Field orientation="horizontal">
              <Checkbox
                id="zone-seated"
                name={field.name}
                ref={field.ref}
                checked={field.value}
                onCheckedChange={(c) => field.onChange(c === true)}
                onBlur={field.onBlur}
              />
              <FieldLabel htmlFor="zone-seated">{t('venues.zones.seated')}</FieldLabel>
            </Field>
          )}
        />
        <Footer pending={save.isPending} submit={t(zone ? 'common.save' : 'common.create')} rootError={errors.root?.message} />
      </FieldGroup>
    </form>
  )
}

// ---- Gate ----

export function GateDialog({ venueId, gate, ...dialog }: DialogProps & { venueId: string; gate?: Gate }) {
  const { t } = useTranslation()
  return (
    <FormDialog {...dialog} title={gate ? t('venues.gates.editTitle', { code: gate.code }) : t('venues.gates.newTitle')}>
      {(close) => <GateForm venueId={venueId} gate={gate} onDone={close} />}
    </FormDialog>
  )
}

function GateForm({ venueId, gate, onDone }: { venueId: string; gate?: Gate; onDone: () => void }) {
  const { t } = useTranslation()
  const schema = gateSchema(t, gate)
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      code: gate?.code ?? '',
      name: gate?.name ?? '',
      directions: gate?.directions ?? '',
      latitude: gate?.latitude == null ? '' : String(gate.latitude),
      longitude: gate?.longitude == null ? '' : String(gate.longitude),
    },
  })
  const gates = path('org', 'venues', venueId, 'gates')
  const save = useVenueMutation((body: z.output<typeof schema>, send) =>
    send(gate ? { method: 'PATCH', path: `${gates}${path(gate.id)}`, body } : { method: 'POST', path: gates, body }),
  )
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => save.mutate(v, { onSuccess: onDone, onError: (e) => showApiError(e, form, t) }))}>
      <FieldGroup>
        <TextField
          id="gate-code"
          label={t('venues.gates.code')}
          hint={t('venues.gates.codeHint')}
          upper
          error={errors.code?.message}
          registration={form.register('code')}
        />
        <TextField id="gate-name" label={t('venues.name')} error={errors.name?.message} registration={form.register('name')} />
        <TextField
          id="gate-directions"
          label={t('venues.gates.directions')}
          hint={t('venues.gates.directionsHint')}
          multiline
          error={errors.directions?.message}
          registration={form.register('directions')}
        />
        <div className="grid grid-cols-2 gap-4">
          <TextField
            id="gate-latitude"
            label={t('venues.gates.latitude')}
            inputMode="text" // the iOS decimal pad has no minus sign; Moroccan longitudes are negative
            describedBy="gate-location-hint"
            error={errors.latitude?.message}
            registration={form.register('latitude')}
          />
          <TextField
            id="gate-longitude"
            label={t('venues.gates.longitude')}
            inputMode="text" // the iOS decimal pad has no minus sign; Moroccan longitudes are negative
            describedBy="gate-location-hint"
            error={errors.longitude?.message}
            registration={form.register('longitude')}
          />
        </div>
        <FieldDescription id="gate-location-hint">{t('venues.gates.locationHint')}</FieldDescription>
        <Footer pending={save.isPending} submit={t(gate ? 'common.save' : 'common.create')} rootError={errors.root?.message} />
      </FieldGroup>
    </form>
  )
}

// ---- Delete ----

/**
 * Confirmation; stays open while deleting and shows a refusal (ZONE_IN_USE, PRICE_IN_USE…). On success, focus goes to
 * `fallbackFocus`. Afterwards the organizer's queries under `scope` are refetched (default: the venues).
 */
export function DeleteDialog({
  title,
  body,
  path,
  scope = ['venues'],
  open,
  onOpenChange,
  fallbackFocus,
}: DialogProps & { title: string; body: string; path: string; scope?: readonly unknown[] }) {
  const { t } = useTranslation()
  const { onOpenAutoFocus, onCloseAutoFocus, markGone } = useReturnFocus(fallbackFocus)
  const remove = useOrgMutation(scope, (_: void, send) => send({ method: 'DELETE', path }))
  return (
    <AlertDialog
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o)
        remove.reset()
      }}
    >
      <AlertDialogContent onOpenAutoFocus={onOpenAutoFocus} onCloseAutoFocus={onCloseAutoFocus}>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{body}</AlertDialogDescription>
        </AlertDialogHeader>
        {remove.error && <FieldError>{t(venueErrorKey(remove.error))}</FieldError>}
        <AlertDialogFooter>
          <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
          <Button
            variant="destructive"
            disabled={remove.isPending}
            onClick={() =>
              remove.mutate(undefined, {
                onSuccess: () => {
                  markGone()
                  onOpenChange(false)
                },
              })
            }
          >
            {t('common.delete')}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
