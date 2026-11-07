import { fmt, type Dict } from './i18n'
import tokens from './tokens'

/** The club this shop sells for: design/tokens.json `club` (build-time identity, Q23). */
export const club = tokens.club

/** "Billetterie ATLAS FC": the header's secondary line after the Jomhor lockup, home page title. */
export const shopName = (d: Dict) => fmt(d.header.shop, { club: club.name })
