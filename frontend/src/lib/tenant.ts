export const DEFAULT_BUSINESS_SLUG = 'aussie-floors-group'

// Where to send visitors who arrive without a business slug (bare "/" or any
// path that does not match a tenant route). This is the EXTERNAL marketing /
// subscription site, so it is config-driven — non-prod environments can point
// it elsewhere via VITE_MARKETING_URL.
export const MARKETING_URL: string =
  (import.meta.env as Record<string, string | undefined>).VITE_MARKETING_URL ??
  'https://tradextack.com'

// The active business slug is the first path segment of the current URL:
//   /terralux/login                -> terralux
//   /terralux/dashboard            -> terralux
//   /aussie-floors-group/orders/89 -> aussie-floors-group
//
// The non-React API layer cannot read route params via hooks, so it reads the
// live URL here. This is always correct for the route the user is on, including
// /{slug}/login at login time when no auth/session exists yet — the URL is the
// source of truth, never auth state, and there is no render-time module state.
// Falls back to the default slug only when there is no segment (e.g. bare "/"),
// a case that never issues API calls.
export function getActiveSlug(): string {
  const segment = window.location.pathname.split('/').filter(Boolean)[0]
  return segment && segment.length > 0 ? segment : DEFAULT_BUSINESS_SLUG
}

// Reserved app/system words that must NEVER be treated as a business slug. A
// tenant slug equal to any of these would shadow a real top-level app path
// (/login, /orders, /account, …), so the router redirects them to the marketing
// site instead of routing them as a tenant.
//
// THIS LIST MUST MIRROR the backend `chk_business_slug_reserved` constraint
// (last replaced by db/migration/V18__reserve_slug_q.sql, which added 'q' for
// the public quote page route /q/{token} — closes #105) EXACTLY — it is the
// single frontend source of truth. If a word is added/removed/changed in that
// constraint, change it here too, or the two will silently drift.
export const RESERVED_BUSINESS_SLUGS = [
  'admin', 'api', 'login', 'static', 'auth', 'health',
  'dashboard', 'orders', 'select-store', 'assets', 'new',
  'logout', 'account', 'settings', 'public', 'q',
] as const

const RESERVED_BUSINESS_SLUG_SET: ReadonlySet<string> = new Set(
  RESERVED_BUSINESS_SLUGS,
)

// True when `value` is a reserved word and therefore NOT a valid business slug.
// Used by the router guard to redirect reserved top-level paths to the marketing
// site. Exact (case-sensitive) match, mirroring the backend constraint — real
// slugs are always lowercase (chk_business_slug_format).
export function isReservedBusinessSlug(value: string): boolean {
  return RESERVED_BUSINESS_SLUG_SET.has(value)
}
