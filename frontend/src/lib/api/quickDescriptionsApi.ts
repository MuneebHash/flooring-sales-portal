import { getActiveSlug } from '../tenant'
import { get } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'

// Phase 14C — tenant quick-add descriptions (GET /api/v1/{slug}/quick-descriptions).
// Per-business "details of sale" presets, ordered by sort_order and with the
// [BUSINESS_NAME] token already substituted SERVER-SIDE (the client never performs
// business-name substitution).
//
// This endpoint IS tenant-scoped, so the path goes through apiPath(getActiveSlug(), …)
// — matching every other authenticated wrapper. Do NOT copy the public business-lookup
// (fetchPublicBusiness) pattern, which is unauthenticated and deliberately bypasses
// apiPath.
//
// Response is the standard ApiSuccess envelope wrapping a plain string list:
//   { "data": ["Aussie Floors Group to supply and install …", …] }
// Unwraps to string[]. An empty list (or any fetch failure handled by the caller)
// renders no quick-add buttons.
export function fetchQuickDescriptions(): Promise<string[]> {
  return get<ApiSuccess<string[]>>(
    apiPath(getActiveSlug(), '/quick-descriptions'),
  ).then((response) => response.data)
}
