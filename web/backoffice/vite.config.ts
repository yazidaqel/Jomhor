import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  // API_PROXY_TARGET: local backend when 8080 is taken by something else.
  server: { proxy: { '/v1': process.env.API_PROXY_TARGET ?? 'http://localhost:8080' } },
  // testTimeout: whole-app renders (shell with sidebar, then the page) and many byRole queries; 5 s is too tight on a
  // loaded machine. The setup file raises findBy*/waitFor the same way.
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], testTimeout: 10_000 },
})
