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
