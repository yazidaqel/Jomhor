import { fileURLToPath } from 'node:url'
import type { NextConfig } from 'next'

// The repo root, so the Tailwind config can read design/tokens.json.
const root = fileURLToPath(new URL('../..', import.meta.url))

const config: NextConfig = {
  poweredByHeader: false,
  turbopack: { root },
  outputFileTracingRoot: root,
}

export default config
