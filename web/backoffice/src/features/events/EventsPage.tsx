import { useTranslation } from 'react-i18next'

export function EventsPage() {
  const { t } = useTranslation()
  return (
    <>
      <h1 className="text-[22px] font-bold">{t('events.title')}</h1>
      <p className="mt-2 text-sm text-muted-foreground">{t('events.empty')}</p>
    </>
  )
}
