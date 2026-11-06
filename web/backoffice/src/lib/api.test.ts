import { afterEach, expect, it, vi } from 'vitest'
import { api, ApiError } from './api'
import { session } from './session'

afterEach(() => {
  vi.unstubAllGlobals()
  session.clear()
})

function stubFetch(impl: () => Promise<Response>) {
  const fetchMock = vi.fn<typeof fetch>(impl)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function sentHeaders(fetchMock: ReturnType<typeof stubFetch>) {
  const [url, init] = fetchMock.mock.calls[0]!
  return { url, headers: new Headers(init?.headers) }
}

it('calls /v1 with the bearer token and the given X-Organizer-Id', async () => {
  session.start('tok-123', '2999-01-01T00:00:00Z')
  const fetchMock = stubFetch(async () => Response.json({ id: 'evt-1' }))

  await expect(api('/org/events', { organizerId: 'org-atlas' })).resolves.toEqual({ id: 'evt-1' })

  const { url, headers } = sentHeaders(fetchMock)
  expect(url).toBe('/v1/org/events')
  expect(headers.get('Authorization')).toBe('Bearer tok-123')
  expect(headers.get('X-Organizer-Id')).toBe('org-atlas')
})

it('sends no X-Organizer-Id when none is given', async () => {
  session.start('tok-123', '2999-01-01T00:00:00Z')
  const fetchMock = stubFetch(async () => Response.json({}))

  await api('/org/me')

  expect(sentHeaders(fetchMock).headers.has('X-Organizer-Id')).toBe(false)
})

it('never overrides a header the caller set', async () => {
  session.start('tok-123', '2999-01-01T00:00:00Z')
  const fetchMock = stubFetch(async () => Response.json({}))

  await api('/org/events', { organizerId: 'org-a', headers: { 'X-Organizer-Id': 'org-b', Authorization: 'Bearer other' } })

  const { headers } = sentHeaders(fetchMock)
  expect(headers.get('X-Organizer-Id')).toBe('org-b')
  expect(headers.get('Authorization')).toBe('Bearer other')
})

it('returns undefined for a 2xx with an empty body', async () => {
  stubFetch(async () => new Response(null, { status: 202 }))

  await expect(api('/org/auth/otp', { method: 'POST', body: '{}' })).resolves.toBeUndefined()
})

it('parses the 04 error shape into ApiError', async () => {
  const body = { code: 'SOLD_OUT', message: 'Complet', details: { price_category_id: 'pc-1' } }
  stubFetch(async () => Response.json(body, { status: 409 }))

  const err = await api('/org/events', { method: 'POST', body: '{}' }).catch((e: unknown) => e)

  expect(err).toBeInstanceOf(ApiError)
  expect(err).toMatchObject({ status: 409, ...body })
})

it('keeps the code when details is null', async () => {
  stubFetch(async () => Response.json({ code: 'FORBIDDEN', message: 'Non', details: null }, { status: 403 }))

  await expect(api('/org/events')).rejects.toMatchObject({ status: 403, code: 'FORBIDDEN', details: {} })
})

it('falls back to HTTP_<status> when the body is not the 04 shape', async () => {
  stubFetch(async () => new Response('Bad Gateway', { status: 502, statusText: 'Bad Gateway' }))

  await expect(api('/org/events')).rejects.toMatchObject({ status: 502, code: 'HTTP_502', details: {} })
})

it('clears the session on a 401, except from /org/auth/*', async () => {
  session.start('tok-123', '2999-01-01T00:00:00Z')
  stubFetch(async () => Response.json({ code: 'OTP_INVALID', message: 'x' }, { status: 401 }))
  await expect(api('/org/auth/verify', { method: 'POST', body: '{}' })).rejects.toMatchObject({ code: 'OTP_INVALID' })
  expect(session.token).toBe('tok-123')

  stubFetch(async () => Response.json({ code: 'UNAUTHORIZED', message: 'x' }, { status: 401 }))
  await expect(api('/org/me')).rejects.toMatchObject({ status: 401 })
  expect(session.token).toBeNull()
})

it('treats an expired token as no session', async () => {
  session.start('tok-old', '2000-01-01T00:00:00Z')
  const fetchMock = stubFetch(async () => Response.json({}))

  await api('/org/me')

  expect(session.token).toBeNull()
  expect(sentHeaders(fetchMock).headers.has('Authorization')).toBe(false)
})

it('wraps a failed fetch as NETWORK_ERROR', async () => {
  stubFetch(async () => {
    throw new TypeError('Failed to fetch')
  })

  const err = await api('/org/events').catch((e: unknown) => e)

  expect(err).toBeInstanceOf(ApiError)
  expect(err).toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
})

it('wraps a body read failure (connection dropped mid-body) as NETWORK_ERROR', async () => {
  const res = new Response('{"id":')
  vi.spyOn(res, 'text').mockRejectedValue(new TypeError('network error'))
  stubFetch(async () => res)

  await expect(api('/org/events')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
})

it('rethrows the original AbortError when the caller aborted', async () => {
  const controller = new AbortController()
  controller.abort()
  stubFetch(async () => {
    throw new DOMException('aborted', 'AbortError')
  })

  const err = await api('/org/events', { signal: controller.signal }).catch((e: unknown) => e)

  expect(err).not.toBeInstanceOf(ApiError)
  expect(err).toMatchObject({ name: 'AbortError' })
})
