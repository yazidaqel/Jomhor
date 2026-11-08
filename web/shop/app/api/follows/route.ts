import { NextResponse, type NextRequest } from 'next/server'
import { FOLLOWS_COOKIE, FOLLOWS_MAX, parseFollows, UUID } from '@/lib/clubs'
import { errorResponse, forbidden, isSameOrigin } from '@/lib/http'
import { safeNext } from '@/lib/next-path'

/**
 * Follow or unfollow a club from a plain form (works with JS off): `organizer_id`, `follow=1|0`, `next`. Same origin
 * only (403); a malformed id or flag → 422. The `follows` cookie keeps at most FOLLOWS_MAX ids (oldest dropped); the
 * server keeps no follow list (04 §Club directory). → 303 to `safeNext(next)`.
 */
export async function POST(req: NextRequest) {
  if (!isSameOrigin(req)) return forbidden()
  const form = await req.formData().catch(() => null)
  const id = String(form?.get('organizer_id') ?? '').toLowerCase()
  const follow = form?.get('follow')
  if (!UUID.test(id) || (follow !== '1' && follow !== '0')) return errorResponse(422, 'VALIDATION_FAILED', 'organizer_id or follow')

  const ids = parseFollows(req.cookies.get(FOLLOWS_COOKIE)?.value).filter((x) => x !== id)
  if (follow === '1') ids.push(id)
  const res = new NextResponse(null, { status: 303, headers: { location: safeNext(form?.get('next')) } })
  res.cookies.set(FOLLOWS_COOKIE, ids.slice(-FOLLOWS_MAX).join(','), {
    path: '/',
    maxAge: 60 * 60 * 24 * 365,
    sameSite: 'lax',
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
  })
  return res
}
