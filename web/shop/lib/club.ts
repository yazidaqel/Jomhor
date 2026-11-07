import { fmt, type Dict } from './i18n'
import tokens from './tokens'

/** The club this shop sells for: design/tokens.json `club` (build-time identity, Q23). */
export const club = tokens.club

/** "ATLAS FC · Billetterie": header, title template, Open Graph site name. */
export const shopName = (d: Dict) => fmt(d.header.shop, { club: club.name })
