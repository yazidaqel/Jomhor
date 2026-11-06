import { defaultLocale, fmt, getDictionary } from './i18n'
import tokens from './tokens'

/** The club this shop sells for: design/tokens.json `club` (build-time identity, Q23). */
export const club = tokens.club

/** "ATLAS FC · Billetterie": header, title template, Open Graph site name. */
export const shopName = fmt(getDictionary(defaultLocale).header.shop, { club: club.name })
