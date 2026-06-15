import { get } from './client'
import type { ApiSuccess, PublicBusiness } from './types'

// Public, UNAUTHENTICATED tenant lookup: GET /api/v1/public/businesses/{slug}.
//
// This endpoint is NOT tenant-scoped, so it must NOT go through apiPath(slug, …)
// — that builds /api/v1/{slug}/… and would produce the wrong URL. The path is
// built directly here. The slug is encoded defensively even though the router
// only ever passes clean slug segments.
//
// The standard { data: … } envelope is unwrapped; callers get the branding
// fields directly. A 404 surfaces as an ApiError with status 404 (tenant not
// found); network/5xx/other failures surface as ApiError with a non-404 status,
// which callers must distinguish from "not found".
export function fetchPublicBusiness(slug: string): Promise<PublicBusiness> {
  return get<ApiSuccess<PublicBusiness>>(
    `/api/v1/public/businesses/${encodeURIComponent(slug)}`,
  ).then((response) => response.data)
}
