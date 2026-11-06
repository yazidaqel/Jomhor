/**
 * The `next` redirect target after login: a same-site relative path, normalised, else `/`. Rejects anything a browser
 * could read as another origin: `//host`, `/\host`, absolute URLs, backslashes, whitespace and control characters
 * (browsers strip tab and newline, so `/\t/host` would become `//host`), and dot segments that resolve to `//host`
 * (`/.//host`, `/a/..//host`).
 */
export function safeNext(raw: unknown): string {
  // eslint-disable-next-line no-control-regex -- rejecting control characters is the point
  if (typeof raw !== 'string' || raw.length > 2048 || !/^\/(?![/\\])[^\\\s\x00-\x1f\x7f]*$/.test(raw)) return '/'
  const base = 'https://shop.invalid'
  const url = new URL(raw, base)
  if (url.origin !== base || url.pathname.startsWith('//')) return '/'
  return url.pathname + url.search + url.hash
}
