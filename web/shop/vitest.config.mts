import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

const path = (p: string) => fileURLToPath(new URL(p, import.meta.url))

export default defineConfig({
  resolve: {
    alias: {
      '@': path('./'),
      // Route handlers run on the server; tests stand in for that environment.
      'server-only': path('./node_modules/server-only/empty.js'),
    },
  },
  test: { environment: 'node' },
})
