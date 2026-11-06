// Theme from design/tokens.json (normative), loaded by app/globals.css via @config. No value is copied here.
import tokens from '../../design/tokens.json'

const kebab = (s: string) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
const map = <T,>(o: Record<string, T>, f: (v: T) => string) =>
  Object.fromEntries(Object.entries(o).map(([k, v]) => [kebab(k), f(v)]))

export default {
  theme: {
    extend: {
      // color.textSecondary → text-text-secondary, color.surface2 → bg-surface2, color.onAccent → text-on-accent
      colors: map(tokens.color, (v) => v),
      borderRadius: map(tokens.radius, (v) => `${v}px`),
      // size.touchMin → min-h-touch-min, size.ctaHeight → h-cta-height
      spacing: map(tokens.size, (v) => `${v}px`),
      // type.h1 → text-h1 (size, line height, weight, tracking); pair with font-display / font-body and `uppercase`.
      fontSize: Object.fromEntries(
        Object.entries(tokens.type).map(([k, t]) => [
          kebab(k),
          [
            `${t.size}px`,
            {
              fontWeight: String(t.weight),
              ...('lineHeight' in t && { lineHeight: String(t.lineHeight) }),
              ...('letterSpacing' in t && { letterSpacing: `${t.letterSpacing}px` }),
            },
          ],
        ]),
      ),
      // next/font sets the variables (app/layout.tsx); the token family names are the fallback.
      fontFamily: {
        display: [`var(--font-display)`, tokens.font.display, 'sans-serif'],
        body: [`var(--font-body)`, tokens.font.body, 'system-ui', 'sans-serif'],
      },
    },
  },
}
