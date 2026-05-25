export function apiPath(slug: string, path: string): string {
  const normalizedSlug = slug.trim()
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `/api/v1/${normalizedSlug}${normalizedPath}`
}
