import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite } from '@/features/auth/organizer'
import { cn } from '@/lib/utils'
import {
  CANVAS,
  LAYOUT_IDS,
  LAYOUTS,
  mapBody,
  mapErrorField,
  mapSchema,
  NO_LAYOUT,
  OFF_MAP,
  switchLayout,
  toMapForm,
  useSaveMap,
  venueErrorKey,
  type MapBody,
  type MapForm,
  type VenueDetail,
} from './api'

/** B10 "Plan": the venue map template and the zone → slot table (docs/04-api.md §Venue map). FINANCE: preview only. */
export function MapCard({ venue }: { venue: VenueDetail }) {
  const { t } = useTranslation()
  const canWrite = useCanWrite()
  const initial = toMapForm(venue)
  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t('venues.map.title')}
        </CardTitle>
        <CardDescription>{t('venues.map.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {venue.zones.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('venues.map.empty')}</p>
        ) : canWrite ? (
          // Remounted when the server map changes (the refetch after a save): the form restarts from it.
          <MapEditor key={JSON.stringify(initial)} venue={venue} initial={initial} />
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-sm">{t('venues.map.layoutValue', { layout: t(`venues.map.layouts.${initial.layout}`) })}</p>
            <Preview venue={venue} form={initial} />
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function MapEditor({ venue, initial }: { venue: VenueDetail; initial: MapForm }) {
  const { t } = useTranslation()
  const save = useSaveMap(venue.id)
  const form = useForm<MapForm, unknown, MapBody>({ resolver: zodResolver(mapSchema(t).transform(mapBody)), defaultValues: initial })
  const values = useWatch({ control: form.control }) as MapForm
  const { errors } = form.formState
  const changed = JSON.stringify(values) !== JSON.stringify(initial)

  const slots = values.layout === NO_LAYOUT ? [] : LAYOUTS[values.layout].slots.map((s) => s.id)
  const taken = new Set<string>(values.zones.map((r) => r.map_slot))
  const code = (zoneId: number) => venue.zones.find((z) => z.id === zoneId)!.code
  // Zones on the saved map that a template switch takes off it.
  const dropped =
    values.layout === initial.layout
      ? []
      : initial.zones.filter((z, i) => z.map_slot !== OFF_MAP && values.zones[i]!.map_slot === OFF_MAP).map((z) => code(z.zone_id))

  const submit = form.handleSubmit((body) => {
    save.mutate(body, {
      onSuccess: () => toast.success(t('venues.map.saved')),
      onError: (e) => {
        const field = mapErrorField(e, form.getValues(), body)
        // A deleted zone: the refetch (every write refetches) remounts the editor without it, so a toast, not a field error.
        if (field?.key === 'venues.map.zoneGone') toast.error(t(field.key))
        else if (field) form.setError(field.name, { message: t(field.key) }, { shouldFocus: true })
        else form.setError('root', { message: t(venueErrorKey(e)) })
      },
    })
  })

  return (
    <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-5">
      <Controller
        control={form.control}
        name="layout"
        render={({ field }) => (
          <Field data-invalid={!!errors.layout} className="max-w-xs">
            <FieldLabel htmlFor="map-layout">{t('venues.map.layout')}</FieldLabel>
            <Select
              name={field.name}
              value={field.value}
              onValueChange={(layout: MapForm['layout']) => {
                form.clearErrors()
                // From the saved map, so Stade → Théâtre → Stade restores it; manual edits are dropped on a switch.
                form.reset(switchLayout(initial, layout), { keepDefaultValues: true })
              }}
            >
              <SelectTrigger
                id="map-layout"
                ref={field.ref}
                onBlur={field.onBlur}
                className="w-full"
                aria-invalid={!!errors.layout}
                aria-describedby={errors.layout ? 'map-layout-error' : undefined}
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {([NO_LAYOUT, ...LAYOUT_IDS] as const).map((l) => (
                  <SelectItem key={l} value={l}>
                    {t(`venues.map.layouts.${l}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {errors.layout && <FieldError id="map-layout-error">{errors.layout.message}</FieldError>}
          </Field>
        )}
      />
      {dropped.length > 0 && (
        <p role="status" className="rounded-md border border-warning-border bg-warning-muted px-3 py-2 text-sm text-warning-foreground">
          {t('venues.map.dropped', { codes: dropped.join(', ') })}
        </p>
      )}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Preview venue={venue} form={values} />
        {values.layout !== NO_LAYOUT && (
          <div className="flex flex-col gap-2">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('venues.zones.code')}</TableHead>
                  <TableHead>{t('venues.zones.name')}</TableHead>
                  <TableHead>{t('venues.map.slot')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {venue.zones.map((z, i) => {
                  const own = values.zones[i]!.map_slot
                  const error = errors.zones?.[i]?.map_slot?.message
                  return (
                    <TableRow key={z.id}>
                      <TableCell className="font-semibold">{z.code}</TableCell>
                      <TableCell className="whitespace-normal">{z.name}</TableCell>
                      <TableCell className="whitespace-normal">
                        <Controller
                          control={form.control}
                          name={`zones.${i}.map_slot`}
                          render={({ field }) => (
                            <Field data-invalid={!!error} className="gap-1">
                              <Select name={field.name} value={field.value} onValueChange={(v: MapForm['zones'][number]['map_slot']) => field.onChange(v)}>
                                <SelectTrigger
                                  ref={field.ref}
                                  onBlur={field.onBlur}
                                  className="w-full min-w-36"
                                  aria-label={t('venues.map.slotLabel', { code: z.code })}
                                  aria-invalid={!!error}
                                  aria-describedby={error ? `map-slot-${z.id}-error` : undefined}
                                >
                                  <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                  <SelectItem value={OFF_MAP}>{t('venues.map.off')}</SelectItem>
                                  {slots
                                    .filter((s) => s === own || !taken.has(s))
                                    .map((s) => (
                                      <SelectItem key={s} value={s}>
                                        {t(`venues.map.slots.${s}`)}
                                      </SelectItem>
                                    ))}
                                </SelectContent>
                              </Select>
                              {error && <FieldError id={`map-slot-${z.id}-error`}>{error}</FieldError>}
                            </Field>
                          )}
                        />
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
            <FieldDescription>{t('venues.map.helper')}</FieldDescription>
          </div>
        )}
      </div>
      <div className="flex flex-wrap items-center justify-end gap-3">
        {errors.root?.message && <FieldError className="mr-auto">{errors.root.message}</FieldError>}
        <Button type="submit" disabled={!changed || save.isPending}>
          {t('venues.map.save')}
        </Button>
      </div>
    </form>
  )
}

const box = ([x, y, w, h]: number[]) => ({
  left: `${(x! / CANVAS.w) * 100}%`,
  top: `${(y! / CANVAS.h) * 100}%`,
  width: `${(w! / CANVAS.w) * 100}%`,
  height: `${(h! / CANVAS.h) * 100}%`,
})

/** The 4:3 canvas, never mirrored: an assigned slot shows its zone code, a free one is dashed with its label. */
function Preview({ venue, form }: { venue: VenueDetail; form: MapForm }) {
  const { t } = useTranslation()
  if (form.layout === NO_LAYOUT) return <p className="text-sm text-muted-foreground">{t('venues.map.noLayout')}</p>
  const layout = LAYOUTS[form.layout]
  const zoneOn = new Map(form.zones.map((z) => [z.map_slot, venue.zones.find((v) => v.id === z.zone_id)!]))
  return (
    <div role="group" aria-label={t('venues.map.preview')} dir="ltr" className="relative aspect-[4/3] w-full max-w-xl self-start">
      <div
        style={box(layout.center.rect)}
        className="absolute flex items-center justify-center rounded-xl bg-muted text-xs font-medium tracking-wide text-muted-foreground uppercase"
      >
        {t(`venues.map.center.${layout.center.kind}`)}
      </div>
      {layout.slots.map((s) => {
        const zone = zoneOn.get(s.id)
        return (
          <div
            key={s.id}
            data-slot={s.id}
            style={box(s.rect)}
            className={cn(
              'absolute flex items-center justify-center overflow-hidden rounded-lg border p-1 text-center text-xs',
              zone ? 'border-primary bg-primary/10 font-semibold text-foreground' : 'border-dashed border-border text-muted-foreground',
            )}
          >
            <span className="truncate">{zone ? zone.code : t(`venues.map.slots.${s.id}`)}</span>
          </div>
        )
      })}
    </div>
  )
}
