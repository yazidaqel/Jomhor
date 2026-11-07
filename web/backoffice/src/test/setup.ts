import { configure } from '@testing-library/dom'

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
