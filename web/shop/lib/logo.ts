import 'server-only'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'

/**
 * A Jomhor SVG from public/brand (byte-equal copies of design/logo, brand.test.ts) as a data URL: ImageResponse
 * (Satori) draws `<img src>`, not module imports. Read from the app's own folder, so traced deploys carry it.
 */
export async function logoDataUrl(file: string) {
  const svg = await readFile(join(process.cwd(), 'public', 'brand', file))
  return `data:image/svg+xml;base64,${svg.toString('base64')}`
}
