import { configure } from '@testing-library/dom'
import { toast } from 'sonner'
import { afterEach } from 'vitest'

// jsdom lacks matchMedia (shadcn sidebar, sonner).
// Defined on window directly, not with vi.stubGlobal: the suites call vi.unstubAllGlobals() after each test.
if (typeof window !== "undefined" && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

// findBy*/waitFor: 1 s by default, too tight for whole-app renders on a loaded machine (see testTimeout in vite.config.ts).
configure({ asyncUtilTimeout: 4000 })

// sonner keeps toasts in a module-level store and replays the still-active ones to every Toaster that mounts. A toast
// whose test ended before its auto-dismiss would reappear in the next test of the file (two copies of the same text, or
// a stale toast passing for a new one). Runs after the suites' cleanup: setup hooks are registered first, run last.
afterEach(() => void toast.dismiss())
